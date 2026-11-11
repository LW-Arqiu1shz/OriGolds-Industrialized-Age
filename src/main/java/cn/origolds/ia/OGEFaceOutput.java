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
 * “把物品送到方块的某个面”的公共实现，是 {@link OGEFaceItems}（取料）的输出侧配套模块。
 *
 * <p>本模组里大量机器都有出料口（粉碎机、筛洗机、超级熔炉、伐木模块……），它们的输出
 * 规则完全一致：<b>出料口那一格是容器时，优先把产物塞进容器；容器不存在、塞不下或
 * 该格不是容器时，剩余部分才作为掉落物掉在该格</b>。这样在机器出料口上放一个箱子，
 * 产物就会自动进箱子，不用漏斗转接。</p>
 *
 * <p>和取料侧一样，末影箱被当作“没有容器”：它的库存归属玩家、随人变动，自动写入
 * 会让不同玩家看到的东西串味，一律跳过。</p>
 *
 * <p>容器统一按 NeoForge 的 {@code IItemHandler} 能力查询，因此原版箱子、木桶、漏斗、
 * 投掷器，以及本模组暴露了该能力的机器都能直接用；非本模组、但同样注册了能力的容器
 * 也能自动兼容。插入用 {@link ItemHandlerHelper#insertItemStacked}：先补齐同类堆、
 * 再占用空格，和原版漏斗的行为一致。</p>
 */
public final class OGEFaceOutput {

    private OGEFaceOutput() {
    }

    /**
     * 把一整批产物送到 {@code pos} 这个出料口：能塞进容器就塞，塞不下的掉落。
     * 空堆会被跳过。
     */
    public static void output(Level level, BlockPos pos, List<ItemStack> stacks) {
        for (ItemStack stack : stacks) {
            output(level, pos, stack);
        }
    }

    /**
     * 把一份产物送到 {@code pos} 这个出料口：能塞进容器就塞，塞不下的掉落。
     * 空堆不做任何事。
     */
    public static void output(Level level, BlockPos pos, ItemStack stack) {
        if (level == null || level.isClientSide || stack.isEmpty()) {
            return;
        }
        ItemStack rest = insert(level, pos, stack);
        if (!rest.isEmpty()) {
            Block.popResource(level, pos, rest);
        }
    }

    /**
     * 只尝试“塞进容器”，不产生任何掉落物，返回没塞进去的余量（堆不会为空时即全部塞入）。
     * 出料口不是容器、容器满了或位置未加载时，原样返回 {@code stack}。
     *
     * <p>需要自行处理余量（例如留在机器缓存里等下一拍再送）的调用方用这个入口；
     * 一般机器直接用 {@link #output} 即可。</p>
     */
    public static ItemStack insert(Level level, BlockPos pos, ItemStack stack) {
        if (level == null || level.isClientSide || stack.isEmpty() || !level.isLoaded(pos)) {
            return stack;
        }
        // 末影箱的库存属于玩家个人，不参与自动化输出（与 OGEFaceItems 的取料规则一致）。
        if (level.getBlockState(pos).is(Blocks.ENDER_CHEST)) {
            return stack;
        }
        IItemHandler target = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null);
        if (target == null) {
            return stack;
        }
        return ItemHandlerHelper.insertItemStacked(target, stack, false);
    }
}
