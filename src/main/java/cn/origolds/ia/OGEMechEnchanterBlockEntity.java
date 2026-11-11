package cn.origolds.ia;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Set;

/**
 * 金源自动附魔机（Origold Mech-Enchanter）的方块实体。
 *
 * <p>三个槽位：0、1 是待合并的两件物品，2 是产物。把两件材料放进去后，箭头按钮
 * 确认开始，机器便按当前档位从所在无线电网持续抽取 OGE，直到完成并产出结果。</p>
 *
 * <h2>合并规则（两条路径）</h2>
 * <ol>
 *   <li><b>同等级高等附魔升上限</b>：两件材料拥有同一种附魔且等级相同；<b>至少一件
 *       必须是附魔书</b>，且参与合并的附魔书只能拥有 <b>1 个</b>附魔。把该附魔升 1 级，
 *       最多升到原版上限的 2 倍（如耐久 3 → 最多 6、锋利 5 → 最多 10）。只有当等级
 *       已经达到（或超过）原版上限、且还没到 2 倍上限时才成立，消耗表见
 *       {@link #costTable(int)} / {@link #timeTable(int)}。经验修补、无限、快速装填、
 *       饵钓、灵魂疾行、迅捷潜行、风爆、火矢、精准采集、深海探索者、引雷、绑定诅咒、
 *       消失诅咒不可用这条路径继续堆高。</li>
 *   <li><b>普通铁砧附魔过程</b>：除上面这条以外、任何一件物品 + 一本兼容附魔书
 *       （或两本附魔书）的组合都走这条。产物保留物品原有的其它附魔，把书上的附魔
 *       逐个敲上去：物品上没有的直接获得，已有的取两者较高等级（同等级则再加 1 级，
 *       最高到原版上限的 2 倍）。例如白板铁剑 + 耐久 V 书 → 耐久 V 铁剑；耐久 IV 铁剑
 *       + 耐久 V 书 → 耐久 V 铁剑；效率 IV 耐久 III 钻石镐 + 时运 III 书 → 再获得时运 III。
 *       基础消耗固定 600u、基础耗时 16 秒；若本次敲上去的附魔超过了原版等级上限，
 *       基础耗时改为 160 秒。两者都按档位倍率缩放（4x 档消耗 ×2、耗时 ÷4；
 *       16x 档消耗 ×4、耗时 ÷16）。</li>
 * </ol>
 *
 * <h2>祛魔规则（第二格放空白书）</h2>
 * <p>第二格放入一本没附魔的普通书时改走祛魔，第一格是要祛魔的物件：</p>
 * <ul>
 *   <li><b>物品祛魔</b>：把物品上的附魔全部搬到书上，基础 600u、16 秒（含超过原版上限
 *       的附魔则 160 秒）。物品<b>不会消失</b>，会以祛魔后的白板状态留在槽位 0。</li>
 *   <li><b>附魔书祛魔</b>：把附魔书上的首个附魔（按注册名排序）搬到书上，基础 300u、
 *       8 秒（该附魔超过原版上限则 80 秒）。原附魔书留在槽位 0，仍带其余附魔；
 *       被搬空时还原成普通书。</li>
 * </ul>
 * <p>耗时同样按档位除数缩短。</p>
 *
 * <h2>档位</h2>
 * <p>1x（初始）/ 4x / 16x 三档：4x 消耗翻倍、耗时降到 1/4；16x 消耗变 4 倍、
 * 耗时降到 1/16。档位由方块的 {@link OGEMechEnchanterBlock#MODE} 决定，
 * 方块状态变了就重算显示。</p>
 *
 * <p>所有对外显示的数值（档位、本次预计消耗与耗时、已用时间、是否工作中）都通过
 * {@link ContainerData} 同步给界面。</p>
 */
public final class OGEMechEnchanterBlockEntity extends BaseContainerBlockEntity implements ContainerData {

    /** 槽位数：0、1 输入，2 输出。 */
    public static final int SLOT_COUNT = 3;

    // ------------------------------------------------------------------
    // 各档位与消耗表
    // ------------------------------------------------------------------

    /** 三档的 OGE 消耗倍率（1x / 4x / 16x 档）。 */
    private static final int[] TIER_COST_MULTIPLIER = {1, 2, 4};

