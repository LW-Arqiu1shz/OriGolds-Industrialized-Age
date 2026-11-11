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
 * 金源伐木模块：接入无线电网后，检测相邻木头并消耗 OGE 自动伐木的机器方块。
 *
 * <p>每个面都使用斧头侧视贴图，具备方块实体以便每刻执行伐木判定。</p>
 */
public final class OGELoggerBlock extends Block implements EntityBlock {

    public OGELoggerBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new OGELoggerBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (type == ModBlockEntities.OGE_LOGGER.get()) {
            return (l, p, s, be) -> ((OGELoggerBlockEntity) be).tick();
        }
        return null;
    }
}
