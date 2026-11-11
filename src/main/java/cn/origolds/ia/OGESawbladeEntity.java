package cn.origolds.ia;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 钉在锯片台面上的旋转圆锯片实体。
 *
 * <h2>存在形态</h2>
 * <p>无重力、不可被伤害（{@link #hurtServer} 恒为 false + 构造里 setInvulnerable），
 * 也不会被 {@link #kill(ServerLevel)} 干掉——原版语义就是"机构件，不是生物"。
 * 位置由 {@link #setMount(BlockPos, Direction, Direction)} 固定到锯片台某面相邻的格心，
 * 朝向经 {@code SynchedEntityData} 同步（台面方位 mountFace、刀片朝向 bladeFacing、
 * 是否在转 spinning）。</p>
 *
 * <h2>攻击</h2>
 * <p>服务端每 {@value #ATTACK_INTERVAL} 刻（按实体 id 错开）扫一次所在格：
 * 台面后的锯片台必须绑定志愿者才有伤害（{@code bladeDamagePerHit()}），
 * 每轮先付 1 点 OGE（{@code payBladeAttack}），再对格内所有存活生物临时清零
 * {@code invulnerableTime} 后打一下（{@code hurtOrSimulate}），有命中则
 * 计一次磨损并让志愿者记录报复目标。</p>
 *
 * <h2>动画帧</h2>
 * <p>客户端渲染每刻查 {@link #animationFrame()}：静止恒 0 帧；转动时按
 * {@code (游戏刻 / FRAME_TICKS + 实体id) % 3} 在三帧方块模型间循环（实体 id 错相位，
 * 多片锯片不会整齐地一起闪）。渲染见 {@link OGESawbladeRenderer}。</p>
 *
 * <p>26.x 变更：伤害覆写从 {@code hurt} 改为抽象的 {@code hurtServer(ServerLevel, ...)}；
 * 目标结算走 {@code hurtOrSimulate}；存档走 {@link ValueOutput}/{@link ValueInput}。</p>
 */
public final class OGESawbladeEntity extends Entity {

    /** 伤害类型键：data/origolds_ia/damage_type/sawblade.json。 */
    public static final ResourceKey<DamageType> SAWBLADE_DAMAGE = ResourceKey.create(
            Registries.DAMAGE_TYPE, Identifier.fromNamespaceAndPath(OriGoldsIndustrializedAge.MOD_ID, "sawblade"));
    private static final String TAG_MOUNT_POS = "MountPos";
    private static final String TAG_MOUNT_FACE = "MountFace";
    private static final String TAG_BLADE_FACING = "BladeFacing";
    private static final EntityDataAccessor<Direction> DATA_MOUNT_FACE =
            SynchedEntityData.defineId(OGESawbladeEntity.class, EntityDataSerializers.DIRECTION);
    private static final EntityDataAccessor<Direction> DATA_BLADE_FACING =
            SynchedEntityData.defineId(OGESawbladeEntity.class, EntityDataSerializers.DIRECTION);
    private static final EntityDataAccessor<Boolean> DATA_SPINNING =
            SynchedEntityData.defineId(OGESawbladeEntity.class, EntityDataSerializers.BOOLEAN);
    /** 攻击扫描间隔（刻），按实体 id 错开。 */
    public static final int ATTACK_INTERVAL = 10;
    /** 动画每帧持续多少刻。 */
    public static final int FRAME_TICKS = 1;

    private BlockPos mountPos = BlockPos.ZERO;

    public OGESawbladeEntity(EntityType<? extends OGESawbladeEntity> type, Level level) {
        super(type, level);
        this.setNoGravity(true);
        this.setPermanentlyInvulnerable(true);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_MOUNT_FACE, Direction.UP);
        builder.define(DATA_BLADE_FACING, Direction.NORTH);
        builder.define(DATA_SPINNING, false);
    }

    public Direction getMountFace() {
        return this.entityData.get(DATA_MOUNT_FACE);
    }

    public Direction getBladeFacing() {
        return this.entityData.get(DATA_BLADE_FACING);
    }

    public void setSpinning(boolean spinning) {
        if (this.entityData.get(DATA_SPINNING) != spinning) {
            this.entityData.set(DATA_SPINNING, spinning);
        }
    }

    public boolean isSpinning() {
        return this.entityData.get(DATA_SPINNING);
    }

    /** 当前动画帧（0..2）：静止恒 0；转动按游戏刻循环，实体 id 错相位。 */
    public int animationFrame() {
        return !this.isSpinning() ? 0 : (int) ((this.level().getGameTime() / FRAME_TICKS + this.getId()) % 3L);
    }

    public BlockPos getMountPos() {
        return this.mountPos;
    }

    public void setMount(BlockPos mountPos, Direction face, Direction facing) {
        this.mountPos = mountPos.immutable();
        this.entityData.set(DATA_MOUNT_FACE, face);
        this.entityData.set(DATA_BLADE_FACING, facing);
    }

    public boolean isMountedOn(BlockPos pos) {
        return this.mountPos.equals(pos);
    }

    /** 取某一帧要渲染的锯片方块状态（台面方位 + 帧号；Y 面还要刀片朝向）。 */
    public BlockState getRenderState(int frame) {
        BlockState state = ModBlocks.SAWBLADE.get()
                .defaultBlockState()
                .setValue(OGESawbladeBlock.MOUNT_FACE, this.getMountFace())
                .setValue(OGESawbladeBlock.FRAME, frame);
        if (this.getMountFace().getAxis() == Axis.Y) {
            state = state.setValue(OGESawbladeBlock.FACING, this.getBladeFacing());
        }
        return state;
    }

    /** 台面后的锯片台方块实体（未加载/不是它则 null）。 */
    public OGESawbladeMountBlockEntity mountBlockEntity() {
        if (this.level() != null && this.level().isLoaded(this.mountPos)) {
            return this.level().getBlockEntity(this.mountPos) instanceof OGESawbladeMountBlockEntity mount
                    ? mount : null;
        }
        return null;
    }

    /** 找出钉在指定台面、可选指定朝向的全部锯片实体（粗略 AABB 预筛）。 */
    public static List<OGESawbladeEntity> findMountedOn(Level level, BlockPos mountPos, Direction face) {
        AABB box = new AABB(mountPos).inflate(2.0);
        return level.getEntitiesOfClass(OGESawbladeEntity.class, box)
                .stream()
                .filter(blade -> blade.isMountedOn(mountPos))
                .filter(blade -> face == null || blade.getMountFace() == face)
                .toList();
    }

    /** 拆除回收：按原版语义掉一块铁锭后消失（与反编译一致）。 */
    public void dropAndDiscard(ServerLevel level) {
        Block.popResource(level, this.blockPosition(), new ItemStack(Items.IRON_BLOCK));
        this.discard();
    }

    @Override
    public boolean isPickable() {
        return false;
    }

    @Override
    public boolean hurtServer(ServerLevel serverLevel, DamageSource source, float amount) {
        return false;
    }

    @Override
    public void kill(ServerLevel serverLevel) {
        // 机构件，不可被“杀死”——保持反编译的空实现语义。
    }

    @Override
    public void tick() {
        super.tick();
        this.setDeltaMovement(Vec3.ZERO);
        if (!this.level().isClientSide() && this.level() instanceof ServerLevel serverLevel) {
            OGESawbladeMountBlockEntity mount = this.mountBlockEntity();
            this.setSpinning(mount != null && mount.isBladePowered());
            if (mount != null) {
                if (this.tickCount % ATTACK_INTERVAL == this.getId() % ATTACK_INTERVAL) {
                    double damage = mount.bladeDamagePerHit();
                    if (!(damage <= 0.0)) {
                        List<LivingEntity> targets = serverLevel.getEntitiesOfClass(LivingEntity.class,
                                new AABB(this.blockPosition()), LivingEntity::isAlive);
                        if (!targets.isEmpty()) {
                            if (mount.payBladeAttack(serverLevel)) {
                                OGEVolunteerWatcherEntity volunteer = mount.getBoundVolunteer();
                                var damageType = serverLevel.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE)
                                        .getOrThrow(SAWBLADE_DAMAGE);
                                // 普通命中仍归志愿者本体，保留生物反击志愿者的仇恨行为；
                                // 玩家模式的致命命中则归隐藏代理玩家，恢复原版玩家击杀判定。
                                DamageSource source = new DamageSource(damageType, this,
                                        volunteer != null ? volunteer : this);
                                ServerPlayer playerKiller = volunteer != null
                                        ? volunteer.playerModeKillProxy() : null;
                                DamageSource lethalSource = playerKiller != null
                                        ? new DamageSource(damageType, this, playerKiller) : null;
                                ItemStack lootingWeapon = volunteer != null
                                        ? mount.loanLootingWeapon() : ItemStack.EMPTY;
                                int landed = 0;
                                for (LivingEntity target : targets) {
                                    boolean lethal = lethalSource != null && target.getHealth() <= (float) damage;
                                    DamageSource hitSource = lethal ? lethalSource : source;
                                    LivingEntity killer = lethal ? playerKiller : volunteer;
                                    ItemStack handBefore = killer != null && !lootingWeapon.isEmpty()
                                            ? lendMainHand(killer, lootingWeapon) : null;
                                    try {
                                        int savedInvulnerable = target.getInvulnerableTime();
                                        boolean hit;
                                        try {
                                            // 26.3 起 invulnerableTime 私有化，改用存取器临时清零受击无敌帧。
                                            target.setInvulnerableTime(0);
                                            hit = target.hurtOrSimulate(hitSource, (float) damage);
                                        } finally {
                                            target.setInvulnerableTime(savedInvulnerable);
                                        }
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
                                    mount.onBladeHits();
                                    if (volunteer != null) {
                                        volunteer.noteBladeRetaliation();
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /** 临时借出主手物品并返回原物品；调用方必须在 finally 中换回。 */
    private static ItemStack lendMainHand(LivingEntity holder, ItemStack weapon) {
        ItemStack previous = holder.getMainHandItem();
        holder.setItemInHand(InteractionHand.MAIN_HAND, weapon);
        return previous;
    }

    @Override
    public void recreateFromPacket(ClientboundAddEntityPacket packet) {
        super.recreateFromPacket(packet);
        this.setOldPosAndRot();
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        output.putLong(TAG_MOUNT_POS, this.mountPos.asLong());
        output.putInt(TAG_MOUNT_FACE, this.getMountFace().get3DDataValue());
        output.putInt(TAG_BLADE_FACING, this.getBladeFacing().get3DDataValue());
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        this.mountPos = BlockPos.of(input.getLongOr(TAG_MOUNT_POS, 0L));
        this.entityData.set(DATA_MOUNT_FACE, Direction.from3DDataValue(input.getIntOr(TAG_MOUNT_FACE, Direction.UP.get3DDataValue())));
        this.entityData.set(DATA_BLADE_FACING, Direction.from3DDataValue(input.getIntOr(TAG_BLADE_FACING, Direction.NORTH.get3DDataValue())));
    }
}
