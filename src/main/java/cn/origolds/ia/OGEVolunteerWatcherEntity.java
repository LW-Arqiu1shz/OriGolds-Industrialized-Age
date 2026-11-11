package cn.origolds.ia;

import com.mojang.authlib.GameProfile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.TriState;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.Entity.RemovalReason;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 志愿者观察员实体：站桩不动的“监工小人”，两种模式（同步数据共享）：
 * <ul>
 *   <li>玩家模式（0）——身旁挂一个假玩家投影（{@link OGEVolunteerSpawnPlayer}），
 *       用玩家皮肤看着机器；</li>
 *   <li>怪物仇恨模式（1）——周期性把附近敌对/中立怪的目标改成自己，替玩家挨打。</li>
 * </ul>
 *
 * <p>Shift+左键点击切换模式；空手右键打开绑定管理界面
 * （{@link OGEVolunteerBindScreen}）；Shift+右键手持对应物品收回本体——收回物品只写
 * "有效信息"组件（非默认模式才写 {@link ModDataComponents#VOLUNTEER_MODE}、有绑定才写
 * {@code VOLUNTEER_BINDS}、有自定义名才写 {@code custom_name}），什么都没干的志愿者
 * 收回后不带任何组件，与刚合成的完全一致、照常堆叠。</p>
 *
 * <p>绑定列表（只含锯片台坐标；自定义名存在锯片台方块实体上，绑同一台的所有志愿者
 * 共用并同步）每 20 刻与世界对账一次：方块没了、或被别的
 * 志愿者占了反向引用，就剔除；新绑上的锯片台反向写回 {@code boundVolunteerId}。
 * 锯片台被打掉时其反向引用被清除（{@link OGESawbladeMountBlockEntity#preRemoveSideEffects}）。</p>
 */
public final class OGEVolunteerWatcherEntity extends Mob {

    /** 玩家模式。 */
    public static final int PLAYER_MODE = 0;
    /** 怪物仇恨模式。 */
    public static final int MOB_TARGET_MODE = 1;

    private static final int TARGET_REFRESH_TICKS = 20;
    private static final double MAX_TARGET_SCAN_RANGE = 128.0;
    private static final int RETALIATION_WINDOW_TICKS = 100;
    private static final int RETALIATION_KEEP_ALIVE = 60;

    private static final EntityDataAccessor<Integer> DATA_MODE =
            SynchedEntityData.defineId(OGEVolunteerWatcherEntity.class, EntityDataSerializers.INT);

    /** 玩家模式下的假玩家投影（不入世界实体表，仅渲染与占位）。 */
    private OGEVolunteerSpawnPlayer spawnPlayer;
    private final List<OGEVolunteerBindEntry> bladeBinds = new ArrayList<>();
    private int bladeRetaliationTicks;
    private int bindReconcileCountdown = 1;

    public OGEVolunteerWatcherEntity(EntityType<? extends OGEVolunteerWatcherEntity> type, Level level) {
        super(type, level);
        this.setNoAi(true);
        this.setPersistenceRequired();
        this.setPermanentlyInvulnerable(true);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0)
                .add(Attributes.MOVEMENT_SPEED, 0.0)
                .add(Attributes.KNOCKBACK_RESISTANCE, 1.0);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_MODE, PLAYER_MODE);
    }

    public int getMode() {
        return this.entityData.get(DATA_MODE) == MOB_TARGET_MODE ? MOB_TARGET_MODE : PLAYER_MODE;
    }

    public void setMode(int mode) {
        int clamped = mode == MOB_TARGET_MODE ? MOB_TARGET_MODE : PLAYER_MODE;
        if (this.getMode() != clamped) {
            this.entityData.set(DATA_MODE, clamped);
            if (clamped == MOB_TARGET_MODE) {
                this.removeSpawnPlayer();
            } else {
                this.releaseMobAggro();
            }
        }
    }

    /** 怪物 AI 的“可视为敌人”判定：仇恨模式或被锯片反击窗口内才成立。 */
    @Override
    public boolean canBeSeenAsEnemy() {
        return this.getMode() == MOB_TARGET_MODE || this.bladeRetaliationTicks > 0;
    }

    /** 锯片台反击事件：把仇恨窗口重置为 {@value RETALIATION_WINDOW_TICKS} 刻。 */
    public void noteBladeRetaliation() {
        this.bladeRetaliationTicks = RETALIATION_WINDOW_TICKS;
    }

    /** Shift+左键：循环切换模式并给玩家行动栏提示。 */
    public void cycleMode(Player player) {
        this.setMode(this.getMode() == PLAYER_MODE ? MOB_TARGET_MODE : PLAYER_MODE);
        player.sendOverlayMessage(Component.translatable(
                "message.origolds_ia.volunteer_watcher_mode",
                Component.translatable(this.getMode() == PLAYER_MODE
                        ? "mode.origolds_ia.volunteer_watcher.player"
                        : "mode.origolds_ia.volunteer_watcher.mob_target")));
    }

    @Override
    public void tick() {
        super.tick();
        // 站桩：锁死移动、回满血、朝向冻结。
        this.setDeltaMovement(Vec3.ZERO);
        this.setHealth(this.getMaxHealth());
        this.setYBodyRot(this.getYRot());
        this.setYHeadRot(this.getYRot());

        if (!this.level().isClientSide() && this.tickCount % TARGET_REFRESH_TICKS == 0) {
            if (this.getMode() == PLAYER_MODE) {
                this.ensureSpawnPlayer();
            } else {
                this.removeSpawnPlayer();
                this.attractNearbyMobs();
            }
        }

        if (!this.level().isClientSide()) {
            if (this.tickCount % TARGET_REFRESH_TICKS == 0) {
                if (this.bladeRetaliationTicks > 0) {
                    this.bladeRetaliationTicks -= TARGET_REFRESH_TICKS;
                }
                if (this.bladeRetaliationTicks < RETALIATION_KEEP_ALIVE && this.hasMobTargetingMe()) {
                    this.bladeRetaliationTicks = RETALIATION_KEEP_ALIVE;
                }
            }
            if (--this.bindReconcileCountdown <= 0) {
                this.bindReconcileCountdown = TARGET_REFRESH_TICKS;
                this.reconcileBladeBinds();
            }
        }
    }

    private boolean hasMobTargetingMe() {
        AABB scanBox = this.getBoundingBox().inflate(MAX_TARGET_SCAN_RANGE);
        for (Mob mob : this.level().getEntitiesOfClass(Mob.class, scanBox, this::isAttractableMob)) {
            if (mob.getTarget() == this) {
                return true;
            }
        }
        return false;
    }

    /** 仇恨模式：把扫描盒内的敌对/中立怪的目标都改成自己。 */
    private void attractNearbyMobs() {
        if (this.getMode() == PLAYER_MODE) {
            return;
        }
        AABB scanBox = this.getBoundingBox().inflate(MAX_TARGET_SCAN_RANGE);
        for (Mob mob : this.level().getEntitiesOfClass(Mob.class, scanBox, this::isAttractableMob)) {
            double followRange = mob.getAttribute(Attributes.FOLLOW_RANGE) == null
                    ? 32.0 : mob.getAttributeValue(Attributes.FOLLOW_RANGE);
            if (mob.distanceToSqr(this) > followRange * followRange) {
                continue;
            }
            mob.setTarget(this);
            if (mob instanceof NeutralMob neutralMob) {
                neutralMob.setPersistentAngerTarget(EntityReference.of(this));
                neutralMob.startPersistentAngerTimer();
            }
            if (mob.getBrain().hasMemoryValue(MemoryModuleType.ATTACK_TARGET)) {
                mob.getBrain().setMemory(MemoryModuleType.ATTACK_TARGET, this);
            }
        }
    }

    private boolean isAttractableMob(Mob mob) {
        return mob != this && mob.isAlive() && (mob instanceof Enemy || mob instanceof NeutralMob);
    }

    /** 切回玩家模式时把吸来的仇恨全部解除。 */
    private void releaseMobAggro() {
        if (!(this.level() instanceof ServerLevel serverLevel) || serverLevel.isClientSide()) {
            return;
        }
        UUID selfId = this.getUUID();
        AABB scanBox = this.getBoundingBox().inflate(MAX_TARGET_SCAN_RANGE);
        for (Mob mob : serverLevel.getEntitiesOfClass(Mob.class, scanBox, this::isAttractableMob)) {
            if (mob.getTarget() == this) {
                mob.setTarget(null);
            }
            if (mob instanceof NeutralMob neutralMob && neutralMob.getPersistentAngerTarget() != null
                    && neutralMob.getPersistentAngerTarget().matches(this)) {
                neutralMob.stopBeingAngry();
            }
            if (mob.getBrain().isMemoryValue(MemoryModuleType.ATTACK_TARGET, this)) {
                mob.getBrain().eraseMemory(MemoryModuleType.ATTACK_TARGET);
            }
            if (mob.getBrain().isMemoryValue(MemoryModuleType.ANGRY_AT, selfId)) {
                mob.getBrain().eraseMemory(MemoryModuleType.ANGRY_AT);
            }
        }
    }

    /** 玩家模式：维持身旁假玩家投影（复用同一实体 id，每 20 刻贴一次位置）。 */
    private void ensureSpawnPlayer() {
        if (!(this.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        if (this.spawnPlayer != null && !this.spawnPlayer.isRemoved()) {
            this.spawnPlayer.teleportTo(this.getX(), this.getY(), this.getZ());
            this.spawnPlayer.setYRot(this.getYRot());
            serverLevel.getChunkSource().move(this.spawnPlayer);
            return;
        }
        UUID proxyId = UUID.nameUUIDFromBytes(
                ("origolds_ia:volunteer_watcher:" + this.getUUID()).getBytes(StandardCharsets.UTF_8));
        String name = "OGEWatch" + this.getStringUUID().substring(0, 8);
        this.spawnPlayer = new OGEVolunteerSpawnPlayer(serverLevel, new GameProfile(proxyId, name));
        this.spawnPlayer.teleportTo(this.getX(), this.getY(), this.getZ());
        this.spawnPlayer.setYRot(this.getYRot());
        serverLevel.addNewPlayer(this.spawnPlayer);
    }

    /**
     * 玩家模式下用于代表志愿者完成致命击杀的隐藏代理玩家。
     * 返回值仅在服务端玩家模式下有效；代理尚未创建时会立即补建。
     */
    public ServerPlayer playerModeKillProxy() {
        if (this.level().isClientSide() || this.getMode() != PLAYER_MODE) {
            return null;
        }
        this.ensureSpawnPlayer();
        return this.spawnPlayer != null && !this.spawnPlayer.isRemoved() ? this.spawnPlayer : null;
    }

    private void removeSpawnPlayer() {
        if (this.spawnPlayer != null && !this.spawnPlayer.isRemoved()
                && this.level() instanceof ServerLevel serverLevel) {
            serverLevel.removePlayerImmediately(this.spawnPlayer, RemovalReason.DISCARDED);
        }
        this.spawnPlayer = null;
    }

    /** 左键全部拦下（本实体不吃近战）；Shift+左键切换模式。 */
    @Override
    public boolean skipAttackInteraction(Entity attacker) {
        if (attacker instanceof Player player && player.isShiftKeyDown()) {
            if (!this.level().isClientSide()) {
                this.cycleMode(player);
            }
            return true;
        }
        return true;
    }

    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        boolean client = this.level().isClientSide();
        if (player.isSecondaryUseActive()) {
            // Shift+空手右键：收回本体为物品（状态带走）
            if (!client && !this.isRemoved()) {
                ItemStack returned = new ItemStack(ModBlocks.VOLUNTEER_WATCHER.get());
                this.copyStateToItem(returned);
                if (!player.getInventory().add(returned)) {
                    this.spawnAtLocation((ServerLevel) this.level(), returned);
                }
                this.level().playSound(null, this.getX(), this.getY(), this.getZ(),
                        SoundEvents.ARMOR_STAND_BREAK, SoundSource.NEUTRAL, 1.0F, 1.0F);
                this.removeSpawnPlayer();
                this.discard();
            }
            return client ? InteractionResult.CONSUME : InteractionResult.SUCCESS;
        }
        // 右键：打开绑定管理界面
        if (!client && !this.isRemoved() && player instanceof ServerPlayer serverPlayer) {
            OGEVolunteerBindManager.openConfig(serverPlayer, this.getId());
        }
        return client ? InteractionResult.CONSUME : InteractionResult.SUCCESS;
    }

    /**
     * 把"有效信息"写进物品组件（收进背包时调用）。
     *
     * <p>默认玩家模式不写组件：什么也没配置过的志愿者收回后与刚合成的物品逐字节一致、
     * 照常堆叠；只有仇恨模式、绑定列表、自定义名这三类有效信息才产生组件差异。</p>
     */
    public void copyStateToItem(ItemStack stack) {
        if (this.getMode() != PLAYER_MODE) {
            stack.set(ModDataComponents.VOLUNTEER_MODE.get(), this.getMode());
        }
        if (this.hasCustomName()) {
            stack.set(DataComponents.CUSTOM_NAME, this.getCustomName());
        }
        if (!this.bladeBinds.isEmpty()) {
            stack.set(ModDataComponents.VOLUNTEER_BINDS.get(), List.copyOf(this.bladeBinds));
        }
    }

    /** 从物品组件恢复绑定列表（放实体时调用）。 */
    public void restoreBindsFromItem(ItemStack stack) {
        List<OGEVolunteerBindEntry> saved = stack.get(ModDataComponents.VOLUNTEER_BINDS.get());
        if (saved == null || saved.isEmpty()) {
            return;
        }
        this.bladeBinds.clear();
        for (OGEVolunteerBindEntry entry : saved) {
            if (!this.hasBind(entry.pos())) {
                this.bladeBinds.add(entry);
            }
        }
        this.adoptLegacyBindNames();
        this.onBindsChanged();
    }

    /**
     * 把 1.0.1 之前"名字随记录走"的旧绑定名迁移到锯片台方块实体上（刃槽还没有名字时
     * 才覆盖），随后清掉记录里的过渡名字。锯片台区块未加载时保留旧名字，等每刻对账重试。
     */
    private void adoptLegacyBindNames() {
        if (this.level().isClientSide()) {
            return;
        }
        for (int i = 0; i < this.bladeBinds.size(); i++) {
            OGEVolunteerBindEntry entry = this.bladeBinds.get(i);
            if (entry.hasLegacyName() && this.level().isLoaded(entry.pos())) {
                if (this.level().getBlockEntity(entry.pos()) instanceof OGESawbladeMountBlockEntity mount
                        && mount.getVolunteerBindName().isEmpty()) {
                    mount.setVolunteerBindName(entry.legacyName());
                }
                this.bladeBinds.set(i, new OGEVolunteerBindEntry(entry.pos()));
            }
        }
    }

    /** 免疫一切伤害（服务端伤害结算入口，26.x 取代旧 hurt）。 */
    @Override
    public boolean hurtServer(ServerLevel serverLevel, DamageSource source, float amount) {
        return false;
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

    public List<OGEVolunteerBindEntry> bladeBinds() {
        return List.copyOf(this.bladeBinds);
    }

    public boolean hasBind(BlockPos pos) {
        for (OGEVolunteerBindEntry entry : this.bladeBinds) {
            if (entry.pos().equals(pos)) {
                return true;
            }
        }
        return false;
    }

    public boolean addBind(BlockPos pos) {
        if (this.hasBind(pos)) {
            return false;
        }
        this.bladeBinds.add(new OGEVolunteerBindEntry(pos));
        this.onBindsChanged();
        return true;
    }

    public boolean removeBind(BlockPos pos) {
        boolean removed = this.bladeBinds.removeIf(entry -> entry.pos().equals(pos));
        if (removed) {
            this.clearMountBackReference(pos);
            this.onBindsChanged();
        }
        return removed;
    }

    /**
     * 绑定的锯片台显示名：直接读方块实体上的共享名（所有绑定该台的志愿者看到的是同一个）。
     * 方块被拆/区块未加载时返回空串。
     */
    public String bindDisplayName(BlockPos pos) {
        return this.level().isLoaded(pos)
                && this.level().getBlockEntity(pos) instanceof OGESawbladeMountBlockEntity mount
                ? mount.getVolunteerBindName() : "";
    }

    /** 改名：写进锯片台方块实体，返回是否改成功（须已绑定且目标仍是loaded的锯片台）。 */
    public boolean renameBind(BlockPos pos, String name) {
        if (!this.hasBind(pos)) {
            return false;
        }
        if (this.level().isLoaded(pos)
                && this.level().getBlockEntity(pos) instanceof OGESawbladeMountBlockEntity mount) {
            mount.setVolunteerBindName(name == null ? "" : name);
            return true;
        }
        return false;
    }

    /** 按给定坐标顺序重排绑定列表（未列出的排到最后）。 */
    public void reorderBinds(List<BlockPos> order) {
        List<OGEVolunteerBindEntry> sorted = new ArrayList<>(this.bladeBinds.size());
        for (BlockPos pos : order) {
            Iterator<OGEVolunteerBindEntry> it = this.bladeBinds.iterator();
            while (it.hasNext()) {
                OGEVolunteerBindEntry entry = it.next();
                if (entry.pos().equals(pos)) {
                    sorted.add(entry);
                    it.remove();
                    break;
                }
            }
        }
        sorted.addAll(this.bladeBinds);
        this.bladeBinds.clear();
        this.bladeBinds.addAll(sorted);
        this.onBindsChanged();
    }

    private void dropAllMountBackReferences() {
        for (OGEVolunteerBindEntry entry : this.bladeBinds) {
            this.clearMountBackReference(entry.pos());
        }
    }

    private void clearMountBackReference(BlockPos pos) {
        if (this.level().isLoaded(pos)
                && this.level().getBlockEntity(pos) instanceof OGESawbladeMountBlockEntity mount
                && this.getUUID().equals(mount.getBoundVolunteerId())) {
            mount.setBoundVolunteer(null);
            mount.setChanged();
        }
    }

    /** 与世界对账绑定列表：先迁移遗留绑定名；方块消失 / 被他人占用 → 剔除；自己独占而对方未记 → 回写。 */
    private void reconcileBladeBinds() {
        this.adoptLegacyBindNames();
        if (this.bladeBinds.isEmpty()) {
            return;
        }
        boolean changed = false;
        Iterator<OGEVolunteerBindEntry> it = this.bladeBinds.iterator();
        while (it.hasNext()) {
            BlockPos pos = it.next().pos();
            if (!this.level().isLoaded(pos)) {
                continue;
            }
            if (this.level().getBlockEntity(pos) instanceof OGESawbladeMountBlockEntity mount) {
                if (mount.getBoundVolunteerId() == null) {
                    mount.setBoundVolunteer(this.getUUID());
                    mount.setChanged();
                } else if (!this.getUUID().equals(mount.getBoundVolunteerId())) {
                    it.remove();
                    changed = true;
                }
            } else {
                it.remove();
                changed = true;
            }
        }
        if (changed) {
            this.onBindsChanged();
        }
    }

    /**
     * 绑定变化钩子。上游为空实现：列表同步只在
     * {@link OGEVolunteerBindManager} 处理完客户端动作后显式回发，
     * 这里保留挂子以贴合原版语义。
     */
    private void onBindsChanged() {
    }

    @Override
    public void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        output.putInt("VolunteerMode", this.getMode());
        output.store("SawbladeBinds", OGEVolunteerBindEntry.CODEC.listOf(), List.copyOf(this.bladeBinds));
    }

    @Override
    public void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        this.setMode(input.getIntOr("VolunteerMode", PLAYER_MODE));
        this.setHealth(this.getMaxHealth());
        this.bladeBinds.clear();
        // 26.x 新格式使用小写 pos/name；兼容 1.21.1 存档中的大写 Pos/Name。
        List<OGEVolunteerBindEntry> savedBinds = input
                .read("SawbladeBinds", OGEVolunteerBindEntry.CODEC.listOf())
                .orElseGet(() -> input.read("SawbladeBinds", OGEVolunteerBindEntry.LEGACY_CODEC.listOf())
                        .orElse(List.of()));
        for (OGEVolunteerBindEntry entry : savedBinds) {
            if (!this.hasBind(entry.pos())) {
                this.bladeBinds.add(entry);
            }
        }
        // 锯片台区块可能还没加载，迁移不掉的名字会留在记录上，由每刻对账继续处理。
        this.adoptLegacyBindNames();
    }

    @Override
    public void onRemovedFromLevel() {
        this.releaseMobAggro();
        this.removeSpawnPlayer();
        this.dropAllMountBackReferences();
        super.onRemovedFromLevel();
    }

    @Override
    public ItemStack getPickResult() {
        return new ItemStack(ModBlocks.VOLUNTEER_WATCHER.get());
    }
}
