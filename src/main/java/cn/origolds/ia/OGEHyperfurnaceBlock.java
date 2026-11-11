package cn.origolds.ia;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
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
import net.minecraft.world.level.block.state.BlockBehaviour.Properties;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition.Builder;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * 金源超级熔炉方块：烧掉原料区里能熔炼的物品，成品吐到机器正上方。
 *
 * <p>共三档（{@link #MODE}）：1x 每 20 刻熔 1 件、每件 2 OGE；5x 每 4 刻熔 1 件、
 * 每件 4 OGE；20x 每刻熔 1 件、每件 8 OGE。档位由玩家空手按住 Ctrl 准星对着
 * 机器点鼠标调节（左键降档、右键升档，见 {@link OGEHyperfurnaceClient} 与
 * {@link #adjustTier}），切换时以 actionbar 消息告知当前档位、速度与耗能。</p>
 *
 * <p>空手右键打开 27 格原料界面（{@link OGEHyperfurnaceMenu}）；比较器按箱内
 * 占用格数输出 0~15 级信号。破坏 / 被替换时箱内物品由 26.x 原版的容器方块实体
 * 移除副作用自动散落，本类只在 {@link #affectNeighborsAfterRemoval} 里通知邻居。</p>
 */
public final class OGEHyperfurnaceBlock extends Block implements EntityBlock {

    /** 空手 + Ctrl 改档的命中距离上限（平方，8 格）。 */
    private static final double MAX_ADJUST_DISTANCE_SQ = 64.0;

    /** 朝向（水平）。 */
    public static final EnumProperty<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;

    /** 档位数。 */
    public static final int TIER_COUNT = 3;
    public static final int TIER_INDEX_1X = 0;
    public static final int TIER_INDEX_5X = 1;
    public static final int TIER_INDEX_20X = 2;

    /** 默认档位（1x）。 */
    public static final int DEFAULT_MODE = 0;

    /** 档位方块状态属性：0=1x，1=5x，2=20x。 */
    public static final IntegerProperty MODE = IntegerProperty.create("mode", 0, 2);

    /** 每档“熔一件需要的刻数”：20 / 4 / 1。 */
    private static final int[] TICKS_PER_ITEM = new int[] {20, 4, 1};

    /** 每档“熔一件消耗的 OGE”：2 / 4 / 8。 */
    private static final int[] COST_PER_ITEM = new int[] {2, 4, 8};

    /** 每档的展示名。 */
    private static final String[] TIER_LABELS = new String[] {"1x", "5x", "20x"};

    public OGEHyperfurnaceBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(MODE, DEFAULT_MODE));
    }

    @Override
    protected void createBlockStateDefinition(Builder<Block, BlockState> builder) {
        builder.add(FACING, MODE);
    }

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

    /** 把任意档位序号夹到合法范围。 */
    public static int clampMode(int mode) {
        return Math.max(0, Math.min(TIER_COUNT - 1, mode));
    }

    /** 当前档位熔一件需要的刻数。 */
    public static int ticksPerItem(int mode) {
        return TICKS_PER_ITEM[clampMode(mode)];
    }

    /** 当前档位每秒熔炼件数（读数用）。 */
    public static int itemsPerSecond(int mode) {
        return 20 / ticksPerItem(mode);
    }

    /** 当前档位熔一件消耗的 OGE。 */
    public static int costPerItem(int mode) {
        return COST_PER_ITEM[clampMode(mode)];
    }

    /** 当前档位的展示名。 */
    public static String tierLabel(int mode) {
        return TIER_LABELS[clampMode(mode)];
    }

    /**
     * 客户端 Ctrl+点击改档请求的服务端落点：再校验一次空手、方块类型与距离，
     * 然后改 {@link #MODE} 并回一条 actionbar 消息（档位、速度、耗能）。
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
        player.sendOverlayMessage(Component.translatable("message.origolds_ia.hyperfurnace_tier",
                tierLabel(next), itemsPerSecond(next), costPerItem(next)));
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new OGEHyperfurnaceBlockEntity(pos, state);
    }

    /** 只在服务端驱动熔炼循环。 */
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                  BlockEntityType<T> type) {
        if (level.isClientSide() || type != ModBlockEntities.OGE_HYPERFURNACE.get()) {
            return null;
        }
        return (l, p, s, be) -> {
            if (be instanceof OGEHyperfurnaceBlockEntity furnace) {
                furnace.tick();
            }
        };
    }

    /** 空手右键打开原料界面。 */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hitResult) {
        if (level.getBlockEntity(pos) instanceof OGEHyperfurnaceBlockEntity furnace) {
            if (!level.isClientSide()) {
                player.openMenu(furnace, pos);
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
}
