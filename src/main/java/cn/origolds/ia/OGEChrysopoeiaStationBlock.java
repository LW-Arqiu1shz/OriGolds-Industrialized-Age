package cn.origolds.ia;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour.Properties;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 金源炼成台方块：把各种矿物原料炼成金锭的机器。
 *
 * <p>空手右键打开 27 格原料界面（{@link OGEChrysopoeiaStationBlockEntity} 负责
 * 逐刻吸收原料折算 CRV、攒满一份后耗无线金源能量吐出一块金锭）。背面接比较器时
 * 按“距下一次炼成是否已充满”输出 0~15 级信号。</p>
 *
 * <p>破坏 / 被替换时箱内原料由 26.x 原版的容器方块实体移除副作用自动散落到地面，
 * 本类只需在 {@link #affectNeighborsAfterRemoval} 里通知四周刷新比较器等信号。</p>
 */
public final class OGEChrysopoeiaStationBlock extends Block implements EntityBlock {

    public OGEChrysopoeiaStationBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return simpleCodec(OGEChrysopoeiaStationBlock::new);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new OGEChrysopoeiaStationBlockEntity(pos, state);
    }

    /** 只在服务端驱动 CRV 吸收与金锭炼成。 */
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                  BlockEntityType<T> type) {
        if (level.isClientSide() || type != ModBlockEntities.OGE_CHRYSOPOEIA_STATION.get()) {
            return null;
        }
        return (l, p, s, be) -> {
            if (be instanceof OGEChrysopoeiaStationBlockEntity station) {
                station.tick();
            }
        };
    }

    /** 空手右键打开炼成界面。 */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hitResult) {
        if (level.getBlockEntity(pos) instanceof OGEChrysopoeiaStationBlockEntity station) {
            if (!level.isClientSide()) {
                player.openMenu(station, pos);
            }
            return level.isClientSide() ? InteractionResult.SUCCESS : InteractionResult.SUCCESS_SERVER;
        }
        return InteractionResult.PASS;
    }

    /** 移除 / 被替换后通知四周刷新邻居与比较器信号。 */
    @Override
    protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos pos,
                                               boolean movedByPiston) {
        Containers.updateNeighboursAfterDestroy(state, level, pos);
    }

    @Override
    protected boolean hasAnalogOutputSignal(BlockState state) {
        return true;
    }

    @Override
    protected int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos, Direction direction) {
        return level.getBlockEntity(pos) instanceof OGEChrysopoeiaStationBlockEntity station
                ? station.getComparatorSignal()
                : 0;
    }
}
