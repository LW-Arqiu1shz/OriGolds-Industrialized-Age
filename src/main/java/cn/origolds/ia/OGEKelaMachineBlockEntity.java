package cn.origolds.ia;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BonemealableBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 金源坷垃机（Origold-KeLa Machine）的方块实体。
 *
 * <p>没有界面、没有自己的仓库：它把正上方那一格当成“骨粉投料口”，
 * 服务端每秒（20 刻）跑一轮：</p>
 * <ol>
 *   <li><b>先数目标</b>：扫描作用范围，统计这一轮要扣的 OGE 电费——还长得动的方块
 *       （没成熟的作物、树苗、没长到高度上限的甘蔗……）各算 1 个，顶面暴露在空气中的
 *       泥土也算 1 个；另外，作用范围内每一块会成功向外蔓延的菌岩再各算 1 个（见下）。
 *       判定“还长得动”只认原版骨粉标准：能被骨粉催的方块（作物、树苗、海带、藤……）
 *       以 {@code isValidBonemealTarget} 为准，甘蔗/仙人掌单独看柱高。已经长到头的
 *       作物、长到顶的甘蔗/仙人掌、本机自己铺出来的草方块与菌岩，以及耕地、菌丝、
 *       树叶、珊瑚、雪这类“随机刻只会发生环境变化、根本不吃骨粉”的方块都不算目标：
 *       一个都没有时整轮跳过，不会出现“范围内已经没活干却还在吃骨粉”的情况。</li>
 *   <li><b>再查资源</b>：正上方那一格（容器内或地上的掉落物）得备齐本档要扣的骨粉数量
 *       （3 档 1、5 档 1、7 档 2、9 档 4 个），无线电网里也得有本次电费。
 *       条件是先检查后扣除的，缺一样就整轮跳过、一点资源也不花，保持停机；
 *       3 档在扣粉时只有 25% 的轮次真的扣走 1 个，其余 75% 的轮次不扣，所以 1 个骨粉
 *       平均能撑 4 轮；</li>
 *   <li><b>然后干活</b>：取走骨粉，把范围内所有顶面暴露在空气中的泥土变成草方块，
 *       再让作用范围内的每一块绯红/诡异菌岩，向它自己 3×3×3 内的裸露地狱岩蔓延
 *       （每块菌岩的一次成功扩散行为消耗 1u OGE，不论它这一次感染了几块地狱岩）；
 *       并在整轮持续期间给范围内可随机刻的方块补足随机刻——每个方块被随机刻选中的
 *       概率约为原版的 100 倍，相当于约 100 倍的生长速度。额外补足的随机刻预算有上限
 *       （见 {@link #MAX_BUDGET_RANDOM_TICK_SPEED}），不会跟着 game rule 无限变强。</li>
 * </ol>
 *
 * <p>作用范围是以自身为中心、边长为（2×半径+1）的立方体：1 档 3×3×3 …… 4 档
 * （默认）9×9×9，半径由方块状态 {@link OGEKelaMachineBlock#MODE} 决定，
 * 改档后下一轮立刻按新范围工作。</p>
 *
 * <p><b>菌岩蔓延</b>：作用范围内的每块绯红/诡异菌岩，都会以自身为中心，把自己
 * 3×3×3 内、顶面暴露在空气中的地狱岩变成对应种类的菌岩（与原版“对地狱岩使用骨粉”
 * 的判定一致：目标自身周围 3×3×3 里只能有一种菌岩）。本轮的转换格子先全部收集、
 * 再统一落块，因此新长出的菌岩不会在同一轮里继续向外带，一轮只蔓延一圈。</p>
 *
 * <p><b>为什么是“补足随机刻采样”而不是拦截原版随机刻</b>：原版随机刻由
 * {@code ServerLevel.tickChunk} 内部驱动，外部没有可挂的钩子；这里改为在机器范围
 * 内按期望次数随机取点并直接调用 {@link BlockState#randomTick}，把该区域的随机刻
 * 速率补到约 100 倍，效果与“每次被随机刻选中时额外多来几次”一致，且不依赖 Mixin。</p>
 */
public final class OGEKelaMachineBlockEntity extends BlockEntity {

    /** 一轮工作的时间：1 秒。 */
    private static final int TICKS_PER_CYCLE = 20;

    /** 原版随机刻的采样基数：一个区块有 16×16×16 个位置。 */
    private static final double RANDOM_TICK_POSITIONS = 4096.0;

    /** 额外随机刻机会相对自然速率的倍数：110 倍机会中有 90% 生效，净增 99 倍。 */
    private static final double EXTRA_RANDOM_TICK_FACTOR = 110.0;

    /** 每个额外随机刻机会真正生效的概率：90%。 */
    private static final float EXTRA_RANDOM_TICK_CHANCE = 0.9F;

    /**
     * 额外随机刻预算的随机刻速度上限：换算预算时最多按这个速度算。
     *
     * <p>额外的随机刻机会与 {@code /gamerule randomTickSpeed} 成正比，速度调得越高机器
     * 补得越多。为了不让它跟着这个值无限变强、无限吃性能，这里给预算封顶：随机刻速度
     * 超过 20 之后，机器补出的额外机会不再增加，只保留自然随机刻那一份加速。</p>
     *
     * <p>速度 20 时这套预算相当于每秒给范围内每格补约 10 次额外随机刻，已经是性能上
     * 的一个安全上限；默认速度 3（以及调到 20 以内）不受影响，自然随机刻和额外随机刻
     * 同步增长，仍然是完整的约 100 倍。</p>
     */
    private static final int MAX_BUDGET_RANDOM_TICK_SPEED = 20;

    /**
     * 单块菌岩的蔓延半径：以这块菌岩自身为中心的 3×3×3（半径 1）。
     *
     * <p>哪些菌岩可以发动扩散由机器的作用范围决定（{@link #radius()}），
     * 但每块菌岩感染到多远固定是它自己身边这一圈，不随档位变化。</p>
     */
    private static final int NYLIUM_SPREAD_RADIUS = 1;

    /**
     * 甘蔗、仙人掌这类竖直生长的方块能长到的高度上限：到顶之后随机刻不再有效果。
     * 与原版随机刻里的 {@code height < 3} 判定一致。
     */
    private static final int MAX_VERTICAL_PLANT_HEIGHT = 3;

    /**
     * 停机时开新一轮失败后，隔多少刻再试。开新一轮要把整个作用范围扫两遍
     * （9 档 9×9×9，下界里每块菌岩还要再查一圈邻居），缺骨粉 / 缺电 / 没活干时
     * 旧实现每刻都重扫一遍，纯属浪费。半秒重试一次，玩家感觉不到延迟。
     */
    private static final int IDLE_RETRY_TICKS = 10;

    /** 当前轮内的进度（0~19）；{@code -1} 表示停机。 */
    private int cycleTick = -1;

    /** 停机重试倒计时（不存档：重新加载后立刻尝试一次即可）。 */
    private int idleRetry;

    /**
     * 额外随机刻的小数预算，跨 tick 累积，保证小范围、低随机刻速度下也能精确补足倍率。
     * 预算有上限（见 {@link #accelerateRandomTicks}），不会无限囤积。
     */
    private double extraRandomTickBudget;

    public OGEKelaMachineBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.OGE_KELA_MACHINE.get(), pos, state);
    }

    /** 服务端每 tick 调用（方块 ticker 驱动）。 */
    public void tick() {
        if (level == null || level.isClientSide()) {
            return;
        }
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        if (cycleTick < 0) {
            // 停机中：条件和资源齐了才开新一轮；否则这一轮什么都不做。
            if (idleRetry > 0) {
                idleRetry--;
                return;
            }
            if (!startCycle(serverLevel)) {
                extraRandomTickBudget = 0.0;
                idleRetry = IDLE_RETRY_TICKS;
                return;
            }
            cycleTick = 0;
        }
        accelerateRandomTicks(serverLevel);
        cycleTick++;
        if (cycleTick >= TICKS_PER_CYCLE) {
            cycleTick = -1;
        }
    }

    // ------------------------------------------------------------------
    // 一轮的开始：数目标、查资源、扣骨粉与电、泥土变草、菌岩蔓延
    // ------------------------------------------------------------------

    /**
     * 尝试开始新一轮。条件不满足时返回 {@code false}，并且不会消耗任何资源。
     */
    private boolean startCycle(ServerLevel level) {
        int mode = tierMode();
        int boneMeal = OGEKelaMachineBlock.boneMealCostForMode(mode);
        BlockPos intake = worldPosition.above();
        // 先查最便宜的条件（顶上骨粉够不够），不够就不必扫描整个作用范围。
        if (OGEFaceItems.countAt(level, intake, stack -> stack.is(Items.BONE_MEAL)) < boneMeal) {
            return false;
        }
        // 先算出本轮菌岩蔓延计划：既用来数电费（每块成功扩散的菌岩 1u OGE），
        // 也用来在资源齐了之后真正落块，避免重复扫描。
        NyliumPlan nyliumPlan = planNyliumSpread(level);
        // 电费 = 本轮要处理的方块数（催熟目标 + 待变草的泥土 + 待扩散的菌岩），每个 1 OGE，与档位无关。
        int cost = countFertilizeTargets(level) + nyliumPlan.sourceCount();
        if (cost <= 0) {
            return false;
        }
        MinecraftServer server = level.getServer();
        if (server == null) {
            return false;
        }
        // consumeOGEIfAvailable 是原子的：电量不足时一分不扣，正好对应“停机不消耗”。
        if (OGEWirelessManager.consumeOGEIfAvailable(server, level.dimension(), worldPosition, cost) < cost) {
            return false;
        }
        // 这一轮真正扣多少骨粉：3 档只有 25% 的概率扣 1 个（另外 75% 的轮次不扣），
        // 5/7/9 档每轮固定扣 1/2/4 个。门槛已确认备齐 boneMeal 个，最多也只取这么多；
        // 取不满只可能是世界被并发改动，跳过这一轮。
        int take = boneMeal;
        float chance = OGEKelaMachineBlock.boneMealChanceForMode(mode);
        if (chance < 1.0F && level.getRandom().nextFloat() >= chance) {
            take = 0;
        }
        if (take > 0
                && OGEFaceItems.takeUpToAt(level, intake, stack -> stack.is(Items.BONE_MEAL), take) < take) {
            return false;
        }
        fertilizeGround(level, nyliumPlan);
        return true;
    }

    // ------------------------------------------------------------------
    // 作用范围：数目标、泥土变草、菌岩蔓延、随机刻加速
    // ------------------------------------------------------------------

    /** 统计机器作用范围内“催熟 + 变草”的目标数量，作为这部分的 OGE 电费。 */
    private int countFertilizeTargets(ServerLevel level) {
        int r = radius();
        int count = 0;
        for (BlockPos pos : BlockPos.betweenClosed(
                worldPosition.getX() - r, worldPosition.getY() - r, worldPosition.getZ() - r,
                worldPosition.getX() + r, worldPosition.getY() + r, worldPosition.getZ() + r)) {
            if (isWorkable(level, pos)) {
                count++;
            }
        }
        return count;
    }

    /**
     * 把范围内所有顶面暴露在空气中的泥土变成草方块，再执行已经算好的菌岩蔓延。
     *
     * <p>下界没有泥土，所以这里额外做一件同样属于“施肥”的事：让作用范围内的菌岩
     * 向外蔓延到地狱岩上，见 {@link #planNyliumSpread}。</p>
     */
    private void fertilizeGround(ServerLevel level, NyliumPlan nyliumPlan) {
        int r = radius();
        for (BlockPos pos : BlockPos.betweenClosed(
                worldPosition.getX() - r, worldPosition.getY() - r, worldPosition.getZ() - r,
                worldPosition.getX() + r, worldPosition.getY() + r, worldPosition.getZ() + r)) {
            BlockState state = level.getBlockState(pos);
            if (state.is(Blocks.DIRT) && level.getBlockState(pos.above()).isAir()) {
                level.setBlockAndUpdate(pos, Blocks.GRASS_BLOCK.defaultBlockState());
            }
        }
        for (Map.Entry<BlockPos, Block> spread : nyliumPlan.conversions().entrySet()) {
            level.setBlockAndUpdate(spread.getKey(), spread.getValue().defaultBlockState());
        }
    }

    /**
     * 本轮菌岩蔓延计划。
     *
     * @param conversions   要转换的地狱岩 → 它应该变成的菌岩；已经去过重
     * @param sourceCount   本轮成功发动扩散（至少感染到一块地狱岩）的菌岩块数，
     *                      也就是这部分要扣的 OGE 电费：每块菌岩的一次成功扩散行为 1u OGE，
     *                      不论它这一次感染了 1 块还是 10 块地狱岩
     */
    private record NyliumPlan(Map<BlockPos, Block> conversions, int sourceCount) {
    }

    /**
     * 计算机器作用范围内所有菌岩的蔓延：每块菌岩以自身为中心，把自己 3×3×3 内
     * 顶面暴露在空气中的地狱岩变成对应种类的菌岩。
     *
     * <p>逐块判定而不是整片替换：目标地狱岩自身周围 3×3×3 里只能存在一种菌岩时才会
     * 被转换（与原版“对地狱岩使用骨粉”一致）；两种菌岩都在旁边时保持地狱岩不变。
     * 同一格地狱岩只归属最先处理到的那块菌岩，而每块确实感染到东西的菌岩各计 1u OGE。</p>
     */
    private NyliumPlan planNyliumSpread(ServerLevel level) {
        int r = radius();
        Map<BlockPos, Block> conversions = new LinkedHashMap<>();
        Set<BlockPos> spreadingSources = new HashSet<>();
        for (BlockPos source : BlockPos.betweenClosed(
                worldPosition.getX() - r, worldPosition.getY() - r, worldPosition.getZ() - r,
                worldPosition.getX() + r, worldPosition.getY() + r, worldPosition.getZ() + r)) {
            Block sourceNylium = nyliumAt(level, source);
            if (sourceNylium == null) {
                continue;
            }
            // 这块菌岩只影响它自己身边 3×3×3 的范围。
            int sr = NYLIUM_SPREAD_RADIUS;
            for (BlockPos target : BlockPos.betweenClosed(
                    source.getX() - sr, source.getY() - sr, source.getZ() - sr,
                    source.getX() + sr, source.getY() + sr, source.getZ() + sr)) {
                if (conversions.containsKey(target)) {
                    continue;
                }
                if (!isSpreadableNetherrack(level, target)) {
                    continue;
                }
                // 目标自身周围只能有一种菌岩，且正是这块源菌岩的种类。
                if (uniqueNyliumAround(level, target) != sourceNylium) {
                    continue;
                }
                conversions.put(target.immutable(), sourceNylium);
                spreadingSources.add(source.immutable());
            }
        }
        return new NyliumPlan(conversions, spreadingSources.size());
    }

    /** {@code pos} 处是哪一种菌岩；不是菌岩时返回 {@code null}。 */
    private static Block nyliumAt(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.is(Blocks.CRIMSON_NYLIUM)) {
            return Blocks.CRIMSON_NYLIUM;
        }
        if (state.is(Blocks.WARPED_NYLIUM)) {
            return Blocks.WARPED_NYLIUM;
        }
        return null;
    }

    /** 这块地狱岩是否顶面朝天、可以被菌岩蔓延。 */
    private static boolean isSpreadableNetherrack(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).is(Blocks.NETHERRACK)
                && level.getBlockState(pos.above()).isAir();
    }

    /**
     * {@code pos} 周围 3×3×3 内唯一存在的菌岩种类；两种同时存在或一种都没有时返回 {@code null}
     * （条件与原版“对地狱岩使用骨粉”一致）。
     */
    private static Block uniqueNyliumAround(ServerLevel level, BlockPos pos) {
        int x = pos.getX();
        int y = pos.getY();
        int z = pos.getZ();
        boolean crimson = false;
        boolean warped = false;
        for (BlockPos near : BlockPos.betweenClosed(x - 1, y - 1, z - 1, x + 1, y + 1, z + 1)) {
            BlockState nearState = level.getBlockState(near);
            if (nearState.is(Blocks.CRIMSON_NYLIUM)) {
                crimson = true;
            } else if (nearState.is(Blocks.WARPED_NYLIUM)) {
                warped = true;
            }
            if (crimson && warped) {
                return null;
            }
        }
        if (crimson) {
            return Blocks.CRIMSON_NYLIUM;
        }
        return warped ? Blocks.WARPED_NYLIUM : null;
    }

    /**
     * 给范围内可随机刻的方块补足随机刻。
     *
     * <p>自然随机刻在一格体积内每 tick 的期望次数约为
     * {@code volume × randomTickSpeed / 4096}；这里按同样的期望额外准备
     * {@code volume × 110 × randomTickSpeed / 4096} 次随机刻机会，每次机会有 90%
     * 概率真正生效，净增约 99 倍，加上原有的 1 倍正好是需求所说的
     * “100 倍生长速度”。采样次数不足 1 时先用小数预算累积，避免小范围、
     * 低随机刻速度下取整后完全不触发。</p>
     *
     * <p><b>预算上限</b>：换算用的随机刻速度最多按
     * {@link #MAX_BUDGET_RANDOM_TICK_SPEED} 算，攒下的小数预算也最多攒到
     * {@code volume}（范围内每格一次）。所以把 {@code randomTickSpeed} 调到 20 以内
     * 机器都是完整的约 100 倍；超过 20 之后额外机会不再增加，机器自己的加速能力就此封顶。</p>
     */
    private void accelerateRandomTicks(ServerLevel level) {
        int r = radius();
        int side = r * 2 + 1;
        double volume = (double) side * side * side;
        int randomTickSpeed = level.getGameRules().get(GameRules.RANDOM_TICK_SPEED);
        if (randomTickSpeed <= 0) {
            return;
        }
        // 预算封顶：随机刻速度最多按上限算，机器不会跟着 gamerule 无限变强。
        int budgetSpeed = Math.min(randomTickSpeed, MAX_BUDGET_RANDOM_TICK_SPEED);
        extraRandomTickBudget += volume * EXTRA_RANDOM_TICK_FACTOR * budgetSpeed / RANDOM_TICK_POSITIONS;
        // 兜底上限：单次预算最多攒到“范围内每格一次”，极端设置下也不会一口气点上过多次。
        if (extraRandomTickBudget > volume) {
            extraRandomTickBudget = volume;
        }
        int samples = (int) extraRandomTickBudget;
        if (samples <= 0) {
            return;
        }
        extraRandomTickBudget -= samples;
        if (samples > (int) volume) {
            samples = (int) volume;
        }
        RandomSource random = level.getRandom();
        for (int i = 0; i < samples; i++) {
            // 90% 的概率额外进行一次随机刻。
            if (random.nextFloat() >= EXTRA_RANDOM_TICK_CHANCE) {
                continue;
            }
            BlockPos pos = worldPosition.offset(
                    random.nextInt(side) - r,
                    random.nextInt(side) - r,
                    random.nextInt(side) - r);
            if (!level.isLoaded(pos)) {
                continue;
            }
            BlockState state = level.getBlockState(pos);
            if (state.isRandomlyTicking()) {
                state.randomTick(level, pos, random);
            }
        }
    }

    /** 这个方块是否算作“催熟 + 变草”的处理目标：还长得动的随机刻方块，或顶面暴露在空气中的泥土。 */
    private static boolean isWorkable(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.is(Blocks.DIRT) && level.getBlockState(pos.above()).isAir()) {
            return true;
        }
        if (!state.isRandomlyTicking()) {
            return false;
        }
        return canStillGrow(level, pos, state);
    }

    /**
     * 可随机刻的方块里，判断它<b>还能不能生长</b>——已经长到头、随机刻不会再让它长大的方块
     * 不该继续算作目标、继续消耗骨粉。
     *
     * <p>这里只认原版自己的“能不能吃骨粉”标准，而不是笼统地“只要会被随机刻选中就算活着”。
     * 后者会把大量只是随机发生环境变化的方块（最典型的就是种土豆的<b>耕地</b>：它永远可随机刻，
     * 但随机刻只会让它变干/变回泥土，根本不吃骨粉）也算成目标，于是作物收完后机器仍一直扣骨粉。</p>
     *
     * <ul>
     *   <li><b>地表方块（草方块、菌丝、菌岩）</b>：本机把泥土铺出来的，随机刻只会原地蔓延或退化；
     *       菌岩的扩散由 {@link #planNyliumSpread} 单独计费，重复计数正是“永动机”的根源。</li>
     *   <li><b>甘蔗/仙人掌</b>：骨粉催不动，只有整根柱子的最上一节会继续长，且长到 3 格就到顶；
     *       此时整根柱子都不再算目标。</li>
     *   <li><b>其余方块</b>：一律要求它是 {@link BonemealableBlock} 且“还没长到头”
     *       （{@code isValidBonemealTarget}）。成熟的小麦、土豆等在这里返回 {@code false}；
     *       耕地、树叶、珊瑚、火、雪等压根不是骨粉可催方块，直接排除。</li>
     * </ul>
     */
    private static boolean canStillGrow(ServerLevel level, BlockPos pos, BlockState state) {
        Block block = state.getBlock();
        if (block == Blocks.GRASS_BLOCK
                || block == Blocks.MYCELIUM
                || block == Blocks.CRIMSON_NYLIUM
                || block == Blocks.WARPED_NYLIUM) {
            return false;
        }
        if (block == Blocks.SUGAR_CANE || block == Blocks.CACTUS) {
            // 不是这根柱子的最上面一节，或者顶上已经被挡住：随机刻都不会有效果。
            if (!level.getBlockState(pos.above()).isAir()) {
                return false;
            }
            int height = 1;
            while (height < MAX_VERTICAL_PLANT_HEIGHT
                    && level.getBlockState(pos.below(height)).is(block)) {
                height++;
            }
            return height < MAX_VERTICAL_PLANT_HEIGHT;
        }
        // 只认原版“它还能不能再吃骨粉”：等价于“还没长到头”。
        return block instanceof BonemealableBlock growable
                && growable.isValidBonemealTarget(level, pos, state);
    }

    /** 当前档位下标（0~3）。 */
    private int tierMode() {
        BlockState state = getBlockState();
        return state.hasProperty(OGEKelaMachineBlock.MODE)
                ? state.getValue(OGEKelaMachineBlock.MODE)
                : OGEKelaMachineBlock.DEFAULT_MODE;
    }

    /** 当前档位对应的作用半径。 */
    private int radius() {
        return OGEKelaMachineBlock.radiusForMode(tierMode());
    }
}
