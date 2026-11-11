package cn.origolds.ia;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.BlockBehaviour.Properties;
import net.minecraft.world.level.block.state.StateDefinition.Builder;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 圆锯刀片方块：作为 {@link OGESawbladeEntity} 的"模型载体"存在，不实际放置于世界。
 *
 * <p>状态：{@code mount_face}（钉在锯片台的哪个面）、{@code facing}（刀片自身的水平朝向，
 * 只在台面为 Y 面时生效）与 {@code frame}（三帧动画，0..2）。碰撞箱分两种姿态：
 * 钉在墙面/柱面时是横着的薄板，钉在台面板时按朝向给南北或东西向的立板。</p>
 */
public final class OGESawbladeBlock extends Block {
    /** 钉在锯片台的哪个面。 */
    public static final EnumProperty<Direction> MOUNT_FACE = EnumProperty.create("mount_face", Direction.class);
    /** 刀片自身朝向（仅 Y 面使用）。 */
    public static final EnumProperty<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;
    /** 动画帧号。 */
    public static final IntegerProperty FRAME = IntegerProperty.create("frame", 0, 2);

    private static final VoxelShape SHAPE_WALL = Block.box(0.0, 5.0, 0.0, 16.0, 11.0, 16.0);
    private static final VoxelShape SHAPE_STANDING_NORTH_SOUTH = Block.box(0.0, 0.0, 5.0, 16.0, 16.0, 11.0);
    private static final VoxelShape SHAPE_STANDING_EAST_WEST = Block.box(5.0, 0.0, 0.0, 11.0, 16.0, 16.0);

    public OGESawbladeBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any()
                .setValue(MOUNT_FACE, Direction.NORTH)
                .setValue(FACING, Direction.NORTH)
                .setValue(FRAME, 0));
    }

    @Override
    protected void createBlockStateDefinition(Builder<Block, BlockState> builder) {
        builder.add(MOUNT_FACE, FACING, FRAME);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        if (state.getValue(MOUNT_FACE).getAxis() != Axis.Y) {
            return SHAPE_WALL;
        }
        return state.getValue(FACING).getAxis() == Axis.X ? SHAPE_STANDING_EAST_WEST : SHAPE_STANDING_NORTH_SOUTH;
    }
}
