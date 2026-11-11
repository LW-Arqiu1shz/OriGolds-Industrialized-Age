package cn.origolds.ia;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 金源自动合成机（Origold Mech-Crafter）方块。
 *
 * <p>外观是普通正方体：四个侧面都是带 9 个格子的 {@code mechcrafter_front}（这 9 个
 * 格子由 {@link OGEMechCrafterRenderer} 按锁定内容动态渲染，见
 * {@link OGEMechCrafterBlockEntity}），顶面 {@code output_top}（产物从这里冒出），
 * 底面 {@code top_bottom}。放置时正面水平朝向玩家。</p>
 *
 * <p>空手右键打开界面；界面上方 9 个显示格用来“锁定配方”，下方 27 格存储只接受被锁定
 * 的物品，配方有效且材料齐时每刻扣 1u OGE 自动合成一个产物从顶部送出。</p>
 */
public final class OGEMechCrafterBlock extends Block implements EntityBlock {

    /** 正面朝向（只允许南北东西四个水平方向）。 */
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;

    public OGEMechCrafterBlock(BlockBehaviour.Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return simpleCodec(OGEMechCrafterBlock::new);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    /** 放置时正面水平朝向玩家：只看玩家所在的水平方向，不看俯仰（同原版熔炉）。 */
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return this.defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new OGEMechCrafterBlockEntity(pos, state);
    }

    /** 只在服务端驱动：检查配方、扣电、消耗材料、顶部出产物。 */
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                  BlockEntityType<T> type) {
        if (level.isClientSide || type != ModBlockEntities.OGE_MECH_CRAFTER.get()) {
            return null;
        }
        return (l, p, s, be) -> {
            if (be instanceof OGEMechCrafterBlockEntity crafter) {
                crafter.tick();
            }
        };
    }

    /** 空手右键打开界面。 */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hitResult) {
        if (level.getBlockEntity(pos) instanceof OGEMechCrafterBlockEntity crafter) {
            if (!level.isClientSide) {
                player.openMenu(crafter, pos);
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        return InteractionResult.PASS;
    }

    /** 破坏/被替换时把存储里的东西散落到地面，和普通箱子一致。 */
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
        return level.getBlockEntity(pos) instanceof OGEMechCrafterBlockEntity crafter
                ? crafter.getComparatorSignal()
                : 0;
    }
}
