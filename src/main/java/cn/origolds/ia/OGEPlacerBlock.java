package cn.origolds.ia;

import java.util.ArrayList;
import java.util.List;

import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
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

/**
 * 金源放置器（Origold Placer）方块。
 *
 * <p>外观是普通立方体：顶面 {@code placer_top}、底面 {@code output_top}，
 * 四个侧面随放置节拍在 {@code placer_side1t / 5t / 10t / 20t} 之间切换。
 * 放下默认 1t 档。</p>
 *
 * <p>操作方式（Alt / Ctrl 都不是原版交互包会携带的修饰键，按键判定在客户端完成，
 * 见 {@code OGEPlacerClient}；服务端收到 {@link OGEPlacerPayload} 后把空手、
 * 方块类型与距离全部重新校验一遍）：</p>
 * <ul>
 *   <li><b>手持方块 Alt+中键</b>：把手上这种方块绑定成唯一目标；换一种方块再按一次
 *       就换成新方块；<b>空手 Alt+中键</b>清空绑定；</li>
 *   <li><b>空手 Alt+左键</b>：进入投影放置模式（纯客户端编辑，见
 *       {@code OGEPlacerClient}），右键在 9×9×9 范围内摆投影、左键删投影，<b>Alt+右键</b>结束
 *       并把整份投影列表发给服务端保存；</li>
 *   <li><b>空手 Ctrl+左键</b>降低放置间隔、<b>Ctrl+右键</b>提高：1t / 5t / 10t / 20t。</li>
 * </ul>
 *
 * <p>保存下来的投影会交给方块实体 {@link OGEPlacerBlockEntity} 填充：每到一个放置节拍，
 * 就把当前所有能放的位置<b>一次性全部放完</b>；每个方块从四个水平侧面与底下取 1 份
 * 绑定方块，每成功放置 1 个扣 1u OGE。放在投影里的方块各扣各的，材料或电量不够时
 * 这一批停在哪算哪，剩下的等下一个节拍。</p>
 */
public final class OGEPlacerBlock extends Block implements EntityBlock {

    /** 允许远程操作的最大距离平方（8 格）。 */
    private static final double MAX_INTERACT_DISTANCE_SQ = 64.0;

    /** 四档放置间隔（刻），与四张 "placer_sideNt" 侧面贴图一一对应。 */
    public static final int[] INTERVAL_TICKS = {1, 5, 10, 20};

    /** 默认档位下标：放下时是 1t 档。 */
    public static final int DEFAULT_MODE = 0;

    /** 投影允许的最大范围：以放置器为中心 9×9×9，即每轴 ±4 格。 */
    public static final int PROJECTION_RADIUS = 4;

    /** 投影数量上限（9×9×9 全填满）。 */
    public static final int MAX_PROJECTIONS = (PROJECTION_RADIUS * 2 + 1) * (PROJECTION_RADIUS * 2 + 1) * (PROJECTION_RADIUS * 2 + 1);

    /** 档位方块状态：0~3 分别代表 1 / 5 / 10 / 20 tick。 */
    public static final IntegerProperty MODE = IntegerProperty.create("mode", 0, INTERVAL_TICKS.length - 1);

