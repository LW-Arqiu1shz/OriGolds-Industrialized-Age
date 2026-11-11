package cn.origolds.ia;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 已经装到金源圆锯刃槽上的圆锯刀刃（{@code origolds_ia:sawblade}）的
 * <b>模型载体方块</b>。
 *
 * <p>第 149 轮起刀刃本体是 {@link OGESawbladeEntity 实体}，这个方块<b>不会再被放进世界</b>：
 * 它留在注册表里只是为了让 {@link OGESawbladeRenderer} 能借用方块状态解析出
 * {@code blockstates/sawblade.json} 里的三个模型。实体把同步过来的两个朝向套进
 * {@link OGESawbladeBlock#MOUNT_FACE} 与 {@link OGESawbladeBlock#FACING}，
 * 再用方块渲染器画出对应那一面的刀刃。</p>
 *
 * <h2>两个方块状态</h2>
 * <ul>
 *   <li>{@link #MOUNT_FACE}：这片刀刃挂在刃槽的哪个面上，决定用三个模型里的哪一个
 *       （四个侧面共用水平面模型，顶面竖直、底面竖直且贴图倒过来）；</li>
 *   <li>{@link #FACING}：刀刃平面的水平朝向，<b>只有挂顶面 / 底面时用得上</b>。
 *       四个侧面时刀刃恒平行于地面（{@code FACING} 保持默认值，不影响外观）；
 *       顶面 / 底面时刀刃竖直站立，装的那一下按“离玩家最近的四向之一”定好朝向。</li>
 * </ul>
 *
 * <h2>模型</h2>
 * <p>四个侧面用 {@code origold_sawblade_wall}（水平面），顶面用
 * {@code origold_sawblade_floor}、底面用 {@code origold_sawblade_ceil}
 * （同一块竖直平面，底面那份把贴图上下翻过来，让刀刃从刃槽底面向下垂）。
 * 各面的旋转见 {@code blockstates/sawblade.json}。</p>
 */
public final class OGESawbladeBlock extends Block {

    /** 挂在刃槽的哪个面上；刀刃就在这格 {@code 刃槽坐标.relative(MOUNT_FACE)}。 */
    public static final DirectionProperty MOUNT_FACE =
            DirectionProperty.create("mount_face", Direction.values());

    /** 刀刃平面的朝向（水平四向）：只有刃槽顶面 / 底面的刀刃用得上。 */
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;

    /**
     * 动画帧：0 = {@code sawblade}、1 = {@code sawblade_move1}、2 = {@code sawblade_move2}。
     * 刃槽通电时由 {@link OGESawbladeRenderer} 在客户端每 5 刻换一帧，断电回到 0。
     */
    public static final IntegerProperty FRAME = IntegerProperty.create("frame", 0, 2);

    /** 侧面刀刃：平面平行于地面，判定盒取中间 6/16 厚，准星容易点中又看得穿。 */
    private static final VoxelShape SHAPE_WALL = Block.box(0.0, 5.0, 0.0, 16.0, 11.0, 16.0);

    /** 顶 / 底面刀刃：竖直平面，判定盒按朝向取中间 6/16 厚。 */
    private static final VoxelShape SHAPE_STANDING_NORTH_SOUTH =
            Block.box(0.0, 0.0, 5.0, 16.0, 16.0, 11.0);
    private static final VoxelShape SHAPE_STANDING_EAST_WEST =
            Block.box(5.0, 0.0, 0.0, 11.0, 16.0, 16.0);

    public OGESawbladeBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any()
                .setValue(MOUNT_FACE, Direction.NORTH)
                .setValue(FACING, Direction.NORTH)
                .setValue(FRAME, 0));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(MOUNT_FACE, FACING, FRAME);
    }

    /** 判定盒：薄薄一片，只给准星与外框用；实体径直穿过（属性里的 {@code noCollission}）。 */
    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        if (state.getValue(MOUNT_FACE).getAxis() != Direction.Axis.Y) {
            return SHAPE_WALL;
        }
        return state.getValue(FACING).getAxis() == Direction.Axis.X
                ? SHAPE_STANDING_EAST_WEST
                : SHAPE_STANDING_NORTH_SOUTH;
    }
}