    /** 三档的耗时除数（1x / 4x / 16x 档）。 */
    private static final int[] TIER_TIME_DIVISOR = {1, 4, 16};

    /** 一秒游戏刻数，用于把消耗表里的秒换算成刻。 */
    private static final int TICKS_PER_SECOND = 20;

    /** 普通铁砧附魔过程的基础 OGE 消耗（会再乘档位倍率）。 */
    private static final int ANVIL_BASE_COST = 600;

    /** 普通铁砧附魔过程的基础耗时（秒，结果不超过原版上限）。 */
    private static final int ANVIL_BASE_SECONDS = 16;

    /** 普通铁砧附魔过程的基础耗时（秒，敲上去的附魔超过原版上限）。 */
    private static final int ANVIL_OVER_LIMIT_SECONDS = 160;

    /** 祛魔（物品 → 空白书，搬走全部附魔）的基础 OGE 消耗。 */
    private static final int DISENCHANT_ALL_BASE_COST = 600;

    /** 祛魔（物品 → 空白书）的基础耗时（秒，附魔都没超过原版上限）。 */
    private static final int DISENCHANT_ALL_BASE_SECONDS = 16;

    /** 祛魔（物品 → 空白书）的基础耗时（秒，含超过原版上限的附魔）。 */
    private static final int DISENCHANT_ALL_OVER_LIMIT_SECONDS = 160;

    /** 祛魔（附魔书 → 空白书，只搬走首个附魔）的基础 OGE 消耗。 */
    private static final int DISENCHANT_ONE_BASE_COST = 300;

    /** 祛魔（附魔书 → 空白书）的基础耗时（秒，被搬走的附魔没超过原版上限）。 */
    private static final int DISENCHANT_ONE_BASE_SECONDS = 8;

    /** 祛魔（附魔书 → 空白书）的基础耗时（秒，被搬走的附魔超过原版上限）。 */
    private static final int DISENCHANT_ONE_OVER_LIMIT_SECONDS = 80;

    /** 无法合并的附魔（等级堆高后要么有 bug、要么没有作用）。 */
    private static final Set<ResourceKey<Enchantment>> BLOCKED_ENCHANTMENTS = Set.of(
            Enchantments.MENDING,
            Enchantments.INFINITY,
            Enchantments.QUICK_CHARGE,
            Enchantments.LURE,
            Enchantments.SOUL_SPEED,
            Enchantments.SWIFT_SNEAK,
            Enchantments.WIND_BURST,
            Enchantments.FLAME,
            Enchantments.SILK_TOUCH,
            Enchantments.DEPTH_STRIDER,
            Enchantments.CHANNELING,
            Enchantments.BINDING_CURSE,
            Enchantments.VANISHING_CURSE);

    /** 数据槽索引。 */
    public static final int DATA_TIER = 0;
    public static final int DATA_COST = 1;
    public static final int DATA_TICKS = 2;
    public static final int DATA_REMAINING = 3;
    public static final int DATA_WORKING = 4;
    private static final int DATA_COUNT = 5;

    private NonNullList<ItemStack> items = NonNullList.withSize(SLOT_COUNT, ItemStack.EMPTY);

    // ---- 当前任务状态（服务端权威） ----
    private boolean working;
    private int remainingTicks;
    private int totalTicks;
    private int totalCost;
    private int consumedOGE;

    // ---- 对外显示值（ContainerData 同步，客户端只读回填） ----
    private int displayTier;
    private int displayCost;
    private int displayTicks;
    private int displayRemaining;
    private int displayWorking;

    // ---- 待机时的显示计划缓存 ----
    private boolean planDirty = true;
    private int lastPlanTier = -1;
    private int planCost;
    private int planTicks;

    /** 最近一次同步给客户端的展品（槽位 0），用于避免每刻重复发包。 */
    private ItemStack lastSyncedDisplay = ItemStack.EMPTY;

