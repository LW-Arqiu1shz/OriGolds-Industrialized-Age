package cn.origolds.ia;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 金源自动附魔机（Origold Mech-Enchanter）方块。
 *
 * <p>外形只有 0.75 格高（12/16，和原版附魔台一样）：顶面 {@code enchanter_top}、
 * 底面 {@code top_bottom}、四个侧面随档位在 {@code enchanter_side_1} /
 * {@code enchanter_side_4} / {@code enchanter_side_16} 之间切换。</p>
 *
 * <p>共有三档，影响合并时的 OGE 消耗与耗时（详见
 * {@link OGEMechEnchanterBlockEntity}）：</p>
 * <ul>
 *   <li>{@value #TIER_INDEX_1X} 档（1x）：正常消耗、正常耗时；</li>
 *   <li>{@value #TIER_INDEX_4X} 档（4x）：消耗翻倍、耗时降到 1/4；</li>
 *   <li>{@value #TIER_INDEX_16X} 档（16x）：消耗变 4 倍、耗时降到 1/16。</li>
 * </ul>
 *
 * <p><b>空手</b>按住 Ctrl 再点鼠标即可改档：Ctrl+左键降档、Ctrl+右键升档，
 * 结果直接反映在侧面上。Ctrl 不是原版交互包会携带的修饰键，按键判定在客户端完成
 * （见 {@code OGEMechEnchanterClient}），服务端收到 {@link OGEMechEnchanterTierPayload}
 * 后由 {@link #adjustTier} 重新校验空手、方块类型与距离再改档。</p>
 */
public final class OGEMechEnchanterBlock extends Block implements EntityBlock {

    /** 允许远程改档的最大距离平方（8 格）。 */
    private static final double MAX_ADJUST_DISTANCE_SQ = 64.0;

    /** 1x 档位下标。 */
    public static final int TIER_INDEX_1X = 0;
    /** 4x 档位下标。 */
    public static final int TIER_INDEX_4X = 1;
    /** 16x 档位下标。 */
    public static final int TIER_INDEX_16X = 2;

    /** 三档的倍率：OGE 消耗乘数，下标与 {@link #MODE} 一致。 */
    public static final int[] TIER_MULTIPLIER = {1, 4, 16};

    /** 档位方块状态：0/1/2 分别代表 1x / 4x / 16x。 */
    public static final IntegerProperty MODE = IntegerProperty.create("mode", 0, TIER_MULTIPLIER.length - 1);

    /** 0.75 格高的方形碰撞箱 / 轮廓，与附魔台一致。 */
    private static final VoxelShape SHAPE = Block.box(0.0, 0.0, 0.0, 16.0, 12.0, 16.0);

    public OGEMechEnchanterBlock(BlockBehaviour.Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any().setValue(MODE, TIER_INDEX_1X));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(MODE);
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return simpleCodec(OGEMechEnchanterBlock::new);
    }

    /** 放下时固定为 1x 档，不跟随玩家朝向（四个侧面贴图完全相同）。 */
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return this.defaultBlockState();
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos,
                                           CollisionContext context) {
        return SHAPE;
    }

    /** 把档位下标换算成倍率字符串标签（1x / 4x / 16x）。 */
    public static String tierLabel(int mode) {
        int index = mode < 0 ? 0 : Math.min(mode, TIER_MULTIPLIER.length - 1);
        return TIER_MULTIPLIER[index] + "x";
    }

    /**
     * 服务端侧调整档位，由客户端 Ctrl+左键/右键的报文触发
     * （见 {@link OGEMechEnchanterTierPayload}）。
     *
     * <p>客户端只负责判定 Ctrl（服务端读不到键盘），真正生效前这里把所有条件重新校验一遍：
     * 手上必须空着、必须真的指着金源自动附魔机、且在 8 格以内。档位到两端时夹住不变，
     * 但仍播报一次当前档位，让玩家知道已经到底。</p>
     *
     * @param player    发起操作的玩家
     * @param packedPos 目标方块坐标（{@link BlockPos#asLong()} 打包）
     * @param delta     档位变化量：+1 升档、-1 降档     */
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
        if (!(state.getBlock() instanceof OGEMechEnchanterBlock)) {
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
        int next = Math.max(0, Math.min(TIER_MULTIPLIER.length - 1, current + delta));
        if (next != current) {
            level.setBlock(pos, state.setValue(MODE, next), Block.UPDATE_ALL);
        }
        player.displayClientMessage(
                Component.translatable("message.origolds_ia.mech_enchanter_tier", tierLabel(next)), true);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new OGEMechEnchanterBlockEntity(pos, state);
    }

    /** 只在服务端驱动：扣电、计时、完成合并。 */
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                  BlockEntityType<T> type) {
        if (level.isClientSide || type != ModBlockEntities.OGE_MECH_ENCHANTER.get()) {
            return null;
        }
        return (l, p, s, be) -> {
            if (be instanceof OGEMechEnchanterBlockEntity enchanter) {
                enchanter.tick();
            }
        };
    }

    /** 空手右键打开附魔机界面。 */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hitResult) {
        if (level.getBlockEntity(pos) instanceof OGEMechEnchanterBlockEntity enchanter) {
            if (!level.isClientSide) {
                player.openMenu(enchanter, pos);
            }
            return InteractionResult.sidedSuccess(true);
        }
        return InteractionResult.PASS;
    }

    /** 破坏/被替换时把三个槽位里的物品散落到地面，和普通箱子一致。 */
    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState,
                            boolean movedByPiston) {
        Containers.dropContentsOnDestroy(state, newState, level, pos);
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}
