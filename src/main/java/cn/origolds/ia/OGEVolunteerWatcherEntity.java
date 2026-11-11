package cn.origolds.ia;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

import com.mojang.authlib.GameProfile;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** 可切换“玩家模式 / 仇恨目标模式”的挂机志愿者。 */
public final class OGEVolunteerWatcherEntity extends Mob {

    public static final int PLAYER_MODE = 0;
    public static final int MOB_TARGET_MODE = 1;
    private static final int TARGET_REFRESH_TICKS = 20;
    private static final double MAX_TARGET_SCAN_RANGE = 128.0D;
    private static final String TAG_MODE = "VolunteerMode";
    private static final String TAG_BINDS = "SawbladeBinds";

    /** 玩家模式下，绑定刀刃打中生物后仇恨窗口持续的刻数（窗口内允许反击索敌）。 */
    private static final int RETALIATION_WINDOW_TICKS = 100;
    /** 只要还有生物把志愿者当目标，窗口至少续到这里，免得反击仇恨中途被判不可攻击而清空。 */
    private static final int RETALIATION_KEEP_ALIVE = 60;

    private static final EntityDataAccessor<Integer> DATA_MODE =
            SynchedEntityData.defineId(OGEVolunteerWatcherEntity.class, EntityDataSerializers.INT);

    private OGEVolunteerSpawnPlayer spawnPlayer;

    /** 绑定的金源圆锯刃槽（服务端为权威；顺序即配置界面里的顺序）。 */
    private final List<OGEVolunteerBindEntry> bladeBinds = new ArrayList<>();
    /** 玩家模式仇恨窗口剩余刻数：绑定刀刃打中生物时重置为满，每 20 刻递减一次。 */
    private int bladeRetaliationTicks;

    /** 每 20 刻把绑定与刃槽方块实体里的反向引用对账一次。 */
    private int bindReconcileCountdown = 1;


