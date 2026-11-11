package cn.origolds.ia;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.util.RandomSource;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 金源放置器投影的「收集 + 命中」公共逻辑。
 *
 * <p>投影不是真实方块，没有碰撞箱、引擎也不会替我们做鼠标拾取，所以渲染、预览与左键
 * 删除都得自己拿方块轮廓（{@link BlockState#getShape}）做射线求交。这里集中三件事：</p>
 * <ul>
 *   <li><b>收集</b>：玩家附近已加载区块里的放置器，按当前模式给出应该显示的投影——
 *       物流显示模式（且<b>手持</b>父模组连接装置）显示已保存投影，投影放置模式显示
 *       本次编辑中的投影，同一格以编辑中的为准；</li>
 *   <li><b>命中</b>：把「最近的投影命中」与「最近的真实方块命中」比较，靠得更近的那个
 *       才是准星真正的目标（投影挡在真方块前面时能优先选中投影，反之真方块挡住投影时
 *       不会隔墙选中它）；</li>
 *   <li><b>预览</b>：投影放置模式里用 {@link OGEPlacerPlacement} 按正常放置规则算出
 *       准星上那一格会放成什么样，作为半透明虚影一起交给渲染器。</li>
 * </ul>
 *
 * <p>附近放置器的扫描结果按游戏刻缓存，避免每帧遍历区块。</p>
 */
final class OGEPlacerGhosts {

    /** 投影的来源：已保存 / 本次编辑中 / 准星预览。 */
    enum Source {
        /** 已保存到方块实体的投影（物流显示模式）。 */
        SAVED,
        /** 本次投影放置模式里摆好的投影（客户端暂存，退出时才保存）。 */
        PENDING,
        /** 准星上的预览，还没真正摆下。 */
        PREVIEW
    }

    /** 一条投影：位置、状态、归属哪台放置器、来自哪里。 */
    record Ghost(BlockPos pos, BlockState state, BlockPos placer, Source source) {
    }

    /** 附近放置器的扫描半径（格）。 */
    private static final int SCAN_RADIUS = 48;

    /** 附近放置器的位置缓存（每游戏刻重扫一次）。 */
    private static List<BlockPos> placerPositions = List.of();
    private static long cacheTick = Long.MIN_VALUE;
    private static Level cacheLevel;

    private OGEPlacerGhosts() {
    }

    /**
     * 物流显示模式是否正在「有效显示」：模式开着<b>且</b>手持父模组连接装置。
     * 不满足时已保存投影不渲染、也不可删除。
     */
    static boolean logisticsVisible(Minecraft mc) {
        return mc.player != null
                && OGEWirelessConnectorClient.isLogisticsModeOn()
                && OGEWirelessConnectorItemRef.isHeld(mc.player);
    }

    /** 当前应该渲染出来的全部投影（含准星预览）。 */
    static List<Ghost> shown(Minecraft mc) {
        List<Ghost> ghosts = existing(mc);
        BlockPos editing = OGEPlacerClient.editingPos();
        if (editing == null || ghosts == null || mc.level == null || mc.player == null) {
            return ghosts;
        }
        if (!(mc.level.getBlockEntity(editing) instanceof OGEPlacerBlockEntity placer)) {
            return ghosts;
        }
        OGEPlacerPlacement.Aim aim = aim(mc, ghosts);
        if (aim == null) {
            return ghosts;
        }
        Ghost preview = preview(mc, placer, editing, aim, ghosts);
        if (preview != null) {
            removeAt(ghosts, preview.pos());
            ghosts.add(preview);
        }
        return ghosts;
    }

    /** 当前已经存在的投影（已保存 + 编辑中），不含准星预览。 */
    static List<Ghost> existing(Minecraft mc) {
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            return List.of();
        }
        boolean logistics = logisticsVisible(mc);
        boolean editing = OGEPlacerClient.isProjecting();
        if (!logistics && !editing) {
            return List.of();
        }
        List<Ghost> ghosts = new ArrayList<>();
        // 同一格只留一条：编辑中的投影覆盖同格的已保存投影。
        Map<BlockPos, Ghost> byPos = new LinkedHashMap<>();
        for (BlockPos placerPos : nearbyPlacers(mc)) {
            if (!(mc.level.getBlockEntity(placerPos) instanceof OGEPlacerBlockEntity placer)) {
                continue;
            }
            if (logistics) {
                for (OGEPlacerBlockEntity.Projection projection : placer.getProjections()) {
                    byPos.put(projection.pos(),
                            new Ghost(projection.pos(), projection.state(), placerPos, Source.SAVED));
                }
            }
            if (OGEPlacerClient.isEditing(placerPos)) {
                for (OGEPlacerBlockEntity.Projection projection : OGEPlacerClient.pending()) {
                    byPos.put(projection.pos(),
                            new Ghost(projection.pos(), projection.state(), placerPos, Source.PENDING));
                }
            }
        }
        ghosts.addAll(byPos.values());
        return ghosts;
    }

    /** 射线命中的、可以删除的投影（已保存或编辑中）；没有则返回 null。 */
    @Nullable
    static Ghost aimed(Minecraft mc) {
        List<Ghost> ghosts = existing(mc);
        OGEPlacerPlacement.Aim aim = aim(mc, ghosts);
        if (aim == null || aim.ghostState() == null) {
            return null;
        }
        return find(ghosts, aim.hit().getBlockPos());
    }

    /** 在投影列表里按坐标找一条。 */
    @Nullable
    static Ghost find(List<Ghost> ghosts, BlockPos pos) {
        for (Ghost ghost : ghosts) {
            if (ghost.pos().equals(pos)) {
                return ghost;
            }
        }
        return null;
    }

    /**
     * 准星目标：在投影与真实方块之间取距离更近的一个。
     * 两者都没有命中（对着空气）时返回 null。
     */
    @Nullable
    static OGEPlacerPlacement.Aim aim(Minecraft mc, List<Ghost> ghosts) {
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            return null;
        }
        Vec3 eye = player.getEyePosition();
        double range = player.blockInteractionRange();
        Vec3 end = eye.add(player.getLookAngle().scale(range));
        // 投影：拿每条投影的方块轮廓与视线求交，取最近的一条。
        Ghost ghostHit = null;
        BlockHitResult ghostResult = null;
        double ghostDistSq = Double.MAX_VALUE;
        for (Ghost ghost : ghosts) {
            if (ghost.source() == Source.PREVIEW) {
                continue;
            }
            VoxelShape shape = ghost.state().getShape(mc.level, ghost.pos());
            if (shape.isEmpty()) {
                continue;
            }
            BlockHitResult hit = shape.clip(eye, end, ghost.pos());
            if (hit == null) {
                continue;
            }
            double distSq = eye.distanceToSqr(hit.getLocation());
            if (distSq < ghostDistSq) {
                ghostDistSq = distSq;
                ghostHit = ghost;
                ghostResult = hit;
            }
        }
        // 真实方块：走原版那套 clip，免得隔着墙选中投影。
        BlockHitResult real = mc.level.clip(
                new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        double realDistSq = real.getType() == HitResult.Type.BLOCK
                ? eye.distanceToSqr(real.getLocation())
                : Double.MAX_VALUE;
        if (ghostHit != null && ghostDistSq <= realDistSq) {
            return new OGEPlacerPlacement.Aim(ghostResult, ghostHit.state());
        }
        if (real.getType() == HitResult.Type.BLOCK) {
            return new OGEPlacerPlacement.Aim(real, null);
        }
        return null;
    }

    /** 按正常放置规则算出准星上的预览投影；放不下就返回 null。 */
    @Nullable
    private static Ghost preview(Minecraft mc, OGEPlacerBlockEntity placer, BlockPos placerPos,
                                 OGEPlacerPlacement.Aim aim, List<Ghost> ghosts) {
        ItemStack bound = placer.getBound();
        if (bound.isEmpty()) {
            return null;
        }
        OGEPlacerPlacement.Result result =
                OGEPlacerPlacement.resolve(mc.level, mc.player, bound, aim, ghosts);
        if (result == null || !OGEPlacerBlock.inProjectionRange(placerPos, result.target())) {
            return null;
        }
        return new Ghost(result.target(), result.state(), placerPos, Source.PREVIEW);
    }

    private static void removeAt(List<Ghost> ghosts, BlockPos pos) {
        ghosts.removeIf(ghost -> ghost.pos().equals(pos));
    }

    /**
     * 交给渲染线程的一条投影：位置、状态、来源，以及<b>已经在主线程烘焙好的</b>模型部件。
     *
     * <p>模型部件必须在主线程算：{@code BlockStateModel.collectParts} 会读方块状态与
     * 流体状态，26.3 起这类容器带 {@code ThreadingDetector} 信号量，渲染线程去读会
     * 阻塞在 {@code Semaphore.acquire()} 上（表现为卡在进世界）或抛线程检查异常
     * （表现为反复崩溃、启动器进安全模式）。</p>
     */
    record RenderGhost(BlockPos pos, BlockState state, Source source, List<BlockStateModelPart> parts) {
    }

    private static volatile List<RenderGhost> renderSnapshot = List.of();

    /** 渲染线程唯一允许的入口：只读刻级快照。 */
    static List<RenderGhost> renderSnapshot() {
        return renderSnapshot;
    }

    /**
     * 每客户端刻在<b>主线程</b>刷新投影快照（含模型烘焙）。世界或玩家缺失时清空，
     * 渲染线程因此什么都不会画，不会去碰尚未就绪的世界数据。
     */
    static void refreshRenderSnapshot(Minecraft mc) {
        if (mc.level == null || mc.player == null) {
            renderSnapshot = List.of();
            return;
        }
        List<Ghost> ghosts = shown(mc);
        if (ghosts.isEmpty()) {
            renderSnapshot = List.of();
            return;
        }
        RandomSource random = RandomSource.create();
        List<RenderGhost> built = new ArrayList<>(ghosts.size());
        for (Ghost ghost : ghosts) {
            BlockStateModel model = mc.getModelManager().getBlockStateModelSet().get(ghost.state());
            if (model == null) {
                continue;
            }
            List<BlockStateModelPart> parts = new ArrayList<>();
            random.setSeed(ghost.pos().asLong());
            model.collectParts(mc.level, ghost.pos(), ghost.state(), random, parts);
            if (!parts.isEmpty()) {
                built.add(new RenderGhost(ghost.pos(), ghost.state(), ghost.source(), parts));
            }
        }
        renderSnapshot = built;
    }

    /**
     * 缓存玩家附近已加载区块里的金源放置器；同一游戏刻内只重扫一次，
     * 避免多帧渲染反复遍历区块。
     */
    private static List<BlockPos> nearbyPlacers(Minecraft mc) {
        long now = mc.level.getGameTime();
        if (mc.level == cacheLevel && now == cacheTick) {
            return placerPositions;
        }
        cacheLevel = mc.level;
        cacheTick = now;
        List<BlockPos> found = new ArrayList<>();
        BlockPos origin = mc.player.blockPosition();
        int minCx = (origin.getX() - SCAN_RADIUS) >> 4;
        int maxCx = (origin.getX() + SCAN_RADIUS) >> 4;
        int minCz = (origin.getZ() - SCAN_RADIUS) >> 4;
        int maxCz = (origin.getZ() + SCAN_RADIUS) >> 4;
        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                LevelChunk chunk = mc.level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) {
                    continue;
                }
                for (BlockEntity be : chunk.getBlockEntities().values()) {
                    if (be instanceof OGEPlacerBlockEntity) {
                        found.add(be.getBlockPos());
                    }
                }
            }
        }
        placerPositions = found;
        return found;
    }
}
