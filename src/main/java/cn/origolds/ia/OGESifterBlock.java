package cn.origolds.ia;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 金源筛洗机（Origold Sifter）的方块。
 *
 * <p>接入无线电网后，每游戏刻尝试消耗 {@value #COST_PER_OPERATION} u OGE，
 * 从四个水平侧面与底下的 5 个格子里取料（掉落物，或该格为容器时容器内的物品）进行筛洗：</p>
 * <ul>
 *   <li>沙砾 → 25%/50%/25% 概率产出 3/4/5 个闪亮碎粒；</li>
 *   <li>黏土 → 25%/50%/25% 概率产出 3/4/5 个物品，每个 50% 闪亮碎粒、50% 闪亮碎片；</li>
 *   <li>闪亮碎粒 / 闪亮碎片 / 闪亮碎块 → 按各自权重表产出 1 个随机矿物
 *      （煤炭、粗铜、粗铁、红石、青金石、粗金、钻石、绿宝石）。</li>
 * </ul>
 * <p>产物统一掉落在筛洗机顶部。模型：四侧 sifter_side，顶面 output_top，底面 top_bottom。</p>
 */
public final class OGESifterBlock extends Block implements EntityBlock {

    /** 单次筛洗消耗的 OGE（单位 u）。 */
    public static final int COST_PER_OPERATION = 5;

    public OGESifterBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new OGESifterBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (type == ModBlockEntities.OGE_SIFTER.get()) {
            return (l, p, s, be) -> {
                if (be instanceof OGESifterBlockEntity sifter) {
                    sifter.tick();
                }
            };
        }
        return null;
    }
}
