package cn.origolds.ia;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 已经装到金源圆锯刃槽上的圆锯刀刃（{@code origolds_ia:sawblade}）。
 *
 * <p>它是一张<b>没有厚度</b>的贴图（{@code sawblade.png}）。第 149 轮把它从方块改成了
 * <b>实体</b>，为的是两件事：</p>
 * <ul>
 *   <li>刀刃不再占用方块层，那一格可以是空气、可以是别的方块，
 *       <b>多个刀刃也能同时待在同一个格子里</b>（不同刃槽面各自伸出来的一片）；
 *       只要玩家能点到刃槽的那个面，就能往那个面外侧那格装一片；</li>
 *   <li>它<b>不受伤害、无法被攻击、也杀不死</b>——不是 {@code LivingEntity}，
 *       而且 {@link #isPickable()} 返回 {@code false}，准星根本选不中它，
 *       左键会直接穿过去打到后面的刃槽。想拆掉它只有两条路：
 *       拆掉它附着的那个刃槽，或者手持圆锯刃片 Shift+左键它附着的那个刃槽的面
 *       （见 {@link OGESawbladeMountEvents}）。</li>
 * </ul>
 *
 * <h2>挂载关系怎么记</h2>
 * <p>每片刀刃只记三样东西：{@link #getMountPos() 附着在哪个刃槽}、
 * {@link #getMountFace() 挂在刃槽的哪个面}、以及顶 / 底面刀刃的
 * {@link #getBladeFacing() 水平朝向}。位置恒等于
 * {@code 刃槽坐标.relative(MOUNT_FACE)}，渲染时直接用实体自身坐标，
 * 所以“哪些刀刃挂在这个刃槽上”只要按 {@code mountPos} 过滤即可，不用扫六个邻居。</p>
 *
 * <h2>外观</h2>
 * <p>渲染仍复用原来那三个方块模型（{@code origold_sawblade_wall / _floor / _ceil}），
 * 由 {@link OGESawbladeRenderer} 按实体同步过来的两个朝向算出一个方块状态画出来。
 * 方块注册 {@link ModBlocks#SAWBLADE} 现在只当“模型载体”，不会再被放进世界。</p>
 */
public final class OGESawbladeEntity extends Entity {

    /** 刀刃造成的伤害类型（死亡消息写“被圆锯撕碎”），对应 {@code damage_type/sawblade.json}。 */
    public static final ResourceKey<DamageType> SAWBLADE_DAMAGE =
            ResourceKey.create(Registries.DAMAGE_TYPE,
                    ResourceLocation.fromNamespaceAndPath(OriGoldsIndustrializedAge.MOD_ID, "sawblade"));

    private static final String TAG_MOUNT_POS = "MountPos";
    private static final String TAG_MOUNT_FACE = "MountFace";
    private static final String TAG_BLADE_FACING = "BladeFacing";

    /** 挂在刃槽的哪个面上（同步给客户端，决定用哪个模型）。 */
    private static final EntityDataAccessor<Direction> DATA_MOUNT_FACE =
            SynchedEntityData.defineId(OGESawbladeEntity.class, EntityDataSerializers.DIRECTION);

    /** 刀刃平面的水平朝向：只有刃槽顶面 / 底面的刀刃用得上（同步给客户端）。 */
    private static final EntityDataAccessor<Direction> DATA_BLADE_FACING =
            SynchedEntityData.defineId(OGESawbladeEntity.class, EntityDataSerializers.DIRECTION);

    /**
     * 是否在转（刃槽通电）：服务端每刻同步给客户端，决定贴图是轮换还是定格在第一帧。
     * 轮换节奏在客户端按刻数走，不需要逐帧同步帧号。
     */
    private static final EntityDataAccessor<Boolean> DATA_SPINNING =
            SynchedEntityData.defineId(OGESawbladeEntity.class, EntityDataSerializers.BOOLEAN);

    /** 出手周期（刻）：每 10 刻对它所在的那一整格打一次。 */
    public static final int ATTACK_INTERVAL = 10;

    /** 贴图一帧的持续刻数：sawblade → move1 → move2 每 1 刻换一张（较原先提速 5 倍）。 */
    public static final int FRAME_TICKS = 1;

    /** 附着的刃槽坐标；服务端拆装判定用，随 NBT 存盘。 */
    private BlockPos mountPos = BlockPos.ZERO;

    public OGESawbladeEntity(EntityType<? extends OGESawbladeEntity> type, Level level) {
        super(type, level);
        // 不受重力、免疫一切伤害：这两条加上下面的 isPickable=false，
        // 就是“不会被攻击、打不死”的全部实现。
        setNoGravity(true);
        setInvulnerable(true);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_MOUNT_FACE, Direction.UP);
        builder.define(DATA_BLADE_FACING, Direction.NORTH);
        builder.define(DATA_SPINNING, false);
    }

    public Direction getMountFace() {
        return entityData.get(DATA_MOUNT_FACE);
    }

    public Direction getBladeFacing() {
        return entityData.get(DATA_BLADE_FACING);
    }

    /** 刃槽通电时置真（同步给客户端做贴图动画）。 */
    public void setSpinning(boolean spinning) {
        if (entityData.get(DATA_SPINNING) != spinning) {
            entityData.set(DATA_SPINNING, spinning);
        }
    }

    /** 这片刀刃现在该不该转（客户端渲染用）。 */
    public boolean isSpinning() {
        return entityData.get(DATA_SPINNING);
    }

    /**
     * 客户端贴图帧号：静止时恒为 0（{@code sawblade}）；旋转时按世界刻数
     * 每 {@value #FRAME_TICKS} 刻在 0 → 1 → 2 之间轮换，三张图正好
     * sawblade / sawblade_move1 / sawblade_move2。
     */
    public int animationFrame() {
        if (!isSpinning()) {
            return 0;
        }
        return (int) ((level().getGameTime() / FRAME_TICKS + getId()) % 3);
    }

    public BlockPos getMountPos() {
        return mountPos;
    }

    /** 装上去的那一下把三样数据一次写好；之后整片刀刃不再变动。 */
    public void setMount(BlockPos mountPos, Direction face, Direction facing) {
        this.mountPos = mountPos.immutable();
        entityData.set(DATA_MOUNT_FACE, face);
        entityData.set(DATA_BLADE_FACING, facing);
    }

    /** 这片刀刃是不是挂在 {@code pos} 的刃槽上。 */
    public boolean isMountedOn(BlockPos pos) {
        return mountPos.equals(pos);
    }

    /**
     * 渲染用的方块状态：把两个朝向和当前贴图帧套回原刀刃方块，交给渲染器画模型。
     *
     * @param frame 0/1/2，对应 sawblade / sawblade_move1 / sawblade_move2
     *              （由 {@link #animationFrame()} 在客户端算出）
     */
    public BlockState getRenderState(int frame) {
        BlockState state = ModBlocks.SAWBLADE.get().defaultBlockState()
                .setValue(OGESawbladeBlock.MOUNT_FACE, getMountFace())
                .setValue(OGESawbladeBlock.FRAME, frame);
        if (getMountFace().getAxis() == Direction.Axis.Y) {
            state = state.setValue(OGESawbladeBlock.FACING, getBladeFacing());
        }
        return state;
    }

    /** 本刀刃依附的刃槽方块实体；刃槽被拆 / 区块未加载时为 {@code null}。 */
    public OGESawbladeMountBlockEntity mountBlockEntity() {
        if (level() == null || !level().isLoaded(mountPos)) {
            return null;
        }
        return level().getBlockEntity(mountPos) instanceof OGESawbladeMountBlockEntity mount ? mount : null;
    }

    /**
     * 把附着在 {@code mountPos} 上的刀刃实体都找出来。
     *
     * <p>{@code face} 给 {@code null} 表示不限面（拆刃槽时用），否则只找挂在那个面上的。
     * 刀刃永远待在刃槽的相邻格，所以查询范围取刃槽格向四周放宽 2 格就够。</p>
     */
    public static List<OGESawbladeEntity> findMountedOn(Level level, BlockPos mountPos, Direction face) {
        AABB box = new AABB(mountPos).inflate(2.0D);
        return level.getEntitiesOfClass(OGESawbladeEntity.class, box).stream()
                .filter(blade -> blade.isMountedOn(mountPos))
                .filter(blade -> face == null || blade.getMountFace() == face)
                .toList();
    }

    /** 移除前先掉 1 个铁块（就是原来刀刃方块战利品表的那份）。 */
    public void dropAndDiscard(ServerLevel level) {
        Block.popResource(level, blockPosition(), new ItemStack(Items.IRON_BLOCK));
        discard();
    }

    /** 一张贴片没有碰撞体积，也不该挡玩家走路。 */
    @Override
    public boolean isPickable() {
        return false;
    }

    /** 打不中也不该有反馈：任何伤害直接忽略。 */
    @Override
    public boolean hurt(DamageSource source, float amount) {
        return false;
    }

    @Override
    public boolean isInvulnerableTo(DamageSource source) {
        return true;
    }

    /** {@code /kill} 也杀不掉：想清理就拆对应的刃槽。 */
    @Override
    public void kill() {
        // 故意留空。
    }

    @Override
    public void tick() {
        super.tick();
        // 位置由挂载关系定死，任何外力都不该把它挪走。
        setDeltaMovement(Vec3.ZERO);
        if (level().isClientSide || !(level() instanceof ServerLevel serverLevel)) {
            return;
        }
        OGESawbladeMountBlockEntity mount = mountBlockEntity();
        // 转不转只看刃槽通不通电（与武器槽里有没有东西无关），断电时贴图回到第一帧。
        setSpinning(mount != null && mount.isBladePowered());
        if (mount == null) {
            return;
        }
        // 每 10 刻出手一次；按实体 id 错开相位，免得整排刀刃挤在同一刻查询实体。
        if (tickCount % ATTACK_INTERVAL != getId() % ATTACK_INTERVAL) {
            return;
        }
        double damage = mount.bladeDamagePerHit();
        if (damage <= 0.0D) {
            return;
        }
        // 伤害范围是刀刃所在的<b>一整格</b>：贴着刃槽站土径的生物、踩在矮草上的生物
        // 都在这一格里，全部会被锯到——不是按刀刃那张薄片的碰撞盒算的。
        // 任何活着的可伤害目标都算数，包括玩家。
        List<LivingEntity> targets = serverLevel.getEntitiesOfClass(LivingEntity.class,
                new AABB(blockPosition()),
                LivingEntity::isAlive);
        if (targets.isEmpty()) {
            return;
        }
        // 出手前先扣 1u OGE：扣不到（没电）就这一击整个作废，不造成伤害。
        if (!mount.payBladeAttack(serverLevel)) {
            return;
        }
        // 刃槽绑定了挂机志愿者时，伤害的"肇事者"记成该志愿者：
        // 生物的反击索敌（setLastHurtByMob 等原版链路）会因此对志愿者产生仇恨。
        OGEVolunteerWatcherEntity volunteer = mount.getBoundVolunteer();
        Holder<DamageType> sawbladeDamage = serverLevel.registryAccess()
                .registryOrThrow(Registries.DAMAGE_TYPE)
                .getHolderOrThrow(SAWBLADE_DAMAGE);
        DamageSource source = new DamageSource(sawbladeDamage, this, volunteer != null ? volunteer : this);
        // 玩家模式再备一份"被玩家击杀"的肇事者：志愿者的隐藏代理玩家。谁的<b>致命</b>一击记到
        // 代理玩家头上，原版的"被玩家击杀"链路——战利品条件 killed_by_player（苦力怕掉唱片这类）、
        // 幸运参与掷骰、击杀统计与死因归属——就与真人 / Carpet 假人击杀一致；非致命的命中仍记在
        // 志愿者本体上，原有反击仇恨链路分毫不动（死人不需要记仇）。仇恨目标模式没有代理，全按原样。
        ServerPlayer playerKiller = volunteer != null ? volunteer.playerModeKillProxy() : null;
        DamageSource lethalSource = playerKiller != null
                ? new DamageSource(sawbladeDamage, this, playerKiller)
                : null;
        int landed = 0;
        // 抢夺：原版按“击杀者实体主手上的抢夺等级”给掉落加成，而这里的击杀者是绑定的志愿者
        // （致命一击时是它的代理玩家），它手上原本什么都没有。所以伤害（含死亡掉落，全都在
        // hurt 里同步结算）期间把攻击器的副本借给它握着，结算完立刻换回原样——见
        // OGESawbladeMountBlockEntity#loanLootingWeapon。
        ItemStack lootingWeapon = volunteer != null ? mount.loanLootingWeapon() : ItemStack.EMPTY;
        for (LivingEntity target : targets) {
            // 血少到这一击该带走才算致命（护盾、不死图腾之类让它活下来的误判会由下一击纠正）。
            boolean lethal = lethalSource != null && target.getHealth() <= (float) damage;
            DamageSource hitSource = lethal ? lethalSource : source;
            LivingEntity killer = lethal ? playerKiller : volunteer;
            ItemStack handBefore = killer != null && !lootingWeapon.isEmpty()
                    ? lendMainHand(killer, lootingWeapon)
                    : null;
            try {
                // 无视目标无敌帧：出手前清零；打完后把目标原有的无敌帧原样还回去——
                // 刀刃的伤害既不享受也不施加无敌帧。
                int savedInvulnerable = target.invulnerableTime;
                target.invulnerableTime = 0;
                boolean hit = target.hurt(hitSource, (float) damage);
                target.invulnerableTime = savedInvulnerable;
                if (hit) {
                    landed++;
                }
            } finally {
                if (handBefore != null) {
                    lendMainHand(killer, handBefore);
                }
            }
        }
        if (landed > 0) {
            // 命中过目标：给刃槽的共享计数加一次（到阈值由刃槽扣武器耐久）。
            // OGE 已在出手前扣掉，这里不再重复付费。
            mount.onBladeHits();
            if (volunteer != null) {
                // 玩家模式的志愿者只有在这个窗口里才允许生物产生反击仇恨。
                volunteer.noteBladeRetaliation();
            }
        }
    }

    /**
     * 把 {@code holder} 的主手临时换成 {@code weapon}，返回换下来的原主手物品
     * （再调一次传回去即可还原）。志愿者本体（Mob）与其代理玩家（Player）都走这里：
     * 前者经 {@code LivingEntity#setItemInHand}（志愿者已覆写 doesEmitEquipEvent，
     * 换手不发监守者听得见的振动）；后者经 {@code Player#setItemInHand}，只写自己的
     * 物品栏，本来就无人可广播。整个借还都在同一刻的伤害结算里同步完成，不会同步到客户端。
     */
    static ItemStack lendMainHand(LivingEntity holder, ItemStack weapon) {
        ItemStack previous = holder.getMainHandItem();
        holder.setItemInHand(InteractionHand.MAIN_HAND, weapon);
        return previous;
    }

    /** 生成包到达时把“上一 tick 位置”同步成当前值，免得第一帧从世界原点插值飞过来。 */
    @Override
    public void recreateFromPacket(ClientboundAddEntityPacket packet) {
        super.recreateFromPacket(packet);
        setOldPosAndRot();
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putLong(TAG_MOUNT_POS, mountPos.asLong());
        tag.putInt(TAG_MOUNT_FACE, getMountFace().get3DDataValue());
        tag.putInt(TAG_BLADE_FACING, getBladeFacing().get3DDataValue());
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        mountPos = BlockPos.of(tag.getLong(TAG_MOUNT_POS));
        entityData.set(DATA_MOUNT_FACE, Direction.from3DDataValue(tag.getInt(TAG_MOUNT_FACE)));
        entityData.set(DATA_BLADE_FACING, Direction.from3DDataValue(tag.getInt(TAG_BLADE_FACING)));
    }
}