    public OGEVolunteerWatcherEntity(EntityType<? extends OGEVolunteerWatcherEntity> type, Level level) {
        super(type, level);
        setNoAi(true);
        setPersistenceRequired();
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.0D)
                .add(Attributes.KNOCKBACK_RESISTANCE, 1.0D);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_MODE, PLAYER_MODE);
    }

    public int getMode() {
        return entityData.get(DATA_MODE) == MOB_TARGET_MODE ? MOB_TARGET_MODE : PLAYER_MODE;
    }

    public void setMode(int mode) {
        int clamped = mode == MOB_TARGET_MODE ? MOB_TARGET_MODE : PLAYER_MODE;
        if (getMode() == clamped) {
            return;
        }
        entityData.set(DATA_MODE, clamped);
        if (clamped == MOB_TARGET_MODE) {
            removeSpawnPlayer();
        } else {
            // 切回玩家模式时立刻解除仇恨，否则生物会继续追着已经变回玩家模式的志愿者打。
            releaseMobAggro();
        }
    }

    /**
     * 玩家模式下默认不作为任何生物的仇恨目标；唯一的例外是绑定刀刃最近打到了生物
     * （反击索敌意向及其衍生仇恨），此时开放一个短暂的窗口让原版反击 AI 能把
     * 志愿者设为目标。见 {@link #noteBladeRetaliation()}。
     */
    @Override
    public boolean canBeSeenAsEnemy() {
        return getMode() == MOB_TARGET_MODE || bladeRetaliationTicks > 0;
    }

    /** 绑定本志愿者的刀刃打到了生物：重开玩家模式仇恨窗口。 */
    public void noteBladeRetaliation() {
        bladeRetaliationTicks = RETALIATION_WINDOW_TICKS;
    }

    /**
     * 刃槽绑定本志愿者、且处于<b>玩家模式</b>时，致命一击该记名的“击杀者”：
     * 站在志愿者脚下、对客户端隐藏的代理玩家 {@link OGEVolunteerSpawnPlayer}。
     *
     * <p>原版“被玩家击杀”的判定——战利品条件 {@code killed_by_player}（苦力怕掉唱片这类）、
     * 幸运参与掉落掷骰、击杀统计与“被谁杀死”的归属——看的都是伤害来源的肇事者是不是
     * {@code Player}。把击杀记到代理玩家头上，掉落就与真人（或 Carpet 假人）击杀一致；
     * 抢夺同样按这名“玩家”的主手读取，所以攻击器的副本借给它握（见
     * {@link OGESawbladeEntity#lendMainHand}）。代理不可见也不广播，视觉上不会多出一个人。</p>
     *
     * <p>非玩家模式（仇恨目标模式）与客户端侧返回 {@code null}，调用方退回用志愿者本体当
     * 击杀者；玩家模式下代理还没建好的话在这里顺手补建一次。</p>
     */
    public ServerPlayer playerModeKillProxy() {
        if (level().isClientSide || getMode() != PLAYER_MODE) {
            return null;
        }
        if (spawnPlayer == null || spawnPlayer.isRemoved()) {
            ensureSpawnPlayer();
        }
        return spawnPlayer != null && !spawnPlayer.isRemoved() ? spawnPlayer : null;
    }

    /**
     * 代拿攻击器只是给抢夺判定“看一眼”，不是真的装备东西：
     * 原版换装会发 {@code unequip} 游戏事件，那是监守者听得见的振动，
     * 刀刃每 10 刻出手一次会一直在这儿敲。与盔甲架同一做法，一律不发装备事件。
     */
    @Override
    protected boolean doesEmitEquipEvent(EquipmentSlot slot) {
        return false;
    }

    public void cycleMode(Player player) {
        setMode(getMode() == PLAYER_MODE ? MOB_TARGET_MODE : PLAYER_MODE);
        player.displayClientMessage(Component.translatable(
                "message.origolds_ia.volunteer_watcher_mode",
                Component.translatable(getMode() == PLAYER_MODE
                        ? "mode.origolds_ia.volunteer_watcher.player"
                        : "mode.origolds_ia.volunteer_watcher.mob_target")), true);
    }

    @Override
    public void tick() {
        super.tick();
        setDeltaMovement(Vec3.ZERO);
        setHealth(getMaxHealth());

        // 朝向只在放置时确定一次（与盔甲架相同），之后不再追踪任何玩家或生物。
        setYBodyRot(getYRot());
        setYHeadRot(getYRot());

        if (!level().isClientSide && tickCount % TARGET_REFRESH_TICKS == 0) {
            if (getMode() == PLAYER_MODE) {
                ensureSpawnPlayer();
                // 玩家模式不再兜底清仇恨：绑定刀刃打到生物产生的反击仇恨要能留住，
                // 只有切回玩家模式那一下（setMode）与收回/移除时才清除。
            } else {
                removeSpawnPlayer();
                attractNearbyMobs();
            }
        }
        if (!level().isClientSide) {
            // 仇恨窗口：绑定刀刃每打中一次生物重置为满；窗口内还有生物把志愿者
            // 当目标时续期（每 20 刻检查一次），否则随窗口自然到期关闭。
            if (tickCount % TARGET_REFRESH_TICKS == 0) {
                if (bladeRetaliationTicks > 0) {
                    bladeRetaliationTicks -= TARGET_REFRESH_TICKS;
                }
                if (bladeRetaliationTicks < RETALIATION_KEEP_ALIVE && hasMobTargetingMe()) {
                    bladeRetaliationTicks = RETALIATION_KEEP_ALIVE;
                }
            }
            if (--bindReconcileCountdown <= 0) {
                bindReconcileCountdown = TARGET_REFRESH_TICKS;
                reconcileBladeBinds();
            }
        }
    }

    /** 周围是否还有生物正把志愿者当目标（仇恨保留期内）。 */
    private boolean hasMobTargetingMe() {
        AABB scanBox = getBoundingBox().inflate(MAX_TARGET_SCAN_RANGE);
        for (Mob mob : level().getEntitiesOfClass(Mob.class, scanBox, this::isAttractableMob)) {
            if (mob.getTarget() == this) {
                return true;
            }
        }
        return false;
    }

    /** 每秒只扫描一次；仅已加载范围内且志愿者处在其 FOLLOW_RANGE 内的生物会被改目标。 */
    private void attractNearbyMobs() {
        if (getMode() == PLAYER_MODE) {
            return;
        }
        AABB scanBox = getBoundingBox().inflate(MAX_TARGET_SCAN_RANGE);
        for (Mob mob : level().getEntitiesOfClass(Mob.class, scanBox, this::isAttractableMob)) {
            double followRange = mob.getAttribute(Attributes.FOLLOW_RANGE) == null
                    ? 32.0D : mob.getAttributeValue(Attributes.FOLLOW_RANGE);
            if (mob.distanceToSqr(this) > followRange * followRange) {
                continue;
            }
            mob.setTarget(this);
            if (mob instanceof NeutralMob neutralMob) {
                neutralMob.setPersistentAngerTarget(getUUID());
                neutralMob.startPersistentAngerTimer();
            }
            if (mob.getBrain().getMemories().containsKey(MemoryModuleType.ATTACK_TARGET)) {
                mob.getBrain().setMemory(MemoryModuleType.ATTACK_TARGET, this);
            }
        }
    }

    private boolean isAttractableMob(Mob mob) {
        return mob != this && mob.isAlive() && (mob instanceof Enemy || mob instanceof NeutralMob);
    }

    /**
     * 清除周围生物对志愿者的仇恨：目标、中立生物的持久怒气、以及大脑里的 ATTACK_TARGET/ANGRY_AT。
     * 玩家模式切换、收回以及区块卸载时调用，避免生物对着已失效的仇恨目标继续攻击。
     */
    private void releaseMobAggro() {
        Level currentLevel = level();
        if (!(currentLevel instanceof ServerLevel serverLevel) || serverLevel.isClientSide) {
            return;
        }
        UUID selfId = getUUID();
        AABB scanBox = getBoundingBox().inflate(MAX_TARGET_SCAN_RANGE);
        for (Mob mob : serverLevel.getEntitiesOfClass(Mob.class, scanBox, this::isAttractableMob)) {
            if (mob.getTarget() == this) {
                mob.setTarget(null);
            }
            if (mob instanceof NeutralMob neutralMob
                    && selfId.equals(neutralMob.getPersistentAngerTarget())) {
                neutralMob.setPersistentAngerTarget(null);
                neutralMob.setRemainingPersistentAngerTime(0);
            }
            if (mob.getBrain().isMemoryValue(MemoryModuleType.ATTACK_TARGET, this)) {
                mob.getBrain().eraseMemory(MemoryModuleType.ATTACK_TARGET);
            }
            if (mob.getBrain().isMemoryValue(MemoryModuleType.ANGRY_AT, selfId)) {
                mob.getBrain().eraseMemory(MemoryModuleType.ANGRY_AT);
            }
        }
    }

    /**
     * 玩家模式使用真正进入原版玩家距离系统的隐藏创造代理，因此区块加载、模拟距离、
     * 自然生成距离和局部生物上限都直接沿用原版玩家规则，而不是另写一套近似逻辑。
     */
    private void ensureSpawnPlayer() {
        if (!(level() instanceof ServerLevel serverLevel)) {
            return;
        }
        if (spawnPlayer == null || spawnPlayer.isRemoved()) {
            UUID proxyId = UUID.nameUUIDFromBytes(("origolds_ia:volunteer_watcher:" + getUUID())
                    .getBytes(StandardCharsets.UTF_8));
            String name = "OGEWatch" + getStringUUID().substring(0, 8);
            spawnPlayer = new OGEVolunteerSpawnPlayer(serverLevel, new GameProfile(proxyId, name));
            spawnPlayer.moveTo(getX(), getY(), getZ(), getYRot(), 0.0F);
            serverLevel.addNewPlayer(spawnPlayer);
        } else {
            spawnPlayer.moveTo(getX(), getY(), getZ(), getYRot(), 0.0F);
            serverLevel.getChunkSource().move(spawnPlayer);
        }
    }

    private void removeSpawnPlayer() {
        if (spawnPlayer != null && !spawnPlayer.isRemoved() && level() instanceof ServerLevel serverLevel) {
            serverLevel.removePlayerImmediately(spawnPlayer, RemovalReason.DISCARDED);
        }
        spawnPlayer = null;
    }

    @Override
    public boolean skipAttackInteraction(Entity attacker) {
        if (attacker instanceof Player player && player.isShiftKeyDown()) {
            if (!level().isClientSide) {
                cycleMode(player);
            }
            return true;
        }
        return true;
    }

    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (player.isSecondaryUseActive()) {
            // Shift+右键：收回。模式、绑定表与命名牌名字随物品走，其余状态（朝向等）丢弃。
            if (!level().isClientSide && !isRemoved()) {
                ItemStack returned = new ItemStack(ModBlocks.VOLUNTEER_WATCHER.get());
                copyStateToItem(returned);
                if (!player.getInventory().add(returned)) {
                    spawnAtLocation(returned);
                }
                level().playSound(null, getX(), getY(), getZ(), SoundEvents.ARMOR_STAND_BREAK,
                        SoundSource.NEUTRAL, 1.0F, 1.0F);
                removeSpawnPlayer();
                discard();
            }
            return InteractionResult.sidedSuccess(level().isClientSide);
        }
        // 普通右键：打开绑定配置界面。
        if (!level().isClientSide && !isRemoved() && player instanceof ServerPlayer serverPlayer) {
            OGEVolunteerBindManager.openConfig(serverPlayer, getId());
        }
        return InteractionResult.sidedSuccess(level().isClientSide);
    }

    /**
     * 收回时写进物品：模式 + 绑定表 + （若有）命名牌名字。
     *
     * <p><b>只写与默认值不同的东西</b>：玩家模式（默认）、没绑定、没改名时一个组件都不写，
     * 收回来的物品与新合成的完全相同，能正常堆叠。只要写了组件就会让它自成一堆，
     * 所以这里对"有效信息"的判定要严格：</p>
     * <ul>
     *   <li>模式：只有仇恨目标模式算有效信息，玩家模式不写；</li>
     *   <li>绑定表：空表不写。表里只有坐标、没有名字（名字存在刃槽那边，见
     *       {@link OGEVolunteerBindEntry}），所以绑了同一批刃槽、顺序也一致的两个志愿者
     *       收回后组件完全相同，照样能堆一起；</li>
     *   <li>名字：只有真正被命名过才写。</li>
     * </ul>
     */
    public void copyStateToItem(ItemStack stack) {
        if (getMode() != PLAYER_MODE) {
            stack.set(ModDataComponents.VOLUNTEER_MODE.get(), getMode());
        }
        if (hasCustomName()) {
            stack.set(DataComponents.CUSTOM_NAME, getCustomName());
        }
        if (!bladeBinds.isEmpty()) {
            stack.set(ModDataComponents.VOLUNTEER_BINDS.get(), List.copyOf(bladeBinds));
        }
    }

    /** 放置时从物品还原绑定表（模式与名字由原版放置流程与物品调用方处理）。 */
    public void restoreBindsFromItem(ItemStack stack) {
        List<OGEVolunteerBindEntry> saved = stack.get(ModDataComponents.VOLUNTEER_BINDS.get());
        if (saved == null || saved.isEmpty()) {
            return;
        }
        bladeBinds.clear();
        for (OGEVolunteerBindEntry entry : saved) {
            if (!hasBind(entry.pos())) {
                bladeBinds.add(entry);
            }
        }
        adoptLegacyBindNames();
        onBindsChanged();
    }

    /**
     * 旧版本把刃槽显示名各存一份在绑定条目里；现在名字唯一存在刃槽方块实体那边，
     * 读档、放置还原与每 20 刻对账时顺手把没搬过的历史名字搬过去：
     * 刃槽自己还没有名字就用它，已有名字（或那儿根本不是刃槽）就丢掉这条冗余。
     * 刃槽所在区块当时没加载的先留着，下一轮对账再试，避免名字被白白吞掉。
     */
    private void adoptLegacyBindNames() {
        if (level().isClientSide) {
            return;
        }
        for (int i = 0; i < bladeBinds.size(); i++) {
            OGEVolunteerBindEntry entry = bladeBinds.get(i);
            if (!entry.hasLegacyName() || !level().isLoaded(entry.pos())) {
                continue;
            }
            if (level().getBlockEntity(entry.pos()) instanceof OGESawbladeMountBlockEntity mount
                    && mount.getVolunteerBindName().isEmpty()) {
                mount.setVolunteerBindName(entry.legacyName());
            }
            bladeBinds.set(i, new OGEVolunteerBindEntry(entry.pos()));
        }
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        return false;
    }

    @Override
    public boolean isInvulnerableTo(DamageSource source) {
        return true;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public void push(Entity entity) {
    }

    @Override
    public void push(Vec3 movement) {
    }

    @Override
    public void push(double x, double y, double z) {
    }

    // ------------------------------------------------------------------
    // 圆锯刃槽绑定（服务端权威，数据存在志愿者身上）
    // ------------------------------------------------------------------

    /** 当前绑定列表（只读视图，顺序即展示顺序）。 */
    public List<OGEVolunteerBindEntry> bladeBinds() {
        return List.copyOf(bladeBinds);
    }

    /** 是否已绑定 {@code pos} 处的刃槽。 */
    public boolean hasBind(BlockPos pos) {
        for (OGEVolunteerBindEntry entry : bladeBinds) {
            if (entry.pos().equals(pos)) {
                return true;
            }
        }
        return false;
    }

    /** 新增一条绑定（已存在则不动）；返回是否真的加了。 */
    public boolean addBind(BlockPos pos) {
        if (hasBind(pos)) {
            return false;
        }
        bladeBinds.add(new OGEVolunteerBindEntry(pos));
        onBindsChanged();
        return true;
    }

    /** 解绑一个刃槽；顺带清掉刃槽那边的反向引用。 */
    public boolean removeBind(BlockPos pos) {
        boolean removed = bladeBinds.removeIf(entry -> entry.pos().equals(pos));
        if (removed) {
            clearMountBackReference(pos);
            onBindsChanged();
        }
        return removed;
    }

    /** 某条绑定当前生效的显示名：唯一来源是刃槽方块实体；刃槽不在、没加载或没改过名就是空串。 */
    public String bindDisplayName(BlockPos pos) {
        return level().isLoaded(pos)
                && level().getBlockEntity(pos) instanceof OGESawbladeMountBlockEntity mount
                ? mount.getVolunteerBindName() : "";
    }

    /**
     * 改一条绑定的显示名（空串表示回到默认名）。
     *
     * <p>名字<b>不记在志愿者身上</b>，而是写进刃槽方块实体那唯一一份：所有绑定了这个刃槽的
     * 志愿者共用它，在任何一边的界面里改名都会同步到其它边，两个"绑了同一批刃槽、其余数据
     * 一致"的志愿者收回后组件也才能完全相同并堆叠（见 {@link OGEVolunteerBindEntry}）。</p>
     *
     * @return 这条绑定确实属于本志愿者、且刃槽方块实体可写时返回 {@code true}；否则不改任何东西。
     */
    public boolean renameBind(BlockPos pos, String name) {
        if (!hasBind(pos)) {
            return false;
        }
        if (!level().isLoaded(pos)
                || !(level().getBlockEntity(pos) instanceof OGESawbladeMountBlockEntity mount)) {
            return false;
        }
        mount.setVolunteerBindName(name == null ? "" : name);
        return true;
    }

    /**
     * 按给定坐标顺序重排绑定；不在列表里的（服务端这边还有但界面没发来的）保持原序接在后面。
     */
    public void reorderBinds(List<BlockPos> order) {
        List<OGEVolunteerBindEntry> sorted = new ArrayList<>(bladeBinds.size());
        for (BlockPos pos : order) {
            for (Iterator<OGEVolunteerBindEntry> it = bladeBinds.iterator(); it.hasNext(); ) {
                OGEVolunteerBindEntry entry = it.next();
                if (entry.pos().equals(pos)) {
                    sorted.add(entry);
                    it.remove();
                    break;
                }
            }
        }
        sorted.addAll(bladeBinds);
        bladeBinds.clear();
        bladeBinds.addAll(sorted);
        onBindsChanged();
    }

    /** 收回 / 移除时调用：让还活着的刃槽把自己从反向引用里摘掉。 */
    private void dropAllMountBackReferences() {
        for (OGEVolunteerBindEntry entry : bladeBinds) {
            clearMountBackReference(entry.pos());
        }
    }

    private void clearMountBackReference(BlockPos pos) {
        if (level().isLoaded(pos)
                && level().getBlockEntity(pos) instanceof OGESawbladeMountBlockEntity mount
                && getUUID().equals(mount.getBoundVolunteerId())) {
            mount.setBoundVolunteer(null);
            mount.setChanged();
        }
    }

    /**
     * 每 20 刻对账一次：刃槽方块实体加载进来后补上反向引用；刃槽没了或被别的
     * 志愿者抢绑了就删掉这条绑定（反向引用以最后一次成功绑定为准）。
     */
    private void reconcileBladeBinds() {
        // 顺带补搬：读档时刃槽区块可能还没加载，那次的历史名字先留着，到这里再试一次。
        adoptLegacyBindNames();
        if (bladeBinds.isEmpty()) {
            return;
        }
        boolean changed = false;
        for (Iterator<OGEVolunteerBindEntry> it = bladeBinds.iterator(); it.hasNext(); ) {
            BlockPos pos = it.next().pos();
            if (!level().isLoaded(pos)) {
                continue;
            }
            if (!(level().getBlockEntity(pos) instanceof OGESawbladeMountBlockEntity mount)) {
                it.remove();
                changed = true;
                continue;
            }
            if (mount.getBoundVolunteerId() == null) {
                mount.setBoundVolunteer(getUUID());
                mount.setChanged();
            } else if (!getUUID().equals(mount.getBoundVolunteerId())) {
                it.remove();
                changed = true;
            }
        }
        if (changed) {
            onBindsChanged();
        }
    }

    private void onBindsChanged() {
        // 实体 NBT 随区块存档自动写回（addAdditionalSaveData），无需手动标脏。
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putInt(TAG_MODE, getMode());
        ListTag binds = new ListTag();
        for (OGEVolunteerBindEntry entry : bladeBinds) {
            binds.add(entry.toTag());
        }
        tag.put(TAG_BINDS, binds);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        setMode(tag.getInt(TAG_MODE));
        setHealth(getMaxHealth());
        bladeBinds.clear();
        if (tag.contains(TAG_BINDS, Tag.TAG_LIST)) {
            for (Tag element : tag.getList(TAG_BINDS, Tag.TAG_COMPOUND)) {
                if (element instanceof CompoundTag entryTag) {
                    OGEVolunteerBindEntry entry = OGEVolunteerBindEntry.fromTag(entryTag);
                    if (!hasBind(entry.pos())) {
                        bladeBinds.add(entry);
                    }
                }
            }
        }
        // 旧存档里的绑定自带显示名：搬进刃槽那唯一一份，之后条目只剩坐标。
        adoptLegacyBindNames();
    }

    @Override
    public void onRemovedFromLevel() {
        releaseMobAggro();
        removeSpawnPlayer();
        dropAllMountBackReferences();
        super.onRemovedFromLevel();
    }

    @Override
    public ItemStack getPickResult() {
        // 同样不携带数据组件，避免创造模式选取出的物品无法与普通物品堆叠。
        return new ItemStack(ModBlocks.VOLUNTEER_WATCHER.get());
    }
}
