package cn.origolds.ia;

import com.mojang.serialization.MapCodec;
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
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * 金源引力匣方块（Origold Gravity Cassette）。
 *
 * <p>外观与虚空抽屉同款，正面贴图随档位在
 * {@code gravitycassette_front333 / 555 / 777 / 999} 之间切换；内部是一个 27 格、
 * 允许多类型的普通小箱子；每刻把（2×档位+1）³ 范围内的掉落物吸进存储。放置时正面
 * 水平朝向玩家（只看南北东西四个水平方向，把手永远横着对着玩家镜头）。破坏时匣内
 * 所有物品像普通箱子／木桶一样散落到地面，方块本身正常掉落。</p>
 *
 * <p><b>吸附档位</b>共五档（0~4），默认第 {@value #DEFAULT_TIER} 档：</p>
 * <ul>
 *   <li>0 档（贴脸）：只吸上下左右前后六个紧邻格；</li>
 *   <li>1 档：3×3×3（半径 1）；</li>
 *   <li>2 档：5×5×5（半径 2）；</li>
 *   <li>3 档：7×7×7（半径 3）；</li>
 *   <li>4 档：9×9×9（半径 4）。</li>
 * </ul>
 *
 * <p>空手按住 Alt 再点鼠标即可改档：Alt+左键降档、Alt+右键升档（左下右上）。
 * Alt 不是原版交互包会携带的修饰键，按键判定在客户端完成（见
 * {@code OGEGravityCassetteClient}），服务端收到 {@link OGEGravityCassetteTierPayload}
 * 后由 {@link #adjustTier} 重新校验空手、方块类型与距离再改档。</p>
 */
public final class OGEGravityCassetteBlock extends Block implements EntityBlock, OGEItemBindableBlock {

    /** 正面朝向（只允许南北东西四个水平方向，把手永远横着朝向玩家）。 */
    public static final EnumProperty<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;

    /** 贴脸档（0 档）：不吸范围掉落物，只吸六个紧邻格。 */
    public static final int TIER_NEAR = 0;
    /** 档位取值下界（0 档 = 贴脸吸取）。 */
    public static final int TIER_MIN = 0;
    /** 档位取值上界（4 档，9×9×9）。 */
    public static final int TIER_MAX = 4;
    /** 放下时的默认档位：最高一档（9×9×9）。 */
    public static final int DEFAULT_TIER = TIER_MAX;

    /** 吸附档位：0~4，1~4 档同时等于吸取立方体的半径，0 档为贴脸模式。 */
    public static final IntegerProperty TIER = IntegerProperty.create("tier", TIER_MIN, TIER_MAX);

    /** 允许远程改档的最大距离平方（8 格）。 */
    private static final double MAX_ADJUST_DISTANCE_SQ = 64.0;

    public OGEGravityCassetteBlock(BlockBehaviour.Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(TIER, DEFAULT_TIER));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, TIER);
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return simpleCodec(OGEGravityCassetteBlock::new);
    }

    /** 把档位（1~4）夹到合法范围，越界时取最近的边界档。 */
    public static int clampTier(int tier) {
        return Math.max(TIER_MIN, Math.min(TIER_MAX, tier));
    }

    /** 把档位换算成吸取半径：档位即半径（1 档 = 3×3×3，4 档 = 9×9×9）。 */
    public static int radiusForTier(int tier) {
        return clampTier(tier);
    }

    /** 从方块状态读出当前吸取半径（0 档返回 0，配合 {@link #isNearTier} 走贴脸逻辑）。 */
    public static int suctionRadius(BlockState state) {
        return radiusForTier(state.getValue(TIER));
    }

    /** 是否处于“贴脸吸取”档（只吸六个紧邻格）。 */
    public static boolean isNearTier(BlockState state) {
        return state.getValue(TIER) == TIER_NEAR;
    }

    /** 档位对应的吸取范围文字标签：0 档显示“贴脸”，其余显示例如 {@code 3x3x3}。 */
    public static Component tierRangeLabel(int tier) {
        int safe = clampTier(tier);
        if (safe == TIER_NEAR) {
            return Component.translatable("message.origolds_ia.gravity_cassette_range_near");
        }
        int side = radiusForTier(safe) * 2 + 1;
        return Component.literal(side + "x" + side + "x" + side);
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

    /**
     * 服务端侧调整吸附档位，由客户端 Alt+左键/右键的报文触发
     * （见 {@link OGEGravityCassetteTierPayload}）。
     *
     * <p>客户端只负责判定 Alt（服务端读不到键盘），真正生效前这里把所有条件重新校验一遍：
     * 手上必须空着、必须真的指着金源引力匣、且在 8 格以内。档位到两端时夹住不变，
     * 但仍播报一次当前吸取范围，让玩家知道已经到底。</p>
     *
     * @param player    发起操作的玩家
     * @param packedPos 目标方块坐标（{@link BlockPos#asLong()} 打包）
     * @param delta     档位变化量：+1 升档（扩大范围）、-1 降档（缩小范围）
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
        if (!(state.getBlock() instanceof OGEGravityCassetteBlock)) {
            return;
        }
        // “空手”只认主手；主手有物品时 Alt+左/右键本来就该交给物品自己处理。
        if (!player.getMainHandItem().isEmpty()) {
            return;
        }
        if (player.distanceToSqr(Vec3.atCenterOf(pos)) > MAX_ADJUST_DISTANCE_SQ) {
            return;
        }
        int current = state.getValue(TIER);
        int next = clampTier(current + delta);
        if (next != current) {
            level.setBlock(pos, state.setValue(TIER, next), Block.UPDATE_ALL);
        }
        player.sendOverlayMessage(
                Component.translatable("message.origolds_ia.gravity_cassette_tier", tierRangeLabel(next)));
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new OGEGravityCassetteBlockEntity(pos, state);
    }

    /** 服务端每 tick 驱动吸取循环；客户端不做吸取。 */
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide() || type != ModBlockEntities.OGE_GRAVITY_CASSETTE.get()) {
            return null;
        }
        return (l, p, s, be) -> ((OGEGravityCassetteBlockEntity) be).tick();
    }

    /** 空手右键打开和虚空抽屉同款的容器界面。 */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hitResult) {
        if (level.getBlockEntity(pos) instanceof OGEGravityCassetteBlockEntity cassette) {
            if (!level.isClientSide()) {
                player.openMenu(cassette, pos);
            }
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.PASS;
    }

    /**
     * 破坏/被替换时把匣内 27 格物品全部散落到地面，和原版箱子、木桶完全一致
     * （原版 {@code BarrelBlock#onRemove} 就是这么写的）。
     *
     * <p>注意掉落物里不能再带 {@code minecraft:container} 组件，否则内容物会被
     * 同时塞进掉落的方块物品里，等于复制一份；所以本方块对应的 loot table
     * （{@code data/origolds_ia/loot_table/blocks/origold_gravity_cassette.json}）
     * 只掉方块本身。</p>
     */

    @Override
    protected boolean hasAnalogOutputSignal(BlockState state) {
        return true;
    }

    @Override
    protected int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos, Direction direction) {
        return level.getBlockEntity(pos) instanceof OGEGravityCassetteBlockEntity cassette
                ? cassette.getComparatorSignal()
                : 0;
    }
}
