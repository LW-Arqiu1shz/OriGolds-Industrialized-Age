package cn.origolds.ia;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
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
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.Vec3;

/**
 * 金源鼓风机（Origold Blower）方块。
 *
 * <p>可朝六个方向放置（放置时出风口背对玩家），正面一帧静止、一帧转动的叶轮贴图。
 * 接入金源无线电网后持续工作：<b>每 1 秒判定一次</b>扣费，
 * 消耗 {@link #ogePerSecond(int, int)} u OGE 就把工作时间重置为 1.05 秒
 * （{@value OGEBlowerBlockEntity#WORK_TICKS_PER_PAYMENT} 游戏刻），
 * 因此正常供电时工作时间永远用不完（详见
 * {@link OGEBlowerBlockEntity#BILLING_INTERVAL_TICKS}）；
 * 工作期间按 {@value OGEBlowerBlockEntity#ANIM_CYCLE_TICKS} 刻一个来回的节奏在
 * {@code fan_front1} / {@code fan_front2} 之间切换正面贴图（每秒 4 个循环），
 * 并沿朝向吹出气流。停机后会把当前循环走完，最终一定停在 {@code fan_front1}。</p>
 *
 * <h2>距离档与力量档</h2>
 * <p>鼓风机有两组可调档位，四个侧面贴图随档位切换
 * （{@code blower_side_dM_pN}，M 是距离档、N 是力量档）：</p>
 * <ul>
 *   <li><b>距离档</b> {@value #MIN_DISTANCE}~{@value #MAX_DISTANCE}：每档让鼓风最大距离 +9 格，
 *       满档 {@value #MAX_DISTANCE} 时是 {@value #MAX_DISTANCE}×9 = 72 格；</li>
 *   <li><b>力量档</b> {@value #MIN_POWER}~{@value #MAX_POWER}：每档让推动速度 +9 格/秒，
 *       满档 {@value #MAX_POWER} 时是 {@value #MAX_POWER}×9 = 36 格/秒；</li>
 *   <li><b>耗电</b> = 距离档 × 力量档（每秒），基础 1×1 = 1 OGE/s，满档 8×4 = 32 OGE/s。</li>
 * </ul>
 * <h2>贴图朝向</h2>
 * <p>模型模板（{@code tools/gen_blower_models.py} 生成）把<b>风扇放在 {@code up} 面</b>、
 * <b>底座板 {@code top_bottom} 放在 {@code down} 面</b>、四个侧面 {@code north/south/east/west}
 * 放箭头贴图；blockstate 用原版 {@code end_rod} 的旋转方案把 {@code up} 转到 {@link #FACING}。
 * 这样无论朝哪个方向，风扇永远在出风口那一面，底座板在出风口的对面，四个箭头面正好是
 * 垂直于气流的四个面（朝上吹时就是顶面风扇、底面底座、四侧箭头）。</p>
 *
 * <p><b>空手</b>按住 Ctrl 再点鼠标即可改档，切换结果直接反映在侧面贴图上：</p>
 * <ul>
 *   <li>Ctrl + 左键：距离档 +1；Ctrl + 右键：距离档 -1；</li>
 *   <li>Ctrl + Alt + 左键：力量档 +1；Ctrl + Alt + 右键：力量档 -1。</li>
 * </ul>
 * <p>Ctrl / Alt 都不是原版交互包会携带的修饰键，所以按键判定在客户端完成
 * （见 {@code OGEBlowerClient}），服务端收到 {@link OGEBlowerGearPayload}
 * 后由 {@link #adjustGear} 重新校验空手、方块类型与距离再改档。</p>
 */
public final class OGEBlowerBlock extends Block implements EntityBlock {

    /** 允许远程改档的最大距离平方（8 格）。 */
    private static final double MAX_ADJUST_DISTANCE_SQ = 64.0;

    /** 距离档下限 / 上限。 */
    public static final int MIN_DISTANCE = 1;
    public static final int MAX_DISTANCE = 8;

    /** 力量档下限 / 上限。 */
    public static final int MIN_POWER = 1;
    public static final int MAX_POWER = 4;

    /** 每 1 个距离档增加的鼓风距离（格）。 */
    public static final int RANGE_PER_DISTANCE = 9;

    /** 每 1 个力量档增加的推动速度（格/秒）。 */
    public static final double SPEED_PER_POWER = 9.0;

    /** 距离档：1~8，与侧面贴图 {@code blower_side_dM_pN} 的 M 一一对应。 */
    public static final IntegerProperty DISTANCE = IntegerProperty.create("distance", MIN_DISTANCE, MAX_DISTANCE);

