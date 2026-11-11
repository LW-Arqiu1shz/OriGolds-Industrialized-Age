package cn.origolds.ia;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 金源鼓风机（Origold Blower）的方块实体。
 *
 * <p>服务器端每个游戏刻执行：</p>
 * <ol>
 *   <li><b>结算工作时间</b>：<b>每 {@value #BILLING_INTERVAL_TICKS} 刻（1 秒）判定一次</b>
 *       扣费，从所在无线电网（电池优先）原子扣除
 *       {@link OGEBlowerBlock#ogePerSecond(int, int)} u OGE（距离档 × 力量档）；
 *       成功就把剩余工作时间<b>重置</b>为 {@value #WORK_TICKS_PER_PAYMENT} 刻（1.05 秒），
 *       失败则不再续期、让剩余时间自然走完。
 *       因此这段 1.05 秒通常只走掉 1 秒就被下一轮扣费重新续满——这是刻意的：
 *       即使中途卡顿掉刻，只要这 1 秒里扣费成功，吹风也不会断。
 *       电网取不到电时同样是每 {@value #BILLING_INTERVAL_TICKS} 刻才重试一次扣费，
 *       避免未通电的鼓风机每刻都去遍历一遍电网。</li>
 *   <li><b>吹气</b>：工作期间沿朝向逐格推进，最多
 *       {@link OGEBlowerBlock#rangeBlocks(int)} 格（9 × 距离档，满档 72 格）。
 *       气流可以穿过非完整方块（碰撞箱不是满方块的方块），遇到完整方块即停止。
 *       气流里的实体被<b>以恒定速度</b>推着走：目标是
 *       {@link OGEBlowerBlock#speedPerSecond(int)} 格/秒（9 × 力量档，满档 36 格/秒）。
 *       每刻只查一次实体、同一实体只推一次。</li>
 *   <li><b>贴图动画</b>：工作期间正面在 {@code fan_front1} / {@code fan_front2}
 *       之间按 {@value #ANIM_CYCLE_TICKS} 刻一个来回循环（每秒 4 个循环）。
 *       停机后若正停在第二帧，会把本轮循环走完再停下，因此最终一定停在
 *       {@code fan_front1}（方块状态 {@code active=false}）。</li>
 * </ol>
 */
public final class OGEBlowerBlockEntity extends BlockEntity {

    /** 一次扣费换来的工作刻数：21 刻 = 1.05 秒。 */
    public static final int WORK_TICKS_PER_PAYMENT = 21;

    /**
     * 扣费判定的间隔刻数（20 刻 = 1 秒）。
     *
     * <p>判定成功会把剩余工作时间重置回 1.05 秒，所以正常供电时工作时间永远用不完，
     * 只有真的断电才会在最后一段 1.05 秒走完后停机。</p>
     */
    public static final int BILLING_INTERVAL_TICKS = 20;

    /** 贴图一个来回（fan_front1 → fan_front2 → fan_front1）的刻数：5 刻，即每秒 4 个循环。 */
    public static final int ANIM_CYCLE_TICKS = 5;

    /** 一秒的刻数，用于把“格/秒”换算成“格/刻”。 */
    private static final double TICKS_PER_SECOND = 20.0;

    /** 第二帧所处的动画相位：相位 >= 此值显示 fan_front2，否则显示 fan_front1。 */
    private static final int SECOND_FRAME_PHASE = 3;

    /** 剩余工作刻数；> 0 表示本刻在工作。 */
    private int workTicksRemaining = 0;

    /** 距离下一次扣费判定的刻数。 */
    private int billingCountdown = 0;

    /** 动画相位 0..{@value #ANIM_CYCLE_TICKS}-1。 */
    private int animPhase = 0;

    public OGEBlowerBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.OGE_BLOWER.get(), pos, state);
    }

    public void tick() {
        if (level == null || level.isClientSide()) {
            return;
        }
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }

        BlockState state = getBlockState();
        if (!state.hasProperty(OGEBlowerBlock.DISTANCE) || !state.hasProperty(OGEBlowerBlock.POWER)) {
            return;
        }
        int distance = state.getValue(OGEBlowerBlock.DISTANCE);
        int power = state.getValue(OGEBlowerBlock.POWER);

        // 1. 扣费判定：每 20 刻（1 秒）一次。成功就把工作时间重置为 21 刻（1.05 秒），
        //    所以正常供电时上一段 1.05 秒只会走掉约 1 秒就被续满，吹风不会断；
        //    扣不到电时不续期，剩余时间走完即停机，下一轮判定再试。
        //    扣费金额随档位变化：距离档 × 力量档（1 ~ 32）。
        if (billingCountdown > 0) {
            billingCountdown--;
        }
        if (billingCountdown <= 0) {
            billingCountdown = BILLING_INTERVAL_TICKS;
            if (payForWork(serverLevel, OGEBlowerBlock.ogePerSecond(distance, power))) {
                workTicksRemaining = WORK_TICKS_PER_PAYMENT;
            }
        }
        boolean working = workTicksRemaining > 0;
        if (working) {
            workTicksRemaining--;
        }

        // 2. 工作期间吹气。
        if (working) {
            blowAir(serverLevel, state, distance, power);
        }

        // 3. 推进贴图动画；停机时收尾到第一帧。
        advanceAnimation(working);
    }

    /** 从所在无线电网原子扣除一次费用；成功返回 true（扣不动则分文不取）。 */
    private boolean payForWork(ServerLevel serverLevel, int cost) {
        MinecraftServer server = serverLevel.getServer();
        if (server == null || cost <= 0) {
            return false;
        }
        return OGEWirelessManager.consumeOGEIfAvailable(
                server, serverLevel.dimension(), worldPosition, cost) >= cost;
    }

    /**
     * 沿出风口方向吹出气流：逐格前进，遇到完整方块（碰撞箱满方块）停止。
     * 先量出这段气流的长度与包围盒，再用一次实体查询处理整段气流。
     */
    private void blowAir(ServerLevel serverLevel, BlockState state, int distance, int power) {
        if (!state.hasProperty(OGEBlowerBlock.FACING)) {
            return;
        }
        Direction facing = state.getValue(OGEBlowerBlock.FACING);
        int range = OGEBlowerBlock.rangeBlocks(distance);

        // 1. 量出气流长度：从出风口前一格开始，遇到完整方块或超出射程就停。
        BlockPos mouth = worldPosition.relative(facing);
        int length = 0;
        BlockPos.MutableBlockPos cursor = mouth.mutable();
        for (int step = 0; step < range; step++) {
            if (!serverLevel.isLoaded(cursor)) {
                break;
            }
            if (serverLevel.getBlockState(cursor).isCollisionShapeFullBlock(serverLevel, cursor)) {
                break; // 完整方块挡住气流
            }
            length++;
            cursor.move(facing);
        }
        if (length <= 0) {
            return;
        }
        double dirX = facing.getStepX();
        double dirY = facing.getStepY();
        double dirZ = facing.getStepZ();

        // 2. 气流包围盒：mouth 到最后一格，沿朝向的一段 1×1×N 方盒。
        BlockPos end = mouth.relative(facing, length - 1);
        double minX = Math.min(mouth.getX(), end.getX());
        double minY = Math.min(mouth.getY(), end.getY());
        double minZ = Math.min(mouth.getZ(), end.getZ());
        double maxX = Math.max(mouth.getX(), end.getX()) + 1.0;
        double maxY = Math.max(mouth.getY(), end.getY()) + 1.0;
        double maxZ = Math.max(mouth.getZ(), end.getZ()) + 1.0;
        AABB beam = new AABB(minX, minY, minZ, maxX, maxY, maxZ);

        // 3. 把气流范围内的实体沿气流方向拎到“恒定速度”：
        //    目标速度 = 力量档 × 9 格/秒，换算成 格/刻。
        //    这里只补“当前沿向速度与目标速度的差值”，所以不会随刻数越推越快，
        //    同时也不会抹掉实体的侧向移动与重力（垂直吹风时正好抵消重力）。
        double target = OGEBlowerBlock.speedPerSecond(power) / TICKS_PER_SECOND;
        Vec3 direction = new Vec3(dirX, dirY, dirZ);
        for (Entity entity : serverLevel.getEntitiesOfClass(Entity.class, beam, e -> !e.isSpectator())) {
            double along = entity.getDeltaMovement().dot(direction);
            double add = target - along;
            if (add > 0.0) {
                entity.push(dirX * add, dirY * add, dirZ * add);
                // 玩家客户端不会主动跟随服务端改过的速度，标记一下让连接层补发运动包。
                entity.syncVelocity = true;
            }
        }
    }

    /**
     * 推进贴图动画。工作时每刻前进一相；停机时若正显示第二帧，
     * 继续走完本轮循环，落回第一帧后停住。
     */
    private void advanceAnimation(boolean working) {
        boolean onSecondFrame = animPhase >= SECOND_FRAME_PHASE;
        if (working || onSecondFrame) {
            animPhase = (animPhase + 1) % ANIM_CYCLE_TICKS;
        } else {
            animPhase = 0;
        }
        applyFrontFrame(animPhase >= SECOND_FRAME_PHASE);
    }

    /** 只在帧号变化时改写方块状态，避免每刻刷包。 */
    private void applyFrontFrame(boolean secondFrame) {
        BlockState state = getBlockState();
        if (!state.hasProperty(OGEBlowerBlock.ACTIVE)
                || state.getValue(OGEBlowerBlock.ACTIVE) == secondFrame) {
            return;
        }
        level.setBlock(worldPosition, state.setValue(OGEBlowerBlock.ACTIVE, secondFrame),
                Block.UPDATE_CLIENTS);
    }
}
