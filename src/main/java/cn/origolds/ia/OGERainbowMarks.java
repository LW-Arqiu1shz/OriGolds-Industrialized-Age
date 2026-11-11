package cn.origolds.ia;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 虹彩印记（Rainbow Mark）的公共定义：三档倍率、可贴方块的判定与换算。
 *
 * <p>档位下标 {@code 0/1/2} 分别是 5x / 20x / 100x；方块实体上另用 {@code -1}
 * 表示“没贴印记”。每贴一档，机器的工作速度按 {@link #speedMultiplier(int) 速度倍率}
 * 提升，但同样的燃料 / OGE 能产出的东西按 {@link #resourceMultiplier(int) 资源倍率}
 * 缩水——也就是“为了同样的工作要多烧几倍资源”。</p>
 *
 * <p>原料消耗是两边一起乘的：<b>总消耗倍率 = 速度倍率 × 资源倍率</b>。100x 档的机器
 * 产电速度是平时的 100 倍，燃料也按 100 × 4 = 400 倍的速率被烧掉，所以同一块煤的总
 * 发电量只剩 1/4。资源发生器同理：每秒多产 100 倍的方块，每一块的 OGE 消耗也变成 4 倍。</p>
 *
 * <p>火力发电机、魔力发电机的换算举例（一块煤 80 秒、共 800u OGE，基准 10u/s）：</p>
 * <ul>
 *   <li>5x：50u/s 烧 16 秒，共 800u（总消耗 ×5）；</li>
 *   <li>20x：200u/s 烧 2 秒，共 400u（总消耗 ×40）；</li>
 *   <li>100x：1000u/s 烧 0.2 秒，共 200u（总消耗 ×400）。</li>
 * </ul>
 */
public final class OGERainbowMarks {

    /** 档位数量：5x / 20x / 100x。 */
    public static final int TIER_COUNT = 3;

    /** 默认档位：5x。 */
    public static final int DEFAULT_TIER = 0;

    /** 各档的工作速度倍率。 */
    private static final int[] SPEED_MULTIPLIER = {5, 20, 100};

    /** 各档的生产资源（燃料 / 魔金之核 / 金源能量）倍率。 */
    private static final int[] RESOURCE_MULTIPLIER = {1, 2, 4};

    /** 各档的显示标签。 */
    private static final String[] LABELS = {"5x", "20x", "100x"};

    private OGERainbowMarks() {
    }

    /** 把档位下标夹到 0~2。 */
    public static int clampTier(int tier) {
        return Math.max(0, Math.min(TIER_COUNT - 1, tier));
    }

    /** 工作速度倍率；{@code tier < 0}（没贴）时是 1。 */
    public static int speedMultiplier(int tier) {
        return tier < 0 ? 1 : SPEED_MULTIPLIER[clampTier(tier)];
    }

    /** 生产资源消耗倍率；{@code tier < 0}（没贴）时是 1。 */
    public static int resourceMultiplier(int tier) {
        return tier < 0 ? 1 : RESOURCE_MULTIPLIER[clampTier(tier)];
    }

    /** 档位显示标签（5x / 20x / 100x）。 */
    public static String label(int tier) {
        return LABELS[clampTier(tier)];
    }

    /**
     * 方块被玩家挖掉时的“返还”：贴着印记的机器会掉 1 个魔金之核。
     *
     * <p>贴一次印记只扣 1 点耐久，等价于消耗了 1 个魔金之核，所以机器连同印记一起
     * 消失时要把这份投入还回来；印记本身不再掉落（它还在玩家手里，掉出来会变成复制）。
     * 没贴印记、或父模组没装（拿不到魔金之核）时什么都不做。</p>
     *
     * @param level      方块所在世界（只在服务端执行掉落）
     * @param pos        被挖掉的方块坐标
     * @param blockEntity 该坐标上的方块实体（未实现 {@link IRainbowMarked} 时忽略）
     */
    public static void dropInvestedCore(Level level, BlockPos pos, BlockEntity blockEntity) {
        if (level.isClientSide || !(blockEntity instanceof IRainbowMarked marked) || marked.getRainbowTier() < 0) {
            return;
        }
        Item core = OGEMagicCoreRef.get();
        if (core != null) {
            Block.popResource(level, pos, new ItemStack(core));
        }
    }

    /**
     * 该方块能不能被贴虹彩印记：三种资源发生器，以及火驱 / 魔驱金源能量发生器
     * （风驱与水驱没有可加速的“消耗”，电池不产能，都不在此列）。
     */
    public static boolean isAttachable(BlockState state) {
        if (state.getBlock() instanceof OGEResourceGeneratorBlock) {
            return true;
        }
        if (state.getBlock() instanceof OGEMachineBlock machine) {
            OGEMachineKind kind = machine.getKind();
            return kind == OGEMachineKind.FIRE || kind == OGEMachineKind.MAGIC;
        }
        return false;
    }
}
