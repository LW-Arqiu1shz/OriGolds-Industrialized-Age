package cn.origolds.ia;

import java.util.Collection;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 「按正常放置规则算出投影会落在哪一格、放成什么状态」的公共实现。
 *
 * <p>投影放置模式与准星预览都要回答同一个问题：以当前准星命中的目标（可能是真实方块，
 * 也可能是别的投影）为基准，手上这种绑定方块会被原版放置成什么样。之前只有
 * {@code Block#getStateForPlacement} 这一步，替换可替换方块、投影叠投影、放置合法性都
 * 没管；这里把判断补齐，并保持与原版 {@code BlockItem} 相同的顺序：</p>
 * <ol>
 *   <li>命中格本身能不能被替换（草、藤蔓、雪这类可替换方块就直接占掉它）；</li>
 *   <li>据此决定目标格是命中格还是它的相邻格；</li>
 *   <li>目标格必须空着或可替换（投影优先于真实方块）；</li>
 *   <li>{@code getStateForPlacement} 算出状态，再用 {@code canSurvive} 校验合法性
 *       （小麦只能放耕地上、火把要有支撑等等），不合法就不生成投影；</li>
 *   <li>目标格若已是水（或别种流体），会被流体冲掉的方块也不生成投影
 *       （见 {@link OGEFluidGuard}，避免放置器往水里反复放作物而拦住水流）。</li>
 * </ol>
 *
 * <p>投影是「像方块但没有碰撞箱、不能交互」的东西，所以合法性只拿<b>真实世界</b>判断：
 * 投影之间互相支撑属于边界情况（本模组每台放置器只投一种绑定方块，实心方块默认
 * {@code canSurvive} 恒真），这样既能叠投影，又不会把不合法位置混进清单。</p>
 */
final class OGEPlacerPlacement {

    /** 准星命中的目标：{@code ghostState} 为 null 表示命中的是真实方块。 */
    record Aim(BlockHitResult hit, @Nullable BlockState ghostState) {
    }

    /** 一次合法放置的结果：放在 {@code target}，状态为 {@code state}。 */
    record Result(BlockPos target, BlockState state) {
    }

    private OGEPlacerPlacement() {
    }

    /**
     * 算出一次投影放置；命中不了、目标被占、状态非法时返回 {@code null}。
     *
     * @param ghosts 当前已经存在的投影（含已保存与本次编辑的），用于判断目标格归属
     */
    @Nullable
    static Result resolve(Level level, Player player, ItemStack bound, Aim aim,
                          Collection<OGEPlacerGhosts.Ghost> ghosts) {
        if (!(bound.getItem() instanceof BlockItem blockItem)) {
            return null;
        }
        Block block = blockItem.getBlock();
        if (!block.isEnabled(level.enabledFeatures())) {
            return null;
        }
        BlockHitResult hit = aim.hit();
        // 临时上下文只用来问「命中格能不能被替换」，真实世界状态在这一步仍然有效。
        BlockPlaceContext probe = new BlockPlaceContext(level, player, InteractionHand.MAIN_HAND, bound, hit);
        boolean replacing = aim.ghostState() != null
                ? aim.ghostState().canBeReplaced(probe)
                : probe.replacingClickedOnBlock();
        BlockPos target = replacing ? hit.getBlockPos() : hit.getBlockPos().relative(hit.getDirection());
        // 目标格：投影优先于真实方块，两者都不可替换就不能放。
        BlockState occupied = ghostAt(ghosts, target);
        if (occupied == null) {
            occupied = level.getBlockState(target);
        }
        if (!occupied.isAir() && !occupied.canBeReplaced(probe)) {
            return null;
        }
        BlockPlaceContext context = new TargetContext(level, player, bound, hit, target, replacing);
        BlockState state = block.getStateForPlacement(context);
        if (state == null) {
            return null;
        }
        // 合法性校验与真实放置同一套：不合法就不给投影（例如小麦放到非耕地上）。
        if (!state.canSurvive(level, target)) {
            return null;
        }
        // 目标格是水（或别种流体）时，会被流体冲掉的方块不给投影：放下去也白放，
        // 还会把水流拦住，让以水流为基础的自动农场失效（见 OGEFluidGuard）。
        if (OGEFluidGuard.washedAwayByFluid(level, target, state)) {
            return null;
        }
        return new Result(target, state);
    }

    @Nullable
    private static BlockState ghostAt(Collection<OGEPlacerGhosts.Ghost> ghosts, BlockPos pos) {
        for (OGEPlacerGhosts.Ghost ghost : ghosts) {
            if (ghost.pos().equals(pos)) {
                return ghost.state();
            }
        }
        return null;
    }

    /**
     * 以「目标格已经定好」为前提的放置上下文。
     *
     * <p>原版 {@link BlockPlaceContext} 会拿真实世界判断命中格能不能替换、目标在命中格
     * 还是相邻格；命中投影时这套判断不适用（投影在真实世界里是空气），所以这里把已经
     * 算好的结论直接告诉它，其余朝向、点击面、点击坐标仍沿用原版。</p>
     */
    private static final class TargetContext extends BlockPlaceContext {

        private final BlockPos target;
        private final boolean replacing;

        TargetContext(Level level, Player player, ItemStack stack, BlockHitResult hit,
                      BlockPos target, boolean replacing) {
            super(level, player, InteractionHand.MAIN_HAND, stack, hit);
            this.target = target;
            this.replacing = replacing;
        }

        @Override
        public BlockPos getClickedPos() {
            return target;
        }

        @Override
        public boolean canPlace() {
            return true; // 目标格归属已经在上游判定过
        }

        @Override
        public boolean replacingClickedOnBlock() {
            return replacing;
        }

        /** 与原版一致：不替换时把「点击面的反向」提到最前，决定楼梯/半砖之类的朝向。 */
        @Override
        public Direction[] getNearestLookingDirections() {
            Direction[] directions = Direction.orderedByNearest(getPlayer());
            if (replacing) {
                return directions;
            }
            Direction face = getClickedFace();
            int index = 0;
            while (index < directions.length && directions[index] != face.getOpposite()) {
                ++index;
            }
            if (index > 0) {
                System.arraycopy(directions, 0, directions, 1, index);
                directions[0] = face.getOpposite();
            }
            return directions;
        }
    }
}
