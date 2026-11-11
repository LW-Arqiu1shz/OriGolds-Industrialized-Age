package cn.origolds.ia;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
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
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * 金源超级熔炉（Origold Hyperfurnace）方块。
 *
 * <p>外观是普通正方体：正面 {@code hyperfurnace_front_1x / 5x / 20x} 随档位切换，
 * 其余五个面统一用 {@code top_bottom}；放置时正面水平朝向玩家（只看南北东西四个
 * 水平方向，不看俯仰，同原版熔炉）。</p>
 *
 * <p>接入无线电网后消耗 OGE 烧炼物品（配方取原版熔炼配方，规则见
 * {@link OGEHyperfurnaceBlockEntity}）。共有三档，决定“每秒烧几个”与“每个的
 * 电费”：</p>
 * <ul>
 *   <li>{@value #TIER_INDEX_1X} 档（1x）：每 20 tick 烧 1 个（每秒 1 个），每个 2u OGE；</li>
 *   <li>{@value #TIER_INDEX_5X} 档（5x）：每 4 tick 烧 1 个（每秒 5 个），每个 4u OGE；</li>
 *   <li>{@value #TIER_INDEX_20X} 档（20x）：每刻烧 1 个（每秒 20 个），每个 8u OGE。</li>
 * </ul>
 *
 * <p><b>空手</b>按住 Ctrl 再点鼠标即可改档：Ctrl+左键降档、Ctrl+右键升档。
 * Ctrl 不是原版交互包会携带的修饰键，按键判定在客户端完成（见
 * {@code OGEHyperfurnaceClient}），服务端收到 {@link OGEHyperfurnaceTierPayload}
 * 后由 {@link #adjustTier} 重新校验空手、方块类型与距离再改档。</p>
 */
public final class OGEHyperfurnaceBlock extends Block implements EntityBlock {

    /** 允许远程改档的最大距离平方（8 格）。 */
    private static final double MAX_ADJUST_DISTANCE_SQ = 64.0;

    /** 正面朝向（只允许南北东西四个水平方向）。 */
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;

    /** 档位数量：1x / 5x / 20x 共三档。 */
    public static final int TIER_COUNT = 3;

    /** 1x 档位下标。 */
    public static final int TIER_INDEX_1X = 0;
    /** 5x 档位下标。 */
    public static final int TIER_INDEX_5X = 1;
    /** 20x 档位下标。 */
    public static final int TIER_INDEX_20X = 2;

    /** 放下时的默认档位：1x（最省电的基础档）。 */
    public static final int DEFAULT_MODE = TIER_INDEX_1X;

    /** 档位方块状态：0/1/2 分别代表 1x / 5x / 20x。 */
    public static final IntegerProperty MODE = IntegerProperty.create("mode", 0, TIER_COUNT - 1);

    /** 各档烧炼 1 个物品需要的刻数：20 / 4 / 1，正好是每秒 1 / 5 / 20 个。 */
    private static final int[] TICKS_PER_ITEM = {20, 4, 1};

    /** 各档烧炼 1 个物品消耗的 OGE：2u，5x 档 ×2、20x 档 ×4。 */
    private static final int[] COST_PER_ITEM = {2, 4, 8};

    /** 各档的倍率标签（贴在正面贴图与提示里）。 */
    private static final String[] TIER_LABELS = {"1x", "5x", "20x"};

    public OGEHyperfurnaceBlock(BlockBehaviour.Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(MODE, DEFAULT_MODE));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, MODE);
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

    /** 把档位下标夹到合法范围。 */
    public static int clampMode(int mode) {
        return Math.max(0, Math.min(TIER_COUNT - 1, mode));
    }

    /** 档位下标 → 烧炼 1 个物品需要的刻数（20 / 4 / 1）。 */
    public static int ticksPerItem(int mode) {
        return TICKS_PER_ITEM[clampMode(mode)];
    }

    /** 档位下标 → 每秒烧炼的物品数（1 / 5 / 20）。 */
    public static int itemsPerSecond(int mode) {
        return 20 / ticksPerItem(mode);
    }

    /** 档位下标 → 烧炼 1 个物品消耗的 OGE（2 / 4 / 8）。 */
    public static int costPerItem(int mode) {
        return COST_PER_ITEM[clampMode(mode)];
    }

    /** 档位下标 → 倍率标签（1x / 5x / 20x）。 */
    public static String tierLabel(int mode) {
        return TIER_LABELS[clampMode(mode)];
    }

    /**
     * 服务端侧调整档位，由客户端 Ctrl+左键/右键的报文触发
     * （见 {@link OGEHyperfurnaceTierPayload}）。
     *
     * <p>客户端只负责判定 Ctrl（服务端读不到键盘），真正生效前这里把所有条件重新校验
     * 一遍：手上必须空着、必须真的指着金源超级熔炉、且在 8 格以内。档位到两端时夹住
     * 不变，但仍播报一次当前档位，让玩家知道已经到底。</p>
     *
     * @param player    发起操作的玩家
     * @param packedPos 目标方块坐标（{@link BlockPos#asLong()} 打包）
     * @param delta     档位变化量：+1 升档、-1 降档
     */
    public static void adjustTier(ServerPlayer player, long packedPos, int delta) {
        if (delta == 0) {
            return;
        }
        BlockPos pos = BlockPos.of(packedPos);
        Level level = player.level();
        if (!level.isLoaded(pos)) {
            return;
        }
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof OGEHyperfurnaceBlock)) {
            return;
        }
        // “空手”只认主手；主手有物品时 Ctrl+左/右键本来就该交给物品自己处理。
        if (!player.getMainHandItem().isEmpty()) {
            return;
        }
        if (player.distanceToSqr(Vec3.atCenterOf(pos)) > MAX_ADJUST_DISTANCE_SQ) {
            return;
        }
        int current = state.getValue(MODE);
        int next = clampMode(current + delta);
        if (next != current) {
            level.setBlock(pos, state.setValue(MODE, next), Block.UPDATE_ALL);
        }
        player.displayClientMessage(Component.translatable("message.origolds_ia.hyperfurnace_tier",
                tierLabel(next), itemsPerSecond(next), costPerItem(next)), true);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new OGEHyperfurnaceBlockEntity(pos, state);
    }

    /** 只在服务端驱动：取料、扣电、按原版熔炼配方产出。 */
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                  BlockEntityType<T> type) {
        if (level.isClientSide || type != ModBlockEntities.OGE_HYPERFURNACE.get()) {
            return null;
        }
        return (l, p, s, be) -> {
            if (be instanceof OGEHyperfurnaceBlockEntity furnace) {
                furnace.tick();
            }
        };
    }

    /**
     * 空手右键打开 27 格存储界面。
     *
     * <p>按住 Ctrl 的左右键不会走到这里：客户端在
     * {@link OGEHyperfurnaceClient} 里就把带 Ctrl 的点击吃掉，改成发档位报文了。</p>
     */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hitResult) {
        if (level.getBlockEntity(pos) instanceof OGEHyperfurnaceBlockEntity furnace) {
            if (!level.isClientSide) {
                player.openMenu(furnace, pos);
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        return InteractionResult.PASS;
    }

    /** 破坏/被替换时把 27 格存储里的东西散落到地面，和普通箱子一致。 */
    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        Containers.dropContentsOnDestroy(state, newState, level, pos);
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}