    public OGEMechEnchanterBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.OGE_MECH_ENCHANTER.get(), pos, state);
    }

    // ------------------------------------------------------------------
    // 每刻驱动
    // ------------------------------------------------------------------

    /** 服务端每 tick 调用（方块 ticker 驱动）。 */
    public void tick() {
        if (level == null || level.isClientSide) {
            return;
        }
        if (working) {
            advanceJob();
        } else if (planDirty || lastPlanTier != tierIndex()) {
            refreshPlan();
        }
        syncDisplay();
        syncDisplayStack();
    }

    /**
     * 槽位 0 的展品发生变化时把方块实体数据推给附近客户端。
     *
     * <p>方块实体的物品内容默认不会随方块发到客户端，顶上浮空展品要跟着第一格的
     * 东西走就必须显式同步；这里只在展品真的变了才发包，进行中的作业不会每刻刷屏。</p>
     */
    private void syncDisplayStack() {
        ItemStack current = items.get(0);
        if (ItemStack.matches(current, lastSyncedDisplay)) {
            return;
        }
        lastSyncedDisplay = current.copy();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    /** 推进一次合并：按进度抽取 OGE，电不够就原地暂停。 */
    private void advanceJob() {
        if (remainingTicks <= 0) {
            finishJob();
            return;
        }
        // 本刻应累计消耗到多少（整数刻度，保证走完时恰好等于 totalCost）。
        long target = (long) totalCost * (totalTicks - remainingTicks + 1L) / totalTicks;
        int want = (int) Math.max(0L, target - consumedOGE);
        if (want > 0) {
            if (!(level instanceof ServerLevel serverLevel)) {
                return;
            }
            MinecraftServer server = serverLevel.getServer();
            if (server == null) {
                return;
            }
            int got = OGEWirelessManager.consumeOGEIfAvailable(
                    server, serverLevel.dimension(), worldPosition, want);
            if (got < want) {
                // 电网供不上：本刻暂停，不推进进度，下一仍然重试。
                return;
            }
            consumedOGE += got;
        }
        remainingTicks--;
        if (remainingTicks <= 0) {
            finishJob();
        } else {
            setChanged();
        }
    }

    /** 完成本次作业：合并消耗两个输入，祛魔则保留第一格的物件、消耗第二格的书。 */
    private void finishJob() {
        Plan plan = computePlan(items.get(0), items.get(1), tierIndex());
        if (plan != null && items.get(2).isEmpty()) {
            if (plan.operation() == Operation.MERGE) {
                ItemStack result = buildResult(items.get(0), items.get(1), plan);
                if (!items.get(0).isEmpty()) {
                    items.get(0).shrink(1);
                }
                if (!items.get(1).isEmpty()) {
                    items.get(1).shrink(1);
                }
                items.set(2, result);
            } else {
                applyDisenchant(plan);
            }
        }
        resetJob();
    }

    /**
     * 结算一次祛魔：把搬走的附魔写进输出槽的附魔书，第一格的物件保留在槽位 0、
     * 只去掉被搬走的附魔（祛魔全部时就是一件白板物品），第二格的空白书消耗 1 本。
     */
    private void applyDisenchant(Plan plan) {
        ItemStack source = items.get(0);
        int count = Math.max(1, source.getCount());

        // 附魔书被搬空后还原成普通书，物品被搬空后就是原来的白板物品。
        ItemStack kept = source.copyWithCount(count);
        if (plan.remainder().isEmpty()) {
            kept.remove(EnchantmentHelper.getComponentType(kept));
            if (kept.is(Items.ENCHANTED_BOOK)) {
                kept = new ItemStack(Items.BOOK, count);
            }
        } else {
            EnchantmentHelper.setEnchantments(kept, plan.remainder());
        }
        items.set(0, kept);

        ItemStack book = items.get(1);
        book.shrink(1);
        if (book.isEmpty()) {
            items.set(1, ItemStack.EMPTY);
        }

        ItemStack output = new ItemStack(Items.ENCHANTED_BOOK);
        EnchantmentHelper.setEnchantments(output, plan.result());
        items.set(2, output);
    }

    private void resetJob() {
        working = false;
        remainingTicks = 0;
        totalTicks = 0;
        totalCost = 0;
        consumedOGE = 0;
        planDirty = true;
        setChanged();
    }

    /**
     * 由界面箭头按钮确认开始合并。校验通过才设状态，返回是否真的开始。
     */
    public boolean startJob() {
        if (working || !items.get(2).isEmpty()) {
            return false;
        }
        Plan plan = computePlan(items.get(0), items.get(1), tierIndex());
        if (plan == null) {
            return false;
        }
        working = true;
        totalCost = plan.cost();
        totalTicks = plan.ticks();
        remainingTicks = plan.ticks();
        consumedOGE = 0;
        planDirty = false;
        setChanged();
        return true;
    }

    /** 重算待机时的预计消耗与耗时（仅在输入或档位变化后调用）。 */
    private void refreshPlan() {
        Plan plan = computePlan(items.get(0), items.get(1), tierIndex());
        planCost = plan == null ? 0 : plan.cost();
        planTicks = plan == null ? 0 : plan.ticks();
        lastPlanTier = tierIndex();
        planDirty = false;
    }

    /** 把权威任务状态写进对外显示值（ContainerData 每 tick 对比后自动同步）。 */
    private void syncDisplay() {
        displayTier = tierIndex();
        if (working) {
            displayCost = totalCost;
            displayTicks = totalTicks;
            displayRemaining = remainingTicks;
            displayWorking = 1;
        } else {
            displayCost = planCost;
            displayTicks = planTicks;
            displayRemaining = 0;
            displayWorking = 0;
        }
    }

    @Override
    public void setChanged() {
        super.setChanged();
        planDirty = true;
    }

    // ------------------------------------------------------------------
    // 合并规则
    // ------------------------------------------------------------------

    /**
     * 计算一次作业计划；无法作业时返回 {@code null}。
     *
     * <p>先判断“祛魔”（第一格是被祛魔的物件、第二格是空白书），其次尝试“同等级高等附魔
     * 升上限”，最后退回“普通铁砧附魔过程”。</p>
     *
     * @param a    槽位 1 的物品
     * @param b    槽位 2 的物品
     * @param tier 档位下标（0/1/2）
     */
    public static Plan computePlan(ItemStack a, ItemStack b, int tier) {
        if (a.isEmpty() || b.isEmpty()) {
            return null;
        }
        int safeTier = Math.max(0, Math.min(TIER_COST_MULTIPLIER.length - 1, tier));
        int baseCost = TIER_COST_MULTIPLIER[safeTier];
        int timeDivisor = TIER_TIME_DIVISOR[safeTier];

        // 祛魔优先：第二格是没附魔的空白书时，语义就是“把附魔搬到书上”。
        Plan disenchant = computeDisenchantPlan(a, b, baseCost, timeDivisor);
        if (disenchant != null) {
            return disenchant;
        }

        boolean aBook = a.is(Items.ENCHANTED_BOOK);
        boolean bBook = b.is(Items.ENCHANTED_BOOK);
        // 至少一件必须是附魔书（物品 + 物品不能合并）。
        if (!aBook && !bBook) {
            return null;
        }

        Plan overCap = computeOverCapPlan(a, b, aBook, bBook, baseCost, timeDivisor);
        if (overCap != null) {
            return overCap;
        }
        return computeAnvilPlan(a, b, aBook, bBook, baseCost, timeDivisor);
    }

    /**
     * 祛魔：第一格放要祛魔的物件，第二格放没附魔的空白书。
     *
     * <ul>
     *   <li><b>物品 → 书</b>：把物品上的附魔全部搬到书里，基础 600u、16 秒；只要有一项
     *       附魔超过原版上限，基础耗时改为 160 秒。物品本身保留在槽位 0（变成白板）。</li>
     *   <li><b>附魔书 → 书</b>：把附魔书上的首个附魔搬到空白书里，基础 300u、8 秒；
     *       被搬走的这项附魔超过原版上限时基础耗时改为 80 秒。原附魔书留在槽位 0，
     *       仍带着其余的附魔（搬空则还原成普通书）。</li>
     * </ul>
     *
     * <p>“首个附魔”按附魔注册名排序取第一个，保证同一本书每次结果一致。</p>
     */
    private static Plan computeDisenchantPlan(ItemStack source, ItemStack book,
                                              int costMultiplier, int timeDivisor) {
        // 第二格必须是“空白书”：普通书，且本身没有附魔。
        if (!book.is(Items.BOOK) || !EnchantmentHelper.getEnchantmentsForCrafting(book).isEmpty()) {
            return null;
        }
        ItemEnchantments enchant = EnchantmentHelper.getEnchantmentsForCrafting(source);
        if (enchant.isEmpty()) {
            return null;
        }

        boolean sourceIsBook = source.is(Items.ENCHANTED_BOOK);
        ItemEnchantments moved;
        ItemEnchantments remainder;
        int baseCost;
        int baseSeconds;
        if (sourceIsBook) {
            Holder<Enchantment> first = firstEnchantment(enchant);
            if (first == null) {
                return null;
            }
            int level = enchant.getLevel(first);
            ItemEnchantments.Mutable movedMut = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
            movedMut.set(first, level);
            moved = movedMut.toImmutable();

            ItemEnchantments.Mutable restMut = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
            for (Holder<Enchantment> holder : enchant.keySet()) {
                if (!holder.equals(first)) {
                    restMut.set(holder, enchant.getLevel(holder));
                }
            }
            remainder = restMut.toImmutable();

            baseCost = DISENCHANT_ONE_BASE_COST;
            baseSeconds = level > first.value().getMaxLevel()
                    ? DISENCHANT_ONE_OVER_LIMIT_SECONDS
                    : DISENCHANT_ONE_BASE_SECONDS;
        } else {
            moved = enchant;
            remainder = ItemEnchantments.EMPTY;
            baseCost = DISENCHANT_ALL_BASE_COST;
            baseSeconds = hasOverLimit(enchant)
                    ? DISENCHANT_ALL_OVER_LIMIT_SECONDS
                    : DISENCHANT_ALL_BASE_SECONDS;
        }

        int cost = baseCost * costMultiplier;
        int ticks = Math.max(1,
                (int) Math.round(baseSeconds * (double) TICKS_PER_SECOND / timeDivisor));
        Operation operation = sourceIsBook ? Operation.DISENCHANT_ONE : Operation.DISENCHANT_ALL;
        return new Plan(operation, moved, remainder, cost, ticks);
    }

    /** 附魔表里是否含有超过原版等级上限的附魔。 */
    private static boolean hasOverLimit(ItemEnchantments enchantments) {
        for (Holder<Enchantment> holder : enchantments.keySet()) {
            if (enchantments.getLevel(holder) > holder.value().getMaxLevel()) {
                return true;
            }
        }
        return false;
    }

    /** 取附魔表里的“首个附魔”（按附魔注册名排序，取字典序最小的一项）。 */
    private static Holder<Enchantment> firstEnchantment(ItemEnchantments enchantments) {
        Holder<Enchantment> best = null;
        String bestId = null;
        for (Holder<Enchantment> holder : enchantments.keySet()) {
            if (enchantments.getLevel(holder) <= 0) {
                continue;
            }
            String id = holder.unwrapKey()
                    .map(key -> key.location().toString())
                    .orElse("");
            if (best == null || id.compareTo(bestId) < 0) {
                best = holder;
                bestId = id;
            }
        }
        return best;
    }

    /**
     * 路径一：同等级高等附魔升上限（消耗 / 耗时查表，随档位缩放）。
     */
    private static Plan computeOverCapPlan(ItemStack a, ItemStack b, boolean aBook, boolean bBook,
                                           int costMultiplier, int timeDivisor) {
        ItemEnchantments enchantA = EnchantmentHelper.getEnchantmentsForCrafting(a);
        ItemEnchantments enchantB = EnchantmentHelper.getEnchantmentsForCrafting(b);
        // 参与合并的附魔书只支持“只拥有 1 个附魔”的附魔书。
        if ((aBook && enchantA.size() != 1) || (bBook && enchantB.size() != 1)) {
            return null;
        }
        // 找同一种且同等级的附魔。
        Holder<Enchantment> found = null;
        int level = 0;
        for (Holder<Enchantment> holder : enchantA.keySet()) {
            int levelA = enchantA.getLevel(holder);
            if (levelA > 0 && enchantB.getLevel(holder) == levelA) {
                found = holder;
                level = levelA;
                break;
            }
        }
        if (found == null) {
            return null;
        }
        ResourceKey<Enchantment> key = found.unwrapKey().orElse(null);
        if (key == null || BLOCKED_ENCHANTMENTS.contains(key)) {
            return null;
        }
        int maxLevel = found.value().getMaxLevel();
        int[] costs = costTable(maxLevel);
        int[] times = timeTable(maxLevel);
        if (costs == null || times == null) {
            return null;
        }
        // 只能从原版上限继续往上合并，最多到 2 倍上限。
        int index = level - maxLevel;
        if (index < 0 || index >= costs.length) {
            return null;
        }
        // 产物以非附魔书那一侧为底，保留它原有的其它附魔；两本都是书则新建一本。
        ItemStack base = aBook ? b : a;
        ItemEnchantments.Mutable out =
                new ItemEnchantments.Mutable(EnchantmentHelper.getEnchantmentsForCrafting(base));
        out.set(found, level + 1);
        int cost = costs[index] * costMultiplier;
        int ticks = Math.max(1,
                (int) Math.round(times[index] * (double) TICKS_PER_SECOND / timeDivisor));
        return new Plan(Operation.MERGE, out.toImmutable(), null, cost, ticks);
    }

    /**
     * 路径二：普通铁砧附魔过程（基础 600u、基础 16s / 160s，随档位缩放）。
     *
     * <p>把附魔书那一侧的附魔逐个敲到另一侧：物品上已有的取较高等级（同等级则再加 1 级，
     * 最高到 2 倍原版上限），没有的直接获得；与目标物品不兼容、或与已有附魔互斥的附魔
     * 会被跳过。只有确实让至少一项附魔变高时才成立。</p>
     */
    private static Plan computeAnvilPlan(ItemStack a, ItemStack b, boolean aBook, boolean bBook,
                                         int costMultiplier, int timeDivisor) {
        // 产物/目标是非附魔书那一侧；两本都是书时以 b 为目标。
        ItemStack target = aBook ? b : a;
        ItemStack book = aBook ? a : b;
        boolean targetIsBook = target.is(Items.ENCHANTED_BOOK);
        ItemEnchantments targetEnch = EnchantmentHelper.getEnchantmentsForCrafting(target);
        ItemEnchantments bookEnch = EnchantmentHelper.getEnchantmentsForCrafting(book);

        ItemEnchantments.Mutable out = new ItemEnchantments.Mutable(targetEnch);
        Set<Holder<Enchantment>> present = new java.util.HashSet<>(targetEnch.keySet());
        boolean changed = false;
        boolean overLimit = false;
        for (Holder<Enchantment> holder : bookEnch.keySet()) {
            int bookLevel = bookEnch.getLevel(holder);
            if (bookLevel <= 0) {
                continue;
            }
            // 附魔书上的附魔不是都能敲到书上：物品要看能不能接受这个附魔。
            if (!targetIsBook && !holder.value().canEnchant(target)) {
                continue;
            }
            // 与目标物品已有附魔互斥的（例如精准采集 vs 时运）跳过。
            if (!isCompatibleWithAll(present, holder)) {
                continue;
            }
            int current = out.getLevel(holder);
            int maxLevel = holder.value().getMaxLevel();
            // 同等级且已达原版上限时，属于“路径一”的升上限合并；能走到这里说明材料不符合
            // 路径一（例如一本多附魔书），此时不再额外升 1 级，避免绕过路径一的消耗表。
            if (current > 0 && current == bookLevel && current >= maxLevel) {
                continue;
            }
            int merged = current <= 0
                    ? bookLevel
                    : (current == bookLevel ? current + 1 : Math.max(current, bookLevel));
            // 与原版一样封顶到 2 倍上限，避免出现离谱等级。
            merged = Math.min(merged, maxLevel * 2);
            // 被列为不可继续堆高的附魔只允许“普通方式”（不超过原版上限）应用。
            if (merged > maxLevel) {
                ResourceKey<Enchantment> key = holder.unwrapKey().orElse(null);
                if (key == null || BLOCKED_ENCHANTMENTS.contains(key)) {
                    continue;
                }
            }
            if (merged <= current) {
                continue;
            }
            out.set(holder, merged);
            present.add(holder);
            changed = true;
            if (merged > maxLevel) {
                overLimit = true;
            }
        }
        if (!changed) {
            return null;
        }
        int cost = ANVIL_BASE_COST * costMultiplier;
        int seconds = overLimit ? ANVIL_OVER_LIMIT_SECONDS : ANVIL_BASE_SECONDS;
        int ticks = Math.max(1,
                (int) Math.round(seconds * (double) TICKS_PER_SECOND / timeDivisor));
        return new Plan(Operation.MERGE, out.toImmutable(), null, cost, ticks);
    }

    /**
     * 判断某个附魔能否与已有附魔共存。
     *
     * <p>不能直接用 {@link EnchantmentHelper#isEnchantmentCompatible}：那个方法把“同一种
     * 附魔”也算作不兼容（它用于给物品筛选新附魔），而这里恰恰要允许同种附魔继续叠加。</p>
     */
    private static boolean isCompatibleWithAll(Set<Holder<Enchantment>> present, Holder<Enchantment> candidate) {
        for (Holder<Enchantment> existing : present) {
            if (existing.equals(candidate)) {
                continue;
            }
            if (!Enchantment.areCompatible(existing, candidate)) {
                return false;
            }
        }
        return true;
    }

    /** 按原版上限取每级 OGE 消耗表（下标 0 = 上限级 → 上限+1 级）。 */
    private static int[] costTable(int maxLevel) {
        return switch (maxLevel) {
            case 1 -> new int[]{30000};
            case 2 -> new int[]{9375, 18750};
            case 3 -> new int[]{4075, 8150, 12400};
            case 4 -> new int[]{2015, 4030, 6045, 8170};
            case 5 -> new int[]{1050, 2100, 3150, 4200, 5400};
            default -> null;
        };
    }

    /** 按原版上限取每级耗时表（秒，下标与 {@link #costTable(int)} 一致）。 */
    private static int[] timeTable(int maxLevel) {
        return switch (maxLevel) {
            case 1 -> new int[]{4800};
            case 2 -> new int[]{1500, 3000};
            case 3 -> new int[]{652, 1304, 1984};
            case 4 -> new int[]{322, 645, 968, 1308};
            case 5 -> new int[]{168, 336, 504, 672, 864};
            default -> null;
        };
    }

    /** 构造产物：两本附魔书出新的附魔书，物品 + 附魔书则保留原物品（带原有其它附魔）。 */
    private static ItemStack buildResult(ItemStack a, ItemStack b, Plan plan) {
        boolean aBook = a.is(Items.ENCHANTED_BOOK);
        boolean bBook = b.is(Items.ENCHANTED_BOOK);
        ItemStack result;
        if (aBook && bBook) {
            result = new ItemStack(Items.ENCHANTED_BOOK);
        } else {
            ItemStack source = aBook ? b : a;
            result = source.copyWithCount(1);
        }
        EnchantmentHelper.setEnchantments(result, plan.result());
        return result;
    }

    /** 把秒数格式化成 {@code xx:xx:xx}（时/分/秒）。 */
    public static String formatDuration(int totalSeconds) {
        int seconds = Math.max(0, totalSeconds);
        int hours = seconds / 3600;
        int minutes = (seconds % 3600) / 60;
        int secs = seconds % 60;
        return String.format(java.util.Locale.ROOT, "%02d:%02d:%02d", hours, minutes, secs);
    }

    /** 一次作业的种类：合并 / 物品祛魔 / 附魔书祛魔。 */
    public enum Operation {
        /** 两件材料合并成一件产物，两个输入各消耗 1。 */
        MERGE,
        /** 物品祛魔：物品上的附魔全部搬到空白书上，物品保留。 */
        DISENCHANT_ALL,
        /** 附魔书祛魔：附魔书上的首个附魔搬到空白书上，原书保留其余附魔。 */
        DISENCHANT_ONE
    }

    /**
     * 一次作业的计划。
     *
     * @param operation 作业种类
     * @param result    输出槽产物的完整附魔表（合并产物 / 被搬到书上的附魔）
     * @param remainder 祛魔后留在槽位 0 的附魔表（合并时为 {@code null}）
     * @param cost      总 OGE 消耗
     * @param ticks     总刻数
     */
    public record Plan(Operation operation, ItemEnchantments result, ItemEnchantments remainder,
                       int cost, int ticks) {
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    /** 顶上的浮空展品：取槽位 0 的物品（空着时由渲染器回落到小金块）。 */
    public ItemStack getDisplayStack() {
        return items.get(0);
    }

    /** 当前档位下标（1x=0 / 4x=1 / 16x=2）。 */
    public int tierIndex() {
        BlockState state = getBlockState();
        return state.hasProperty(OGEMechEnchanterBlock.MODE)
                ? state.getValue(OGEMechEnchanterBlock.MODE)
                : OGEMechEnchanterBlock.TIER_INDEX_1X;
    }

    public boolean isWorking() {
        return working;
    }

    public int remainingTicks() {
        return remainingTicks;
    }

    public int totalTicks() {
        return totalTicks;
    }

    /** 待机时当前输入的预计消耗（无效组合为 0）。 */
    public int planCost() {
        return planCost;
    }

    /** 待机时当前输入的预计耗时刻数（无效组合为 0）。 */
    public int planTicks() {
        return planTicks;
    }

    // ------------------------------------------------------------------
    // Container / MenuProvider
    // ------------------------------------------------------------------

    @Override
    public int getContainerSize() {
        return SLOT_COUNT;
    }

    @Override
    protected NonNullList<ItemStack> getItems() {
        return items;
    }

    @Override
    protected void setItems(NonNullList<ItemStack> newItems) {
        this.items = newItems;
    }

    @Override
    public boolean canPlaceItem(int index, ItemStack stack) {
        // 输出槽不可放入；合并进行中两个输入槽也锁住，避免进度里的材料被换走。
        return index < 2 && !working;
    }

    @Override
    protected Component getDefaultName() {
        return Component.translatable(getBlockState().getBlock().getDescriptionId());
    }

    @Override
    protected AbstractContainerMenu createMenu(int containerId, Inventory playerInventory) {
        return new OGEMechEnchanterMenu(containerId, playerInventory, worldPosition);
    }

    // ------------------------------------------------------------------
    // ContainerData：把显示值同步给菜单 UI
    // ------------------------------------------------------------------

    @Override
    public int getCount() {
        return DATA_COUNT;
    }

    @Override
    public int get(int index) {
        return switch (index) {
            case DATA_TIER -> displayTier;
            case DATA_COST -> displayCost;
            case DATA_TICKS -> displayTicks;
            case DATA_REMAINING -> displayRemaining;
            case DATA_WORKING -> displayWorking;
            default -> 0;
        };
    }

    @Override
    public void set(int index, int value) {
        switch (index) {
            case DATA_TIER -> displayTier = value;
            case DATA_COST -> displayCost = value;
            case DATA_TICKS -> displayTicks = value;
            case DATA_REMAINING -> displayRemaining = value;
            case DATA_WORKING -> displayWorking = value;
            default -> {
            }
        }
    }

    // ------------------------------------------------------------------
    // 持久化
    // ------------------------------------------------------------------

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        ContainerHelper.saveAllItems(tag, items, registries);
        tag.putBoolean("Working", working);
        tag.putInt("RemainingTicks", remainingTicks);
        tag.putInt("TotalTicks", totalTicks);
        tag.putInt("TotalCost", totalCost);
        tag.putInt("ConsumedOGE", consumedOGE);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        this.items = NonNullList.withSize(SLOT_COUNT, ItemStack.EMPTY);
        ContainerHelper.loadAllItems(tag, items, registries);
        working = tag.getBoolean("Working");
        remainingTicks = tag.getInt("RemainingTicks");
        totalTicks = tag.getInt("TotalTicks");
        totalCost = tag.getInt("TotalCost");
        consumedOGE = tag.getInt("ConsumedOGE");
        planDirty = true;
        lastPlanTier = -1;
        lastSyncedDisplay = items.get(0).copy();
    }

    /**
     * 发往客户端的方块实体数据：带上三个槽位（渲染器只关心槽位 0 的展品）。
     * 基类默认返回空标签，不显式写就没有任何内容可同步。
     */
    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = super.getUpdateTag(registries);
        ContainerHelper.saveAllItems(tag, items, registries);
        return tag;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