    public OGEPlacerBlock(BlockBehaviour.Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any().setValue(MODE, DEFAULT_MODE));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(MODE);
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return simpleCodec(OGEPlacerBlock::new);
    }

    /** 放下时固定为 1t 档，不跟随玩家朝向（四个侧面只按档位切换）。 */
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return this.defaultBlockState();
    }

    /** 档位下标 → 放置间隔（刻），越界时夹到合法范围。 */
    public static int intervalTicks(int mode) {
        return INTERVAL_TICKS[clampMode(mode)];
    }

    /** 档位下标 → 侧面贴图上的名字标签，例如 0 → {@code 1t}。 */
    public static String intervalLabel(int mode) {
        return intervalTicks(mode) + "t";
    }

    private static int clampMode(int mode) {
        return Math.max(0, Math.min(INTERVAL_TICKS.length - 1, mode));
    }

    /** 目标位置是否落在以放置器为中心的 9×9×9 范围内。 */
    public static boolean inProjectionRange(BlockPos origin, BlockPos target) {
        return Math.abs(target.getX() - origin.getX()) <= PROJECTION_RADIUS
                && Math.abs(target.getY() - origin.getY()) <= PROJECTION_RADIUS
                && Math.abs(target.getZ() - origin.getZ()) <= PROJECTION_RADIUS;
    }

    /**
     * 服务端侧绑定/清空绑定，由客户端 Alt+中键的报文触发（见 {@link OGEPlacerPayload}）。
     *
     * <p>主手拿着方块物品 → 把这种方块记住；主手空着 → 清空绑定。
     * 其他情况（拿着工具、食物等非方块物品）不处理。</p>
     */
    public static void bind(ServerPlayer player, long packedPos) {
        BlockPos pos = BlockPos.of(packedPos);
        Level level = player.level();
        if (!level.isLoaded(pos)
                || !(level.getBlockState(pos).getBlock() instanceof OGEPlacerBlock)
                || player.distanceToSqr(Vec3.atCenterOf(pos)) > MAX_INTERACT_DISTANCE_SQ
                || !(level.getBlockEntity(pos) instanceof OGEPlacerBlockEntity placer)) {
            return;
        }
        ItemStack held = player.getMainHandItem();
        if (held.isEmpty()) {
            placer.setBound(ItemStack.EMPTY);
            player.displayClientMessage(Component.translatable("message.origolds_ia.placer_unbound"), true);
        } else if (held.getItem() instanceof BlockItem) {
            placer.setBound(held);
            player.displayClientMessage(
                    Component.translatable("message.origolds_ia.placer_bound", held.getHoverName()), true);
        }
    }

    /**
     * 服务端侧调整放置间隔，由客户端空手 Ctrl+左键/右键的报文触发。
     * 到达两端时夹住不变，但仍播报一次当前间隔，让玩家知道已经到底。
     *
     * @param delta -1 降低间隔、+1 提高间隔
     */
    public static void adjustInterval(ServerPlayer player, long packedPos, int delta) {
        if (delta == 0) {
            return;
        }
        BlockPos pos = BlockPos.of(packedPos);
        Level level = player.level();
        if (!level.isLoaded(pos)) {
            return;
        }
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof OGEPlacerBlock)) {
            return;
        }
        // “空手”只认主手；主手有物品时 Ctrl+左/右键本来就该交给物品自己处理。
        if (!player.getMainHandItem().isEmpty()) {
            return;
        }
        if (player.distanceToSqr(Vec3.atCenterOf(pos)) > MAX_INTERACT_DISTANCE_SQ) {
            return;
        }
        int current = state.getValue(MODE);
        int next = clampMode(current + delta);
        if (next != current) {
            level.setBlock(pos, state.setValue(MODE, next), Block.UPDATE_ALL);
        }
        player.displayClientMessage(
                Component.translatable("message.origolds_ia.placer_interval", intervalTicks(next)), true);
    }

    /**
     * 服务端侧保存投影列表，由客户端在投影放置模式按 Alt+右键时的报文触发。
     *
     * <p>客户端送来的东西一概不全信：每个投影都要落在 9×9×9 范围内、方块种类必须与
     * 绑定方块一致、目标格现在是水时该方块还得不会被冲走，数量也不能超过整片范围能
     * 容纳的上限。校验通过的才写进方块实体。</p>
     */
    public static void saveProjections(ServerPlayer player, long packedPos,
                                       List<OGEPlacerBlockEntity.Projection> incoming) {
        BlockPos pos = BlockPos.of(packedPos);
        Level level = player.level();
        if (!level.isLoaded(pos)
                || !(level.getBlockState(pos).getBlock() instanceof OGEPlacerBlock)) {
            return;
        }
        // 保存时不再要求主手空着：投影模式里编辑全程被客户端拦着，玩家中途换手持
        // 物品不该让保存静默失败；真正要紧的距离与方块种类下面都会校验。
        if (player.distanceToSqr(Vec3.atCenterOf(pos)) > MAX_INTERACT_DISTANCE_SQ
                || !(level.getBlockEntity(pos) instanceof OGEPlacerBlockEntity placer)) {
            return;
        }
        ItemStack bound = placer.getBound();
        if (bound.isEmpty()) {
            return;
        }
        List<OGEPlacerBlockEntity.Projection> cleaned = new ArrayList<>();
        for (OGEPlacerBlockEntity.Projection projection : incoming) {
            if (cleaned.size() >= MAX_PROJECTIONS) {
                break;
            }
            if (!inProjectionRange(pos, projection.pos())) {
                continue;
            }
            if (projection.state().getBlock().asItem() != bound.getItem()) {
                continue;
            }
            if (OGEFluidGuard.washedAwayByFluid(level, projection.pos(), projection.state())) {
                // 目标格现在是水（或别种流体），放下去会被冲走：不收，免得放置器反复放又拦住水流。
                continue;
            }
            cleaned.add(projection);
        }
        placer.setProjections(cleaned);
        player.displayClientMessage(
                Component.translatable("message.origolds_ia.placer_project_saved", cleaned.size()), true);
    }

    /**
     * 服务端侧删除一条已保存的投影，由客户端在投影模式或物流显示模式左键时触发。
     *
     * <p>客户端送来的位置同样不全信：先确认目标坐标确实在这台放置器的投影列表里，
     * 命中才删除并广播 2001 号方块事件（对应方块的破坏音效与粒子），没命中就什么也不做。</p>
     */
    public static void deleteProjection(ServerPlayer player, long packedPos, long packedTarget) {
        BlockPos pos = BlockPos.of(packedPos);
        Level level = player.level();
        if (!level.isLoaded(pos)
                || !(level.getBlockState(pos).getBlock() instanceof OGEPlacerBlock)
                || player.distanceToSqr(Vec3.atCenterOf(pos)) > MAX_INTERACT_DISTANCE_SQ
                || !(level.getBlockEntity(pos) instanceof OGEPlacerBlockEntity placer)) {
            return;
        }
        BlockPos target = BlockPos.of(packedTarget);
        List<OGEPlacerBlockEntity.Projection> remaining = new ArrayList<>();
        BlockState removed = null;
        for (OGEPlacerBlockEntity.Projection projection : placer.getProjections()) {
            if (removed == null && projection.pos().equals(target)) {
                removed = projection.state(); // 命中的这条不放进剩余列表，即为删除
                continue;
            }
            remaining.add(projection);
        }
        if (removed == null) {
            return;
        }
        placer.setProjections(remaining);
        // 2001 = 原版「破坏方块」事件：对应方块的破坏音效 + 粒子，会广播给周围所有玩家。
        level.levelEvent(2001, target, Block.getId(removed));
    }

    /** 分派客户端报文；所有条件都在各自的静态方法里重新校验一遍。 */
    public static void handle(ServerPlayer player, OGEPlacerPayload payload) {
        switch (payload.action()) {
            case OGEPlacerPayload.ACTION_BIND -> bind(player, payload.pos());
            case OGEPlacerPayload.ACTION_ADJUST -> adjustInterval(player, payload.pos(), payload.value());
            case OGEPlacerPayload.ACTION_SAVE -> {
                List<OGEPlacerBlockEntity.Projection> list = new ArrayList<>();
                for (OGEPlacerPayload.Entry entry : payload.projections()) {
                    OGEPlacerBlockEntity.Projection projection = OGEPlacerPayload.toProjection(entry);
                    if (projection != null) {
                        list.add(projection); // 状态 id 非法时 toProjection 返回 null，直接丢掉
                    }
                }
                saveProjections(player, payload.pos(), list);
            }
            case OGEPlacerPayload.ACTION_DELETE -> {
                // 一次只删一条：只认列表里第一条的位置。
                if (!payload.projections().isEmpty()) {
                    deleteProjection(player, payload.pos(), payload.projections().get(0).pos());
                }
            }
            default -> {
            }
        }
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new OGEPlacerBlockEntity(pos, state);
    }

    /** 只在服务端驱动取料与放置。 */
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                  BlockEntityType<T> type) {
        if (level.isClientSide || type != ModBlockEntities.OGE_PLACER.get()) {
            return null;
        }
        return (l, p, s, be) -> {
            if (be instanceof OGEPlacerBlockEntity placer) {
                placer.tick();
            }
        };
    }

    /** 该机器没有界面，空手右键不做任何事（保持默认 PASS）。 */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hitResult) {
        return InteractionResult.PASS;
    }
}