    /** 力量档：1~4，与侧面贴图 {@code blower_side_dM_pN} 的 N 一一对应。 */
    public static final IntegerProperty POWER = IntegerProperty.create("power", MIN_POWER, MAX_POWER);

    /** 出风口朝向，允许上下南北东西六个方向。 */
    public static final DirectionProperty FACING = BlockStateProperties.FACING;

    /**
     * 正面是否显示转动的第二帧贴图。
     *
     * <p>注意：不能用「是否正在工作」当作状态——停机后还要把本轮贴图循环走完，
     * 所以状态只表示「当前显示哪一帧」，由方块实体每 2~3 刻改写一次。</p>
     */
    public static final BooleanProperty ACTIVE = BooleanProperty.create("active");

    public OGEBlowerBlock(BlockBehaviour.Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(DISTANCE, MIN_DISTANCE)
                .setValue(POWER, MIN_POWER)
                .setValue(ACTIVE, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, DISTANCE, POWER, ACTIVE);
    }

    /** 放置时出风口朝向玩家看过去的方向（同原版发射器），也就是背对玩家吹；档位用基础档 1/1。 */
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return this.defaultBlockState()
                .setValue(FACING, context.getNearestLookingDirection().getOpposite());
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    /** 距离档换算成鼓风最大距离（格）：9 × 距离档。 */
    public static int rangeBlocks(int distance) {
        return RANGE_PER_DISTANCE * distance;
    }

    /** 力量档换算成推动速度（格/秒）：9 × 力量档。 */
    public static double speedPerSecond(int power) {
        return SPEED_PER_POWER * power;
    }

    /** 每秒 OGE 消耗：距离档 × 力量档（1×1=1 ~ 8×4=32）。 */
    public static int ogePerSecond(int distance, int power) {
        return distance * power;
    }

    /**
     * 服务端侧调整档位，由客户端 Ctrl / Ctrl+Alt 点击的报文触发
     * （见 {@link OGEBlowerGearPayload}）。
     *
     * <p>客户端只负责判定 Ctrl / Alt（服务端读不到键盘），真正生效前这里把所有条件重新校验一遍：
     * 手上必须空着、必须真的指着金源鼓风机、且在 8 格以内。档位到两端时夹住不变，
     * 但仍播报一次当前档位，让玩家知道已经到底。</p>
     *
     * @param player       发起操作的玩家
     * @param packedPos    目标方块坐标（{@link BlockPos#asLong()} 打包）
     * @param distanceStep 距离档变化量：+1 增加、-1 降低、0 不变
     * @param powerStep    力量档变化量：+1 增加、-1 降低、0 不变
     */
    public static void adjustGear(ServerPlayer player, long packedPos, int distanceStep, int powerStep) {
        if (distanceStep == 0 && powerStep == 0) {
            return;
        }
        BlockPos pos = BlockPos.of(packedPos);
        Level level = player.level();
        if (!level.isLoaded(pos)) {
            return;
        }
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof OGEBlowerBlock)) {
            return;
        }
        // “空手”只认主手；主手有物品时 Ctrl+左/右键本来就该交给物品自己处理。
        if (!player.getMainHandItem().isEmpty()) {
            return;
        }
        if (player.distanceToSqr(Vec3.atCenterOf(pos)) > MAX_ADJUST_DISTANCE_SQ) {
            return;
        }
        int distance = clamp(state.getValue(DISTANCE) + distanceStep, MIN_DISTANCE, MAX_DISTANCE);
        int power = clamp(state.getValue(POWER) + powerStep, MIN_POWER, MAX_POWER);
        if (distance != state.getValue(DISTANCE) || power != state.getValue(POWER)) {
            level.setBlock(pos, state.setValue(DISTANCE, distance).setValue(POWER, power), Block.UPDATE_ALL);
        }
        player.displayClientMessage(Component.translatable("message.origolds_ia.blower_gear",
                distance, power, rangeBlocks(distance),
                formatSpeed(speedPerSecond(power)), ogePerSecond(distance, power)), true);
    }

    /** 去掉整数值末尾的 “.0”，让播报文案读起来干净些。 */
    private static String formatSpeed(double speed) {
        return speed == Math.floor(speed) ? Integer.toString((int) speed) : Double.toString(speed);
    }

    private static int clamp(int value, int min, int max) {
        return value < min ? min : Math.min(value, max);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new OGEBlowerBlockEntity(pos, state);
    }

    /** 只在服务端驱动：工作计时、扣电、吹气与贴图帧都由服务端决定。 */
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide || type != ModBlockEntities.OGE_BLOWER.get()) {
            return null;
        }
        return (l, p, s, be) -> ((OGEBlowerBlockEntity) be).tick();
    }
}
