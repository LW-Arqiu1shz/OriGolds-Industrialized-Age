package cn.origolds.ia;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LiquidBlockContainer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

/**
 * 「目标格现在是流体，把这种方块放进去会不会被冲掉」的判据。
 *
 * <p>金源放置器会在投影格已变成水（或别种流体）时照常放置。像作物、花草、火把这类
 * 非实心方块，原版水流会直接流进它所在的格子并把它顶掉：方块被冲走后投影又变回
 * 「可放」，放置器就会无休止地反复放、反复冲，既拦住了水流（以水流为基础的自动农场
 * 因此失效），又白白消耗材料与 OGE。</p>
 *
 * <p>这里复刻原版 {@code FlowingFluid#canHoldFluid} 的判据——「流体能不能流进这一格」。
 * 能流进来就意味着方块会被流体顶掉，即会被冲走；实心方块、含水方块与原版特判的
 * 告示牌 / 梯子 / 甘蔗等不会被冲走。放置器只在「不会被冲走」时才允许往流体里放。</p>
 */
final class OGEFluidGuard {

    private OGEFluidGuard() {
    }

    /**
     * 目标格当前有流体，而把 {@code state} 放进去会被这股流体冲掉时返回 {@code true}。
     *
     * <p>目标格没有流体（空气、普通方块等）时恒为 {@code false}：本判据只负责
     * 「放在水里」这一种情况，其余交给原本的 {@code canSurvive} 校验。</p>
     */
    static boolean washedAwayByFluid(BlockGetter level, BlockPos pos, BlockState state) {
        FluidState fluid = level.getFluidState(pos);
        return !fluid.isEmpty() && fluidWouldReplace(level, pos, state);
    }

    /**
     * 流体能不能流进这一格（复刻 {@code FlowingFluid#canHoldFluid}）。
     * 能流进来就会把方块顶掉——非实心的作物、花草正是这样被水冲走的。
     */
    private static boolean fluidWouldReplace(BlockGetter level, BlockPos pos, BlockState state) {
        if (state.getBlock() instanceof LiquidBlockContainer) {
            // 含水方块（半砖、楼梯等）：流体要么与它共存、要么进不去，总之不会被冲掉。
            return false;
        }
        if (state.getBlock() instanceof DoorBlock
                || state.is(BlockTags.SIGNS)
                || state.is(Blocks.LADDER)
                || state.is(Blocks.SUGAR_CANE)
                || state.is(Blocks.BUBBLE_COLUMN)) {
            // 原版这些方块挡得住流体，不会被冲掉。
            return false;
        }
        if (state.is(Blocks.NETHER_PORTAL)
                || state.is(Blocks.END_PORTAL)
                || state.is(Blocks.END_GATEWAY)
                || state.is(Blocks.STRUCTURE_VOID)) {
            return false;
        }
        // 其余情况：不挡流体（非实心）就会被冲掉，实心（挡流体）则不会。
        return !state.blocksMotion();
    }
}
