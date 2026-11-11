package cn.origolds.ia;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 闪亮系列产物（闪亮碎粒 / 闪亮碎片 / 闪亮碎块）的数量分布与随机矿物权重表。
 *
 * <p>金源筛洗机与金源粉碎机共用：</p>
 * <ul>
 *   <li>筛洗沙砾/黏土的产物数量：25%/50%/25% → 3/4/5 个；</li>
 *   <li>粉碎黑曜石的产物数量：10%/20%/40%/20%/10% → 6/7/8/9/10 个，
 *       每个 25% 碎粒、25% 碎片、50% 碎块；</li>
 *   <li>把闪亮物品再筛洗成随机矿物时，按下面三档权重表掷骰。
 *       权重表数值按用户需求原样使用，概率 = 权重 / 表内权重和。</li>
 * </ul>
 */
public final class OGEShinyLoot {

    private OGEShinyLoot() {
    }

    /** 矿物候选：煤炭、粗铜、粗铁、红石、青金石、粗金、钻石、绿宝石。 */
    private static final Item[] MINERALS = {
            Items.COAL, Items.RAW_COPPER, Items.RAW_IRON, Items.REDSTONE,
            Items.LAPIS_LAZULI, Items.RAW_GOLD, Items.DIAMOND, Items.EMERALD
    };

    /** 闪亮碎粒权重：煤 299700｜铜 249800｜铁 199800｜红石 119900｜青金 79900｜金 50000｜钻 694｜绿宝 300。 */
    private static final int[] CRUMB_WEIGHTS = {
            299_700, 249_800, 199_800, 119_900, 79_900, 50_000, 694, 300
    };

    /** 闪亮碎片权重：煤 298000｜铜 238400｜铁 198700｜红石 119200｜青金 79500｜金 59600｜钻 4167｜绿宝 2500。 */
    private static final int[] SHARD_WEIGHTS = {
            298_000, 238_400, 198_700, 119_200, 79_500, 59_600, 4_167, 2_500
    };

    /** 闪亮碎块权重：煤 238800｜铜 191000｜铁 171900｜红石 143300｜青金 114600｜金 95500｜钻 25000｜绿宝 20000。 */
    private static final int[] CHUNK_WEIGHTS = {
            238_800, 191_000, 171_900, 143_300, 114_600, 95_500, 25_000, 20_000
    };

    /** 筛洗沙砾/黏土的产物数量：25%/50%/25% → 3/4/5。 */
    public static int rollSiftCount(RandomSource rand) {
        int r = rand.nextInt(100);
        return r < 25 ? 3 : r < 75 ? 4 : 5;
    }

    /** 粉碎黑曜石的产物数量：10%/20%/40%/20%/10% → 6/7/8/9/10。 */
    public static int rollObsidianCount(RandomSource rand) {
        int r = rand.nextInt(100);
        if (r < 10) {
            return 6;
        }
        if (r < 30) {
            return 7;
        }
        if (r < 70) {
            return 8;
        }
        if (r < 90) {
            return 9;
        }
        return 10;
    }

    /** 筛洗黏土的产物：count 个物品，每个 50% 碎粒、50% 碎片，同种合并成堆。 */
    public static List<ItemStack> makeClayOutputs(int count, RandomSource rand) {
        int crumbs = 0;
        int shards = 0;
        for (int i = 0; i < count; i++) {
            if (rand.nextBoolean()) {
                crumbs++;
            } else {
                shards++;
            }
        }
        List<ItemStack> out = new ArrayList<>(2);
        if (crumbs > 0) {
            out.add(new ItemStack(ModBlocks.SHINY_CRUMB.get(), crumbs));
        }
        if (shards > 0) {
            out.add(new ItemStack(ModBlocks.SHINY_SHARD.get(), shards));
        }
        return out;
    }

    /** 筛洗沙砾的产物：count 个闪亮碎粒（单堆）。 */
    public static List<ItemStack> makeGravelOutputs(int count) {
        List<ItemStack> out = new ArrayList<>(1);
        if (count > 0) {
            out.add(new ItemStack(ModBlocks.SHINY_CRUMB.get(), count));
        }
        return out;
    }

    /** 粉碎黑曜石的产物：count 个物品，每个 25% 碎粒、25% 碎片、50% 碎块，同种合并成堆。 */
    public static List<ItemStack> makeObsidianOutputs(int count, RandomSource rand) {
        int crumbs = 0;
        int shards = 0;
        int chunks = 0;
        for (int i = 0; i < count; i++) {
            int r = rand.nextInt(100);
            if (r < 25) {
                crumbs++;
            } else if (r < 50) {
                shards++;
            } else {
                chunks++;
            }
        }
        List<ItemStack> out = new ArrayList<>(3);
        if (crumbs > 0) {
            out.add(new ItemStack(ModBlocks.SHINY_CRUMB.get(), crumbs));
        }
        if (shards > 0) {
            out.add(new ItemStack(ModBlocks.SHINY_SHARD.get(), shards));
        }
        if (chunks > 0) {
            out.add(new ItemStack(ModBlocks.SHINY_CHUNK.get(), chunks));
        }
        return out;
    }

    /** 是否为闪亮物品（碎粒/碎片/碎块）。 */
    public static boolean isShiny(ItemStack stack) {
        return stack.is(ModBlocks.SHINY_CRUMB.get())
                || stack.is(ModBlocks.SHINY_SHARD.get())
                || stack.is(ModBlocks.SHINY_CHUNK.get());
    }

    /**
     * 把一个闪亮物品筛洗成 1 个随机矿物（按该物品的权重表掷骰）。
     * 非闪亮物品返回空列表。
     */
    public static List<ItemStack> siftShiny(ItemStack input, RandomSource rand) {
        int[] weights;
        if (input.is(ModBlocks.SHINY_CRUMB.get())) {
            weights = CRUMB_WEIGHTS;
        } else if (input.is(ModBlocks.SHINY_SHARD.get())) {
            weights = SHARD_WEIGHTS;
        } else if (input.is(ModBlocks.SHINY_CHUNK.get())) {
            weights = CHUNK_WEIGHTS;
        } else {
            return List.of();
        }
        int total = 0;
        for (int w : weights) {
            total += w;
        }
        int roll = rand.nextInt(total);
        for (int i = 0; i < weights.length; i++) {
            roll -= weights[i];
            if (roll < 0) {
                return List.of(new ItemStack(MINERALS[i]));
            }
        }
        return List.of(new ItemStack(MINERALS[MINERALS.length - 1])); // 理论上到不了这里
    }
}
