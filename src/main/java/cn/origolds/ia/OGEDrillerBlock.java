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
 * 金源采掘模块：接入无线电网后，检测相邻 6 个方块并消耗 OGE 自动采掘的机器方块。
 *
 * <p>每个面都使用镐头侧视贴图，具备方块实体以便周期执行采掘判定。</p>
 */
public final class OGEDrillerBlock extends Block implements EntityBlock {

    public OGEDrillerBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new OGEDrillerBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (type == ModBlockEntities.OGE_DRILLER.get()) {
            return (l, p, s, be) -> ((OGEDrillerBlockEntity) be).tick();
        }
        return null;
    }
}
