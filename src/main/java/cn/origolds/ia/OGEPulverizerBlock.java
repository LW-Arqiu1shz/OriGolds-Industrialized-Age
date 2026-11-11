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
 * 金源粉碎机（Origold Pulverizer）的方块。
 *
 * <p>接入无线电网后，每游戏刻尝试消耗 {@value #COST_PER_OPERATION} u OGE，
 * 从四个水平侧面与底下的 5 个格子里取料（掉落物，或该格为容器时容器内的物品）进行粉碎：</p>
 * <ul>
 *   <li>粗矿（c:raw_materials）→ 2 个精矿（同物品 + {@code oge_concentrated} 组件，
 *       熔炉配方与粗矿相同，但不可用于合成、不可再次粉碎）；</li>
 *   <li>矿石（c:ores 的方块物品）→ 正常挖掘该矿石所得产物 ×2；</li>
 *   <li>黑曜石 → 6~10 个闪亮物品（闪亮碎粒/碎片/碎块）。</li>
 * </ul>
 * <p>产物统一掉落在粉碎机顶部。模型：四侧 pulverizer_side，顶面 output_top，底面 top_bottom。</p>
 */
public final class OGEPulverizerBlock extends Block implements EntityBlock {

    /** 单次粉碎消耗的 OGE（单位 u）。 */
    public static final int COST_PER_OPERATION = 5;

    public OGEPulverizerBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new OGEPulverizerBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (type == ModBlockEntities.OGE_PULVERIZER.get()) {
            return (l, p, s, be) -> {
                if (be instanceof OGEPulverizerBlockEntity pulverizer) {
                    pulverizer.tick();
                }
            };
        }
        return null;
    }
}
