package cn.origolds.ia;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 金源采掘模块的方块实体。
 *
 * <p>服务器端每个游戏刻都执行一次采掘判定：
 * <ol>
 *   <li>检测与自身相邻的 6 个方块，收集“非空气、非金源采掘模块、非金源伐木模块
 *       且可被破坏（硬度不为 -1，如基岩不可）”的目标；</li>
 *   <li>若本方块接入的无线电网中可用的 OGE 不少于目标数量，则消耗等量 OGE，
 *       把这些方块全部破坏并掉落其物品；</li>
 *   <li>能量不足时一个方块也不破坏。</li>
 * </ol>
 *
 * <p>采掘判定分两拍执行：发现目标的当刻只登记状态，下一游戏刻才真正消耗 OGE 并破坏，
 * 以避免同一游戏刻内“检测 + 破坏”导致方块状态刷新失败（见 {@link #miningPending}）。
 */
public final class OGEDrillerBlockEntity extends BlockEntity {

    /**
     * 是否已发现可采掘目标、正在等待那 1 个游戏刻。
     *
     * <p>为避免“同一游戏刻内既检测又破坏”导致方块状态刷新来不及落地
     * （例如方块刚被放置或被邻居更新，状态还没稳定就被摧毁），采掘判定改为两拍：
     * 第 1 刻只登记“有目标可挖”，第 2 刻重新检测确认后才真正消耗 OGE 并破坏。
     * 每次操作结束后复位，下一次采掘重新计时。</p>
     */
    private boolean miningPending = false;

    public OGEDrillerBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.OGE_DRILLER.get(), pos, state);
    }

    public void tick() {
        if (level == null || level.isClientSide()) {
            return;
        }

        Level world = level;

        // 1. 收集 6 个相邻面上可采掘的目标方块
        List<BlockPos> targets = new ArrayList<>(6);
        for (Direction dir : Direction.values()) {
            BlockPos pos = worldPosition.relative(dir);
            if (!world.isLoaded(pos)) {
                continue;
            }
            BlockState state = world.getBlockState(pos);
            if (state.isAir() || isSelfModule(state)) {
                continue;
            }
            // 基岩、末地传送门框架等硬度为 -1 的方块不可破坏
            if (state.getDestroySpeed(world, pos) < 0.0F) {
                continue;
            }
            targets.add(pos);
        }
        if (targets.isEmpty()) {
            miningPending = false;
            return;
        }
        if (!(world instanceof ServerLevel serverLevel)) {
            return;
        }
        MinecraftServer server = serverLevel.getServer();
        if (server == null) {
            miningPending = false;
            return;
        }

        // 1.5 挖掘前先等 1 个游戏刻：本刻只登记“有目标可挖”，
        //     下一游戏刻重新检测确认后再真正消耗 OGE 并破坏，
        //     给同一刻内发生的方块状态刷新留出落地时间。
        if (!miningPending) {
            miningPending = true;
            return;
        }

        // 2. 从所在无线电网（电池优先）原子扣除等量 OGE：能量不足则一个也不挖，
        //    扣除成功的同时把相关储能方块的最新数值推送客户端。
        int needed = targets.size();
        int consumed = OGEWirelessManager.consumeOGEIfAvailable(server, world.dimension(), worldPosition, needed);
        if (consumed < needed) {
            miningPending = false;
            return;
        }
        for (BlockPos pos : targets) {
            if (world.isLoaded(pos) && !world.getBlockState(pos).isAir()) {
                // 第 4 个参数是邻居更新级联的递归深度上限，必须是 UPDATE_LIMIT（512）；
                // 此前误传 UPDATE_ALL（标志位，值为 3），级联只传播 3 层，
                // 深处的藤蔓/树叶收不到 updateShape，从而留下浮空方块。
                world.destroyBlock(pos, true, null, Block.UPDATE_LIMIT);
                // 破坏完成后在该位置再补一次邻居更新，让藤蔓、睡莲等
                // 依附性方块立即重新判定支撑，避免留下浮空方块。
                world.updateNeighborsAt(pos, Blocks.AIR);
            }
        }
        miningPending = false;
        setChanged();
    }

    /** 判断方块是否为金源采掘模块或金源伐木模块本身（这两类方块不受采掘影响）。 */
    private static boolean isSelfModule(BlockState state) {
        return state.is(ModBlocks.ORIGOLD_DRILLER.get()) || state.is(ModBlocks.ORIGOLD_LOGGER.get());
    }
}
