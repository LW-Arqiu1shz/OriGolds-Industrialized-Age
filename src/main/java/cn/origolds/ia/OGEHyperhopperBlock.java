package cn.origolds.ia;

import com.mojang.serialization.MapCodec;
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
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * 金源超级漏斗（Origold Hyperhopper）方块。
 *
 * <p>大小、形状与普通方块完全一致（一个整立方体），只是六个面按“输入端 / 输出端”
 * 的朝向贴上不同贴图：输入端 {@code hopper_input_straight/directional}、输出端
 * {@code hopper_output}、输出端对面 {@code hopper_output_back}、其余侧面
 * {@code hopper_side_directional3 / directional1 / directional2}；其中“输入端与输出端
 * 共轴”（两端正对，即 上↔下、南↔北、东↔西 任意一种）时，四个侧面一律改用
 * {@code hopper_side_straight}（箭头朝向与 {@code hopper_side_directional3} 相同，
 * 只是箭头样式不同）。模型由
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
 * <p>参考系是“玩家与漏斗的连线”这条水平轴（下称连线轴）：</p>
 * <ul>
 *   <li><b>Ctrl + 左键 / 右键</b>：绕“与连线轴垂直的那条水平轴”转 90°，端面在
 *       <em>连线轴所在的竖直平面</em>里走一圈——朝我 ↔ 朝上 ↔ 背我 ↔ 朝下；</li>
 *   <li><b>Ctrl + Alt + 左键 / 右键</b>：绕<em>连线轴本身</em>转 90°，端面像钟表指针
 *       一样绕着这条轴打转。</li>
 *   <li>Ctrl + Shift（+ Alt）：把上面的对象换成输出端。</li>
 * </ul>
 * <p>两条轴互相垂直，各自固定不变，所以同一按键的手感始终一致。只有当前朝向正好
 * 与所选轴平行（转了等于没转，例如绕连线轴转、而端面正对着玩家）时，才换另一条轴
 * 转一次。结束时若落在“另一个端面”这张真正冲突的面上，才向后多走一格跳过它，
 * 其余不冲突的面一律不跳。</p>
 */
public final class OGEHyperhopperBlock extends Block implements EntityBlock, OGEItemBindableBlock {

    /** 输入端朝向（物品从这一面进来）。 */
    public static final DirectionProperty INPUT = DirectionProperty.create("input");

    /** 输出端朝向（物品从这一面出去）。 */
    public static final DirectionProperty OUTPUT = DirectionProperty.create("output");

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

    @Override
    protected MapCodec<? extends Block> codec() {
        return simpleCodec(OGEHyperhopperBlock::new);
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
        if (level.isClientSide || type != ModBlockEntities.OGE_HYPERHOPPER.get()) {
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
            if (!level.isClientSide) {
                player.openMenu(hopper, pos);
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        return InteractionResult.PASS;
    }

    /** 破坏/被替换时把 27 格里的物品散落到地面，和普通箱子、木桶一致。 */
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
     * <p>参考系取“玩家与漏斗的连线”：{@code lineDir} 是从漏斗指向玩家的水平方向，
     * {@code side} 是与它垂直的另一条水平方向。两条轴从此固定，不再随当前朝向漂移。</p>
     *
     * @param player    发起操作的玩家
     * @param packedPos 目标方块坐标（{@link BlockPos#asLong()} 打包）
     * @param target    {@link #TARGET_INPUT} 或 {@link #TARGET_OUTPUT}
     * @param roll      {@code true}：绕“玩家—漏斗”连线轴转（Ctrl+Alt）；
     *                  {@code false}：绕与连线轴垂直的水平轴转，端面沿竖直平面走（Ctrl）
     * @param ccw       {@code true} 逆时针（左键），{@code false} 顺时针（右键）
     */
    public static void rotateEnd(ServerPlayer player, long packedPos, int target, boolean roll, boolean ccw) {
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

        // 参考系：从漏斗指向玩家的水平方向 = 玩家与漏斗的连线轴。
        Direction lineDir = Direction.getNearest(
                player.getX() - (pos.getX() + 0.5), 0.0, player.getZ() - (pos.getZ() + 0.5));
        if (lineDir.getAxis() == Direction.Axis.Y) {
            lineDir = Direction.NORTH;
        }
        // 与连线轴垂直的水平方向（同在水平面上）。
        Direction side = lineDir.getCounterClockWise(Direction.Axis.Y);

        // Ctrl：绕 side 转，端面在连线轴所在的竖直平面里上下走；
        // Ctrl+Alt：绕连线轴本身转，端面绕着这条轴打转。
        Direction axis = roll ? lineDir : side;
        Direction rotated = rotate90(current, axis, ccw);
        if (rotated == current) {
            // 只有“当前朝向正好与所选轴平行”这一种情况转不动（例如绕连线轴转而端面
            // 正对着玩家），此时才换另一条垂直轴，保证每次点击都有实际变化。
            axis = roll ? Direction.UP : lineDir;
            rotated = rotate90(current, axis, ccw);
        }
        if (rotated == other) {
            // 只跳过“另一个端面”这张真正冲突的面，其余不冲突的面一律不跳。
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

    /** 把方向绕轴（右手系，轴的正方向）转 90°；{@code ccw} 为假时反向。 */
    private static Direction rotate90(Direction dir, Direction axis, boolean ccw) {
        Vec3i a = axis.getNormal();
        Vec3i v = dir.getNormal();
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
        Direction result = Direction.fromDelta(x, y, z);
        return result != null ? result : dir;
    }
}
