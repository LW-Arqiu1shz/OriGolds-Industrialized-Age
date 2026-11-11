package cn.origolds.ia;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerPlayer;
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
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * 金源超级漏斗（Origold Hyperhopper）方块。
 *
 * <p>大小、形状与普通方块完全一致（一个整立方体），只是六个面按“输入端 / 输出端”
 * 的朝向贴上不同贴图：输入端 {@code hopper_input_straight/directional}、输出端
 * {@code hopper_output}、输出端对面 {@code hopper_output_back}、其余侧面
 * {@code hopper_side_straight / directional1 / directional2}。模型由
 * {@code tools/gen_hyperhopper_models.py} 按 30 种（输入端 6 选 1 × 输出端 5 选 1）
 * 组合全部生成，blockstate 直接按 {@link #INPUT}/{@link #OUTPUT} 选模型，
 * 所以玩家一旋转朝向，贴图立刻跟着换。</p>
 *
 * <h2>放置</h2>
 * <p>与普通漏斗相同：输出端朝向被点击的那个方块（{@code 点击面.getOpposite()}），
 * 输入端朝上。只有“输出端朝上”这一种极端情况（贴着方块底面往上放）无法再把输入端
 * 摆在上面，此时输入端落在水平方向。</p>
 *
 * <h2>旋转</h2>
 * <p>空手按住 Ctrl 点击鼠标即可旋转（判定在客户端 {@code OGEHyperhopperClient}，
 * 服务端 {@link #rotateEnd} 再校验一遍）。参考系是“方块指向玩家”的那条水平线：</p>
 * <ul>
 *   <li>Ctrl + 左键 / 右键：输入端绕“垂直于玩家视线的水平轴”俯仰翻转 90°
 *       （上↔朝向玩家 / 上↔背向玩家）；</li>
 *   <li>Ctrl + Alt + 左键 / 右键：输入端绕“指向玩家的水平轴”翻滚 90°；</li>
 *   <li>Ctrl + Shift + 左键 / 右键：输出端做同样的俯仰翻转；</li>
 *   <li>Ctrl + Shift + Alt + 左键 / 右键：输出端做同样的翻滚。</li>
 * </ul>
 * <p>转到另一端所在的那一面时会自动跳过，直接落到再下一个面。要是参考轴正好与
 * 当前朝向平行（转了等于没转），就换备用轴（不带 Alt 换视线轴、带 Alt 换竖直轴）
 * 再转，保证每次点击都有效。</p>
 */
public final class OGEHyperhopperBlock extends Block implements EntityBlock, OGEItemBindableBlock {

    /** 输入端朝向（物品从这一面进来）。 */
    public static final EnumProperty<Direction> INPUT = EnumProperty.create("input", Direction.class);

    /** 输出端朝向（物品从这一面出去）。 */
    public static final EnumProperty<Direction> OUTPUT = EnumProperty.create("output", Direction.class);

    /** 允许远程旋转的最大距离平方（8 格）。 */
    private static final double MAX_ADJUST_DISTANCE_SQ = 64.0;

    /** 旋转目标：输入端。 */
    public static final int TARGET_INPUT = 0;

    /** 旋转目标：输出端。 */
    public static final int TARGET_OUTPUT = 1;

    public OGEHyperhopperBlock(BlockBehaviour.Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any()
                .setValue(INPUT, Direction.UP)
                .setValue(OUTPUT, Direction.DOWN));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(INPUT, OUTPUT);
    }


    /** 输出端朝向被放置到的那个方块，输入端朝上（同普通漏斗的语义）。 */
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction output = context.getClickedFace().getOpposite();
        Direction input = output == Direction.UP ? Direction.NORTH : Direction.UP;
        return this.defaultBlockState().setValue(OUTPUT, output).setValue(INPUT, input);
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(INPUT, rotation.rotate(state.getValue(INPUT)))
                .setValue(OUTPUT, rotation.rotate(state.getValue(OUTPUT)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.setValue(INPUT, mirror.mirror(state.getValue(INPUT)))
                .setValue(OUTPUT, mirror.mirror(state.getValue(OUTPUT)));
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new OGEHyperhopperBlockEntity(pos, state);
    }

    /** 只在服务端驱动：计时、取出、推入。 */
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                  BlockEntityType<T> type) {
        if (level.isClientSide() || type != ModBlockEntities.OGE_HYPERHOPPER.get()) {
            return null;
        }
        return (l, p, s, be) -> {
            if (be instanceof OGEHyperhopperBlockEntity hopper) {
                hopper.tick();
            }
        };
    }

    /** 空手右键打开 27 格容量界面。 */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hitResult) {
        if (level.getBlockEntity(pos) instanceof OGEHyperhopperBlockEntity hopper) {
            if (!level.isClientSide()) {
                player.openMenu(hopper, pos);
            }
            return level.isClientSide() ? InteractionResult.SUCCESS : InteractionResult.SUCCESS_SERVER;
        }
        return InteractionResult.PASS;
    }

    /** 破坏/被替换时把 27 格里的物品散落到地面，和普通箱子、木桶一致。 */

    @Override
    protected boolean hasAnalogOutputSignal(BlockState state) {
        return true;
    }

    @Override
    protected int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos, Direction direction) {
        return level.getBlockEntity(pos) instanceof OGEHyperhopperBlockEntity hopper
                ? hopper.getComparatorSignal()
                : 0;
    }

    // ------------------------------------------------------------------
    // 朝向旋转（服务端校验）
    // ------------------------------------------------------------------

    /**
     * 旋转输入端或输出端，由客户端 Ctrl（+Alt/Shift）点击的报文触发
     * （见 {@code OGEHyperhopperRotatePayload}）。
     *
     * @param player    发起操作的玩家
     * @param packedPos 目标方块坐标（{@link BlockPos#asLong()} 打包）
     * @param target    {@link #TARGET_INPUT} 或 {@link #TARGET_OUTPUT}
     * @param alt       {@code true}（按住 Alt）绕“指向玩家的水平轴”翻滚；
     *                  {@code false} 绕“垂直于玩家视线的水平轴”俯仰
     * @param ccw       {@code true} 逆时针（左键），{@code false} 顺时针（右键）
     */
    public static void rotateEnd(ServerPlayer player, long packedPos, int target, boolean alt, boolean ccw) {
        if (target != TARGET_INPUT && target != TARGET_OUTPUT) {
            return;
        }
        BlockPos pos = BlockPos.of(packedPos);
        Level level = player.level();
        if (!level.isLoaded(pos)) {
            return;
        }
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof OGEHyperhopperBlock)) {
            return;
        }
        // “空手”只认主手；主手有物品时 Ctrl+左/右键本来就该交给物品自己处理。
        if (!player.getMainHandItem().isEmpty()) {
            return;
        }
        if (player.distanceToSqr(Vec3.atCenterOf(pos)) > MAX_ADJUST_DISTANCE_SQ) {
            return;
        }

        Direction input = state.getValue(INPUT);
        Direction output = state.getValue(OUTPUT);
        Direction current = target == TARGET_OUTPUT ? output : input;
        Direction other = target == TARGET_OUTPUT ? input : output;

        // 以“方块指向玩家”的水平线为参考系（与 1.21.1 原版语义一致）：
        // 不带 Alt 绕垂直于视线的水平轴俯仰，带 Alt 绕指向玩家的水平轴翻滚。
        Direction near = Direction.getApproximateNearest(
                player.getX() - (pos.getX() + 0.5), 0.0, player.getZ() - (pos.getZ() + 0.5));
        if (near.getAxis() == Direction.Axis.Y) {
            near = Direction.NORTH;
        }
        Direction side = near.getCounterClockWise(Direction.Axis.Y);

        Direction axis = alt ? near : side;
        Direction rotated = rotate90(current, axis, ccw);
        if (rotated == current) {
            // 这个轴和当前朝向平行，转了等于没转：换备用轴（带 Alt 换竖直轴，反之换视线轴）。
            axis = alt ? Direction.UP : near;
            rotated = rotate90(current, axis, ccw);
        }
        if (rotated == other) {
            // 落到了另一端那一面：跳过它，去下一个面。
            rotated = rotate90(rotated, axis, ccw);
        }
        if (rotated == current || rotated == other) {
            return;
        }

        BlockState updated = target == TARGET_OUTPUT
                ? state.setValue(OUTPUT, rotated)
                : state.setValue(INPUT, rotated);
        level.setBlock(pos, updated, Block.UPDATE_ALL);
    }

    /**
     * 把方向绕轴（右手系，轴的正方向）转 90°；{@code ccw} 为假时反向。
     * 轴与朝向平行时叉积为零向量，原版 {@code Direction.fromDelta} 返回 null、
     * 调用方据此走备用轴——这里必须原样返回 {@code dir}，不能当成某个方向。
     */
    private static Direction rotate90(Direction dir, Direction axis, boolean ccw) {
        Vec3i a = axis.getUnitVec3i();
        Vec3i v = dir.getUnitVec3i();
        int x;
        int y;
        int z;
        if (ccw) {
            x = a.getY() * v.getZ() - a.getZ() * v.getY();
            y = a.getZ() * v.getX() - a.getX() * v.getZ();
            z = a.getX() * v.getY() - a.getY() * v.getX();
        } else {
            x = v.getY() * a.getZ() - v.getZ() * a.getY();
            y = v.getZ() * a.getX() - v.getX() * a.getZ();
            z = v.getX() * a.getY() - v.getY() * a.getX();
        }
        if (x == 0 && y == 0 && z == 0) {
            return dir;
        }
        return Direction.getApproximateNearest(x, y, z);
    }
}
