package cn.origolds.ia;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;

/**
 * “往指定面的容器里塞物品”的公共出口：塞不进去的部分原地掉落成掉落物。
 *
 * <p>炼成台、机械合成台、锯片台等机器的产物输出口共用这段逻辑；
 * 末影箱没有对外物品能力，特意先跳过它，避免产物直接掉地上被误认成 bug。</p>
 */
public final class OGEFaceOutput {

    private OGEFaceOutput() {
    }

    /** 逐件输出一批产物到同一目标格。 */
    public static void output(Level level, BlockPos pos, List<ItemStack> stacks) {
        for (ItemStack stack : stacks) {
            output(level, pos, stack);
        }
    }

    /** 输出一件产物；目标格没有容器或装不下时掉落在该格。 */
    public static void output(Level level, BlockPos pos, ItemStack stack) {
        if (level != null && !level.isClientSide() && !stack.isEmpty()) {
            ItemStack rest = insert(level, pos, stack);
            if (!rest.isEmpty()) {
                Block.popResource(level, pos, rest);
            }
        }
    }

    /** 把物品插进目标格的容器，返回没塞下的剩余（整件没塞下时就是原堆）。 */
    public static ItemStack insert(Level level, BlockPos pos, ItemStack stack) {
        if (level == null || level.isClientSide() || stack.isEmpty() || !level.isLoaded(pos)) {
            return stack;
        }
        if (level.getBlockState(pos).is(Blocks.ENDER_CHEST)) {
            return stack;
        }
        IItemHandler target = OGEItemHandlerView.of(level.getCapability(Capabilities.Item.BLOCK, pos, null));
        return target == null ? stack : ItemHandlerHelper.insertItemStacked(target, stack, false);
    }
}
