package cn.origolds.ia;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 彩虹标记的公共常量与工具：档位倍率表、可上标记方块的判定、
 * 砸机器时返还投入的魔金之核。
 */
public final class OGERainbowMarks {

    /** 档位数（0/1/2）。 */
    public static final int TIER_COUNT = 3;

    private static final int[] SPEED_MULTIPLIER = {5, 20, 100};
    private static final int[] RESOURCE_MULTIPLIER = {1, 2, 4};
    private static final String[] LABELS = {"5x", "20x", "100x"};

    private OGERainbowMarks() {
    }

    /** 把任意档位号钳到 0~2。 */
    public static int clampTier(int tier) {
        return Math.max(0, Math.min(2, tier));
    }

    /** 速度倍率：未上标记（负档）恒为 1。 */
    public static int speedMultiplier(int tier) {
        return tier < 0 ? 1 : SPEED_MULTIPLIER[clampTier(tier)];
    }

    /** 资源倍率（能量费用上浮系数）：未上标记恒为 1。 */
    public static int resourceMultiplier(int tier) {
        return tier < 0 ? 1 : RESOURCE_MULTIPLIER[clampTier(tier)];
    }

    /** 档位展示名（"5x"/"20x"/"100x"）。 */
    public static String label(int tier) {
        return LABELS[clampTier(tier)];
    }

    /** 破坏带标记的机器时返还那颗投入的魔金之核（仅服务端）。 */
    public static void dropInvestedCore(Level level, BlockPos pos, BlockEntity blockEntity) {
        if (!level.isClientSide() && blockEntity instanceof IRainbowMarked marked
                && marked.getRainbowTier() >= 0) {
            Item core = OGEMagicCoreRef.get();
            if (core != null) {
                Block.popResource(level, pos, new ItemStack(core));
            }
        }
    }

    /** 该方块状态能否上彩虹标记：三种资源发生器 + 火驱/魔驱发生器。 */
    public static boolean isAttachable(BlockState state) {
        if (state.getBlock() instanceof OGEResourceGeneratorBlock) {
            return true;
        }
        if (!(state.getBlock() instanceof OGEMachineBlock machine)) {
            return false;
        }
        OGEMachineKind kind = machine.getKind();
        return kind == OGEMachineKind.FIRE || kind == OGEMachineKind.MAGIC;
    }
}
