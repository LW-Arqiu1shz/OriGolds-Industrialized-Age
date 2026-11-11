package cn.origolds.ia;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 金源炼金站（Origold Chrysopoeia Station）方块。
 *
 * <p>外观是普通正方体：四个侧面统一 {@code chrysopoeia_side}，顶面 {@code output_top}
 * （产物从顶部冒出），底面 {@code top_bottom}。四个侧面完全相同，所以不需要朝向属性，
 * 放置后无需旋转。</p>
 *
 * <p>接入无线电网后，把 27 格物品栏里可炼金的物品销毁并折算成炼金凝造价量（CRV），
 * 每累计到 2048 就尝试花费 10u OGE 在顶部产出一个金锭，规则见
 * {@link OGEChrysopoeiaStationBlockEntity}。空手右键打开 27 格界面。</p>
 */
public final class OGEChrysopoeiaStationBlock extends Block implements EntityBlock {

    public OGEChrysopoeiaStationBlock(BlockBehaviour.Properties properties) {
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

    /** 只在服务端驱动：销毁物品、累加 CRV、换金锭。 */
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                  BlockEntityType<T> type) {
        if (level.isClientSide || type != ModBlockEntities.OGE_CHRYSOPOEIA_STATION.get()) {
            return null;
        }
        return (l, p, s, be) -> {
            if (be instanceof OGEChrysopoeiaStationBlockEntity station) {
                station.tick();
            }
        };
    }

    /** 空手右键打开 27 格物品栏。 */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hitResult) {
        if (level.getBlockEntity(pos) instanceof OGEChrysopoeiaStationBlockEntity station) {
            if (!level.isClientSide) {
                player.openMenu(station, pos);
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        return InteractionResult.PASS;
    }

    /** 破坏/被替换时把物品栏里的东西散落到地面，和普通箱子一致。 */
    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        Containers.dropContentsOnDestroy(state, newState, level, pos);
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Override
    protected boolean hasAnalogOutputSignal(BlockState state) {
        return true;
    }

    @Override
    protected int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof OGEChrysopoeiaStationBlockEntity station
                ? station.getComparatorSignal()
                : 0;
    }
}
