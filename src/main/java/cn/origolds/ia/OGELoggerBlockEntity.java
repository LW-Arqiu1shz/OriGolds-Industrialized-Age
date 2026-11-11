package cn.origolds.ia;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 金源伐木模块的方块实体。
 *
 * <p>每个游戏刻（服务器端）执行一次伐木判定：
 * <ol>
 *   <li>检测以自身为中心的 3×3×3 方块内是否有木头（{@code #minecraft:logs} 标签，
 *       含下界菌柄，以及红树根与泥泞的红树根）；</li>
 *   <li>若有，则以“沿木头做 3×3×3 判定”的方式洪泛扩展，统计整棵树的木头数量，
 *       单次操作最多锁定 {@value #MAX_WOOD_PER_OPERATION} 个木头（含红树根）；</li>
 *   <li>若木头数量不超过本方块接入的无线电网中可用的 OGE，则消耗等量 OGE，
 *       并把判定到的全部木头摧毁；</li>
 *   <li>每摧毁 1 个木头（或红树根）可额外摧毁最多 {@value #LEAVES_PER_WOOD} 个树叶：
 *       先取与本次采掘的木头相连的树叶，再取与这些树叶相连的树叶。
 *       破坏树叶不消耗 OGE，树叶数量也不参与 OGE 消耗量的计算；
 *       这里的“树叶”除 {@code #minecraft:leaves} 标签外，还包括下界巨菌的
 *       菌光体、诡异疣块与下界疣块，以便整棵下界树连树冠一起清理；</li>
 *   <li>最后清理藤蔓：与本次采掘的木头或树叶相邻的藤蔓，以及沿藤蔓互相
 *       相连延伸出去的整串藤蔓，全部摧毁，<b>不设数量上限</b>；</li>
 *   <li>木头（含红树根）、树叶与藤蔓的全部掉落物会聚集在本方块上方
 *       （按物品合并堆叠后生成掉落物实体）；</li>
 *   <li>能量不足时不砍伐。</li>
 * </ol>
 *
 * <p>单次操作摧毁的木头 + 红树根不超过 {@value #MAX_WOOD_PER_OPERATION} 个，防止一次破坏过多；
 * 树叶不计入该上限，其数量按本次实际摧毁的木头 + 红树根的 {@value #LEAVES_PER_WOOD} 倍计算；
 * 藤蔓清理不计入任何上限（见 {@link #destroyVinesConnectedTo}）。
 *
 * <p>伐木判定分两拍执行：发现木头的当刻只登记状态，下一游戏刻才真正消耗 OGE 并摧毁，
 * 以避免同一游戏刻内“检测 + 破坏”导致方块状态刷新失败（见 {@link #loggingPending}）。
 */
public final class OGELoggerBlockEntity extends BlockEntity {

    /** 单次操作最多摧毁的木头（含红树根）数量，防止一次破坏过多。树叶不计入此上限。 */
    private static final int MAX_WOOD_PER_OPERATION = 256;

    /** 每摧毁 1 个木头（或红树根）允许额外摧毁的树叶数量。 */
    private static final int LEAVES_PER_WOOD = 16;

    /**
     * 是否已发现木头、正在等待那 1 个游戏刻。
     *
     * <p>为避免“同一游戏刻内既检测又破坏”导致方块状态刷新来不及落地
     * （例如方块刚被放置或被邻居更新，状态还没稳定就被摧毁），伐木判定改为两拍：
     * 第 1 刻只登记“有木头可砍”，第 2 刻才真正消耗 OGE 并摧毁。
     * 每次操作结束后复位，下一次伐木重新计时。</p>
     */
    private boolean loggingPending = false;

    /**
     * 藤蔓家族方块：主世界藤蔓、 Cave 藤（垂泪藤/扭曲藤的 plant 形态一并计入）。
     *
     * <p>注意：原版 1.21.1 <b>不存在</b> {@code #minecraft:vines} 方块标签
     * （只有 {@code #minecraft:cave_vines}），此前用该标签判定导致
     * {@code state.is(TAG_VINES)} 恒为 false、藤蔓清理整段静默失效。
     * 这里改为显式比对藤蔓家族的全部 7 个方块。</p>
     */
    private static boolean isVine(BlockState state) {
        Block b = state.getBlock();
        return b == Blocks.VINE
                || b == Blocks.CAVE_VINES
                || b == Blocks.CAVE_VINES_PLANT
                || b == Blocks.TWISTING_VINES
                || b == Blocks.TWISTING_VINES_PLANT
                || b == Blocks.WEEPING_VINES
                || b == Blocks.WEEPING_VINES_PLANT;
    }

    public OGELoggerBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.OGE_LOGGER.get(), pos, state);
    }

    public void tick() {
        if (level == null || level.isClientSide()) {
            return;
        }
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }

        // 1. 以自身为中心的 3×3×3 内是否发现木头
        Set<BlockPos> wood = new HashSet<>();
        Deque<BlockPos> queue = new ArrayDeque<>();
        scanWindow(serverLevel, worldPosition, wood, queue);
        if (wood.isEmpty()) {
            loggingPending = false;
            return;
        }

        // 1.5 挖掘前先等 1 个游戏刻：本刻只登记“有木头可砍”，
        //     下一游戏刻重新扫描确认后再真正消耗 OGE 并摧毁，
        //     给同一刻内发生的方块状态刷新留出落地时间。
        if (!loggingPending) {
            loggingPending = true;
            return;
        }

        // 2. 沿木头执行 3×3×3 判定扩展整棵树，木头（含红树根）数量封顶在单次操作上限以内
        while (!queue.isEmpty() && wood.size() < MAX_WOOD_PER_OPERATION) {
            scanWindow(serverLevel, queue.poll(), wood, queue);
        }
        int woodCount = Math.min(wood.size(), MAX_WOOD_PER_OPERATION);
        if (woodCount <= 0) {
            loggingPending = false;
            return;
        }
        MinecraftServer server = serverLevel.getServer();
        if (server == null) {
            loggingPending = false;
            return;
        }

        // 3. 从所在无线电网（电池优先）原子扣除等量 OGE：不足则整树不砍。
        //    树叶免费，不计入消耗量，也不参与这里的充足性判定。
        int consumed = OGEWirelessManager.consumeOGEIfAvailable(
                server, serverLevel.dimension(), worldPosition, woodCount);
        if (consumed < woodCount) {
            loggingPending = false;
            return;
        }

        // 4. 先摧毁木头（含红树根），其数量已在扫描阶段封顶在单次操作上限以内。
        List<ItemStack> drops = new ArrayList<>();
        int destroyedWood = 0;
        for (BlockPos pos : wood) {
            if (destroyedWood >= MAX_WOOD_PER_OPERATION) {
                break;
            }
            if (serverLevel.isLoaded(pos) && isWood(serverLevel, pos)) {
                collectAndRemove(serverLevel, pos, drops);
                destroyedWood++;
            }
        }

        // 5. 圈定并可额外摧毁的树叶：先取与木头直接相连的，再取与这些树叶相连的；
        //    数量上限 = 本次实际摧毁的木头（含红树根）数 × 16，树叶不计入单次操作上限。
        int leafBudget = destroyedWood * LEAVES_PER_WOOD;
        List<BlockPos> leafTargets = new ArrayList<>();
        if (leafBudget > 0) {
            Set<BlockPos> layer1 = new LinkedHashSet<>();
            for (BlockPos w : wood) {
                for (BlockPos n : neighbors26(serverLevel, w)) {
                    if (isLeaf(serverLevel, n)) {
                        layer1.add(n);
                    }
                }
            }
            leafTargets.addAll(layer1);
            if (leafTargets.size() < leafBudget) {
                Set<BlockPos> layer2 = new LinkedHashSet<>();
                for (BlockPos l : layer1) {
                    for (BlockPos n : neighbors26(serverLevel, l)) {
                        if (!layer1.contains(n) && isLeaf(serverLevel, n)) {
                            layer2.add(n);
                        }
                    }
                }
                leafTargets.addAll(layer2);
            }
        }

        // 6. 摧毁树叶，掉落物与木头的一起收集到本方块上方。
        int leavesLeft = leafBudget;
        for (BlockPos pos : leafTargets) {
            if (leavesLeft <= 0) {
                break;
            }
            if (serverLevel.isLoaded(pos) && isLeaf(serverLevel, pos)) {
                collectAndRemove(serverLevel, pos, drops);
                leavesLeft--;
            }
        }

        // 7. 清理藤蔓：与本次采掘的木头或树叶相连的藤蔓整串摧毁，不设上限。
        //    （级联参数修复后，原版的形状更新已能处理大部分失去支撑的藤蔓；
        //    但仍有零星场景不会立即见效，例如藤蔓还挂着别的支撑面、
        //    或整串被本次采掘"隔断"在更新链之外。这里显式整串摧毁兜底。）
        destroyVinesConnectedTo(serverLevel, wood, leafTargets, drops);

        spawnDropsAboveMachine(serverLevel, drops);
        loggingPending = false;
        setChanged();
    }

    /**
     * 按掉落表收集该方块的掉落物（合并进 {@code drops}），然后不产生原位掉落
     * 直接摧毁方块。
     *
     * <p><b>关于最后一个参数</b>：{@code destroyBlock(BlockPos, boolean, Entity, int)}
     * 的第 4 个参数是邻居更新级联的<b>递归深度上限</b>（原版默认
     * {@link Block#UPDATE_LIMIT} = 512），不是方块更新标志位。此处此前误传
     * {@link Block#UPDATE_ALL}（值为 3），导致级联最多只向外传播 3 层，
     * 更远处的藤蔓、树叶等依附性方块收不到 {@code updateShape} 而残留成浮空方块。
     * 因此必须传 {@link Block#UPDATE_LIMIT}。
     */
    private static void collectAndRemove(ServerLevel level, BlockPos pos, List<ItemStack> drops) {
        BlockState state = level.getBlockState(pos);
        BlockEntity be = level.getBlockEntity(pos);
        for (ItemStack stack : Block.getDrops(state, level, pos, be)) {
            if (!stack.isEmpty()) {
                mergeInto(drops, stack);
            }
        }
        // 第 4 个参数是级联递归上限，必须用 UPDATE_LIMIT（512）；UPDATE_ALL 是标志位，值为 3。
        level.destroyBlock(pos, false, null, Block.UPDATE_LIMIT);
    }

    /**
     * 摧毁所有与本次采掘的木头或树叶相连的藤蔓：先取这些方块六个方向邻居中的
     * 藤蔓作为起点，再沿“藤蔓与藤蔓直接相邻”的关系向外扩散，把整串藤蔓全部
     * 摧毁——不设数量上限。掉落物同样并入 {@code drops} 集中到机器上方。
     *
     * <p>之所以必须显式摧毁：原版藤蔓只在自身收到形状更新时修剪掉失去支撑的
     * 面，或因 {@code randomTick}（平均数十秒一次）逐层枯萎；摧毁木头时留下的
     * 悬空藤蔓链不会立即整串破碎。</p>
     */
    private static void destroyVinesConnectedTo(ServerLevel level, Set<BlockPos> wood,
                                                List<BlockPos> leaves, List<ItemStack> drops) {
        Set<BlockPos> seen = new HashSet<>();
        Deque<BlockPos> frontier = new ArrayDeque<>();
        for (BlockPos anchor : wood) {
            queueAttachedVines(level, anchor, seen, frontier);
        }
        for (BlockPos anchor : leaves) {
            queueAttachedVines(level, anchor, seen, frontier);
        }
        while (!frontier.isEmpty()) {
            BlockPos pos = frontier.poll();
            if (!level.isLoaded(pos) || !isVine(level.getBlockState(pos))) {
                continue; // 可能已在此前的级联中被顺带摧毁
            }
            collectAndRemove(level, pos, drops);
            // 继续沿藤蔓互相相邻的关系扩散，整串清除，无上限
            queueAttachedVines(level, pos, seen, frontier);
        }
    }

    /** 把 {@code center} 六向邻居中尚未入队过的藤蔓位置加入 {@code frontier}。 */
    private static void queueAttachedVines(ServerLevel level, BlockPos center,
                                           Set<BlockPos> seen, Deque<BlockPos> frontier) {
        for (Direction dir : Direction.values()) {
            BlockPos n = center.relative(dir);
            if (seen.add(n) && level.isLoaded(n) && isVine(level.getBlockState(n))) {
                frontier.add(n);
            }
        }
    }

    /** 把一组掉落物按“同物品同组件且未满堆叠”规则合并进列表，减少掉落物实体数量。 */
    private static void mergeInto(List<ItemStack> out, ItemStack stack) {
        ItemStack rest = stack;
        for (ItemStack have : out) {
            if (rest.isEmpty()) {
                return;
            }
            if (have.getCount() < have.getMaxStackSize()
                    && ItemStack.isSameItemSameComponents(have, rest)) {
                int take = Math.min(have.getMaxStackSize() - have.getCount(), rest.getCount());
                have.grow(take);
                rest.shrink(take);
            }
        }
        if (!rest.isEmpty()) {
            out.add(rest);
        }
    }

    /** 在伐木模块正上方生成合并后的掉落物，供玩家直接拾取。 */
    private void spawnDropsAboveMachine(ServerLevel level, List<ItemStack> stacks) {
        if (stacks.isEmpty()) {
            return;
        }
        double cx = worldPosition.getX() + 0.5;
        double cy = worldPosition.getY() + 1.1;
        double cz = worldPosition.getZ() + 0.5;
        for (ItemStack stack : stacks) {
            double jitterX = (level.getRandom().nextDouble() - 0.5) * 0.4;
            double jitterZ = (level.getRandom().nextDouble() - 0.5) * 0.4;
            ItemEntity item = new ItemEntity(level, cx + jitterX, cy, cz + jitterZ, stack);
            item.setDeltaMovement(0.0, 0.05, 0.0);
            item.setNoPickUpDelay();
            level.addFreshEntity(item);
        }
    }

    /**
     * 以 {@code center} 为中心的 3×3×3 窗口扫描木头，把新发现的木头加入
     * {@code out} 与 {@code queue}；总数达到单次操作上限后立即停止。
     */
    private static void scanWindow(ServerLevel level, BlockPos center, Set<BlockPos> out, Deque<BlockPos> queue) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    if (out.contains(pos) || !level.isLoaded(pos)) {
                        continue;
                    }
                    if (isWood(level, pos)) {
                        out.add(pos);
                        queue.add(pos);
                        if (out.size() >= MAX_WOOD_PER_OPERATION) {
                            return;
                        }
                    }
                }
            }
        }
    }

    /** 某方块的 26 邻居（3×3×3 去除自身），只保留已加载位置。 */
    private static List<BlockPos> neighbors26(ServerLevel level, BlockPos center) {
        List<BlockPos> list = new ArrayList<>(26);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) {
                        continue;
                    }
                    BlockPos pos = center.offset(dx, dy, dz);
                    if (level.isLoaded(pos)) {
                        list.add(pos);
                    }
                }
            }
        }
        return list;
    }

    /**
     * 判断某位置的方块是否为伐木模块认可的“木头”：
     * 任何 {@code #minecraft:logs} 标签下的原木/木块类方块，
     * 外加红树根（mangrove_roots）与泥泞的红树根（muddy_mangrove_roots）。
     */
    private static boolean isWood(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.is(BlockTags.LOGS)
                || state.is(Blocks.MANGROVE_ROOTS)
                || state.is(Blocks.MUDDY_MANGROVE_ROOTS);
    }

    /**
     * 判断某位置的方块是否为伐木模块认可的“树叶”：
     * 原版 {@code #minecraft:leaves} 标签下的树叶，外加下界巨菌的“树叶”类方块——
     * 菌光体（shroomlight）、诡异疣块（warped_wart_block）与下界疣块（nether_wart_block）。
     *
     * <p>下界菌类没有真正的树叶，其树冠由疣块与菌光体构成；把这三者一并纳入
     * “树叶”判定后，砍伐诡异/绯红巨型菌时才会连树冠一起清理。</p>
     */
    private static boolean isLeaf(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        Block b = state.getBlock();
        return state.is(BlockTags.LEAVES)
                || b == Blocks.SHROOMLIGHT
                || b == Blocks.WARPED_WART_BLOCK
                || b == Blocks.NETHER_WART_BLOCK;
    }
}
