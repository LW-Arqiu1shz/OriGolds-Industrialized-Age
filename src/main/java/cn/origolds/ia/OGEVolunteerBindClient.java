package cn.origolds.ia;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.ClipContext.Block;
import net.minecraft.world.level.ClipContext.Fluid;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult.Type;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent.Post;
import net.neoforged.neoforge.client.event.InputEvent.InteractionKeyMappingTriggered;
import net.neoforged.neoforge.client.event.InputEvent.MouseButton.Pre;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/**
 * 志愿者快速绑定 / 预览的客户端状态机与世界线框渲染：
 * <ul>
 *   <li>快速绑定态（服务端回发 {@link OGEVolunteerQuickBindPayload} 后激活）：
 *       左键把准星对准的锯片台绑上/解绑，右键退出；已绑台子画半透明填充、
 *       附近可绑的画白色线框，准星命中的加亮；</li>
 *   <li>预览态（绑定界面点某一行触发）：志愿者眼睛到目标格拉一条白线并填充目标框，
 *       右键取消；</li>
 *   <li>Ctrl+左键点志愿者 = 请求进入快速绑定态；志愿者消失 / 距离超 32 格自动退出。</li>
 * </ul>
 */
@EventBusSubscriber(modid = OriGoldsIndustrializedAge.MOD_ID, value = Dist.CLIENT)
public final class OGEVolunteerBindClient {

    /** 快速绑定的准星射线长度。 */
    private static final double QUICK_BIND_RANGE = 32.0;
    /** 快速绑定态维持的最大距离平方（32 格）。 */
    private static final double MAX_KEEP_DISTANCE_SQ = 1024.0;

    private static int quickBindVolunteerId = -1;
    private static final Set<Long> quickBindBound = new HashSet<>();
    private static int previewVolunteerId = -1;
    private static volatile long previewPosLong;

    /** 附近锯片台扫描缓存：同一游戏刻只重扫一次。 */
    private static Level mountCacheLevel;
    private static long mountCacheTick = -1L;
    private static List<BlockPos> mountCache = List.of();

    /**
     * 渲染用的刻级快照：附近锯片台、准星命中的台、预览志愿者的眼位、已绑集合。
     *
     * <p>客户端刻刷新、渲染回调只读快照，绝不在绘制路径上查世界——
     * 与 26.3 版结构一致（那边帧渲染拆出了抽取阶段，跨线程访问 PalettedContainer
     * 会崩），这里提前统一，两版行为、维护口径保持对齐。</p>
     */
    private static volatile List<BlockPos> mountSnapshot = List.of();
    private static volatile BlockPos aimedMountSnapshot = null;
    private static volatile Vec3 previewEyeSnapshot = null;
    private static volatile Set<Long> boundSnapshot = Set.of();

    private static final int[][] BOX_EDGES = {
            {0, 1}, {1, 3}, {3, 2}, {2, 0}, {4, 5}, {5, 7}, {7, 6}, {6, 4},
            {0, 4}, {1, 5}, {2, 6}, {3, 7}};
    private static final double[][] BOX_CORNERS = {
            {0, 0, 0}, {1, 0, 0}, {0, 1, 0}, {1, 1, 0},
            {0, 0, 1}, {1, 0, 1}, {0, 1, 1}, {1, 1, 1}};
    private static final int[][] BOX_FACES = {
            {0, 1, 3, 2}, {4, 6, 7, 5}, {0, 2, 6, 4}, {1, 5, 7, 3}, {0, 4, 5, 1}, {2, 3, 7, 6}};

    private static final int GLFW_KEY_LEFT_CONTROL = 341;
    private static final int GLFW_KEY_RIGHT_CONTROL = 345;
    private static final int GLFW_MOD_CONTROL = 2;

    private OGEVolunteerBindClient() {
    }

    // ------------------------------------------------------------------
    // 预览态（绑定界面回发）
    // ------------------------------------------------------------------

    /** 界面某行被点击：开始“志愿者 → 目标格”预览连线。 */
    public static void startPreview(int volunteerId, long packedPos) {
        previewVolunteerId = volunteerId;
        previewPosLong = packedPos;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.sendOverlayMessage(Component.translatable("message.origolds_ia.volunteer_preview_on"));
        }
    }

    /** 结束预览态（右键取消或志愿者消失）。 */
    public static void stopPreview() {
        if (previewVolunteerId >= 0) {
            previewVolunteerId = -1;
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                mc.player.sendOverlayMessage(Component.translatable("message.origolds_ia.volunteer_preview_off"));
            }
        }
    }

    /** 该行是否正处于预览（界面据此高亮）。 */
    public static boolean isPreviewing(int volunteerId, long packedPos) {
        return previewVolunteerId == volunteerId && previewPosLong == packedPos;
    }

    public static boolean isPreviewingVolunteer(int volunteerId) {
        return previewVolunteerId == volunteerId;
    }

    // ------------------------------------------------------------------
    // 快速绑定态
    // ------------------------------------------------------------------

    /** 服务端回发入口：绑定坐标列表刷新即视为（继续）处于快速绑定态。 */
    public static void handleQuickBind(OGEVolunteerQuickBindPayload payload) {
        boolean entering = quickBindVolunteerId < 0;
        quickBindVolunteerId = payload.entityId();
        quickBindBound.clear();
        quickBindBound.addAll(payload.positions());
        if (entering) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                mc.player.sendOverlayMessage(Component.translatable("message.origolds_ia.volunteer_quickbind_on"));
            }
        }
    }

    private static void exitQuickBind(Minecraft mc, boolean announce) {
        if (quickBindVolunteerId >= 0) {
            quickBindVolunteerId = -1;
            quickBindBound.clear();
            if (announce && mc.player != null) {
                mc.player.sendOverlayMessage(Component.translatable("message.origolds_ia.volunteer_quickbind_off"));
            }
        }
    }

    @SubscribeEvent
    public static void onMouseButton(Pre event) {
        if (event.getAction() != 1) {
            return;
        }
        int button = event.getButton();
        if (button != 0 && button != 1) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null || mc.getOverlay() != null || mc.player == null || mc.level == null) {
            return;
        }
        if (quickBindVolunteerId >= 0) {
            if (button == 1) {
                exitQuickBind(mc, true);
                event.setCanceled(true);
            } else {
                BlockPos aimed = aimedMount(mc);
                if (aimed != null) {
                    ClientPacketDistributor.sendToServer(
                            new OGEVolunteerBindTogglePayload(quickBindVolunteerId, aimed.asLong()));
                }
                event.setCanceled(true);
            }
        } else if (previewVolunteerId >= 0 && button == 1) {
            stopPreview();
            event.setCanceled(true);
        } else if (button == 0 && isControl(event)) {
            OGEVolunteerWatcherEntity volunteer = hitVolunteer(mc);
            if (volunteer != null) {
                ClientPacketDistributor.sendToServer(new OGEVolunteerCtrlPayload(volunteer.getId()));
                event.setCanceled(true);
            }
        }
    }

    /** 快速绑定态吞掉一切攻击/使用按键；Ctrl+左键点志愿者时阻止挥臂。 */
    @SubscribeEvent
    public static void onClickInput(InteractionKeyMappingTriggered event) {
        if (!event.isAttack() && !event.isUseItem()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null || mc.player == null || mc.level == null) {
            return;
        }
        if (quickBindVolunteerId >= 0) {
            event.setCanceled(true);
            event.setSwingHand(false);
        } else if (isControlHeld() && event.isAttack() && hitVolunteer(mc) != null) {
            event.setCanceled(true);
            event.setSwingHand(false);
        }
    }

    /** 志愿者消失或玩家跑出 32 格：自动退出两种态；顺带刷新渲染快照。 */
    @SubscribeEvent
    public static void onClientTick(Post event) {
        Minecraft mc = Minecraft.getInstance();
        refreshRenderSnapshots(mc);
        if (quickBindVolunteerId >= 0
                && (mc.level == null || mc.player == null
                || !(mc.level.getEntity(quickBindVolunteerId) instanceof OGEVolunteerWatcherEntity volunteer)
                || volunteer.distanceToSqr(mc.player) > MAX_KEEP_DISTANCE_SQ)) {
            exitQuickBind(mc, true);
        }
        if (previewVolunteerId >= 0
                && (mc.level == null || mc.player == null
                || !(mc.level.getEntity(previewVolunteerId) instanceof OGEVolunteerWatcherEntity))) {
            stopPreview();
        }
    }

    // ------------------------------------------------------------------
    // 世界线框渲染（父模组在场走 WireVisuals 通道，缺席时沿用本方法自绘）
    // ------------------------------------------------------------------

    /** 主线程每刻刷新渲染快照；两种态都没开启或世界未就绪时全部清空。 */
    private static void refreshRenderSnapshots(Minecraft mc) {
        boolean quick = quickBindVolunteerId >= 0;
        boolean preview = previewVolunteerId >= 0;
        if (!quick && !preview) {
            mountSnapshot = List.of();
            aimedMountSnapshot = null;
            previewEyeSnapshot = null;
            boundSnapshot = Set.of();
            return;
        }
        if (mc.level == null || mc.player == null) {
            mountSnapshot = List.of();
            aimedMountSnapshot = null;
            previewEyeSnapshot = null;
            return;
        }
        mountSnapshot = quick ? nearbyMounts(mc, QUICK_BIND_RANGE) : List.of();
        aimedMountSnapshot = quick ? aimedMount(mc) : null;
        boundSnapshot = quick ? Set.copyOf(quickBindBound) : Set.of();
        previewEyeSnapshot = preview
                && mc.level.getEntity(previewVolunteerId) instanceof OGEVolunteerWatcherEntity v
                ? v.getEyePosition() : null;
    }

    // ---- WireVisuals 通道访问器（见 OGEWireVisualsChannels：只读刻级快照） ----

    /** 快速绑定或预览任一状态激活中（通道 active 判据）。 */
    static boolean hasVisuals() {
        return quickBindVolunteerId >= 0 || previewVolunteerId >= 0;
    }

    /** 是否处于快速绑定态（该态开界面时不画世界框线，预览连线不受限）。 */
    static boolean isQuickBindActive() {
        return quickBindVolunteerId >= 0;
    }

    static List<BlockPos> mountSnapshot() {
        return mountSnapshot;
    }

    static BlockPos aimedMountSnapshot() {
        return aimedMountSnapshot;
    }

    static Set<Long> boundSnapshot() {
        return boundSnapshot;
    }

    static Vec3 previewEyeSnapshot() {
        return previewEyeSnapshot;
    }

    static BlockPos previewTargetPos() {
        return BlockPos.of(previewPosLong);
    }

    @SubscribeEvent
    public static void onSubmitCustomGeometry(SubmitCustomGeometryEvent event) {
        // 父模组（origolds_ar 1.2.0+）的 WireVisuals 通道可用时，快速绑定框线/填充与
        // 预览连线全部由 OGEWireVisualsChannels 经父模组管线绘制（穿墙可见、Iris 兼容），
        // 这里直接跳过避免重复绘制；父模组缺席或版本过旧时沿用本方法自绘。
        if (OGEWireVisualsBridge.isDelegated()) {
            return;
        }
        boolean quick = quickBindVolunteerId >= 0;
        boolean preview = previewVolunteerId >= 0;
        if (!quick && !preview) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || (quick && mc.screen != null)) {
            return;
        }

        // 渲染回调只读主线程每刻刷好的快照，绝不在这条路径上查世界。
        List<BlockPos> mounts = mountSnapshot;
        BlockPos aimed = aimedMountSnapshot;
        Vec3 previewEye = previewEyeSnapshot;
        Set<Long> bound = boundSnapshot;

        Vec3 cam = event.getLevelRenderState().cameraRenderState.pos;
        PoseStack poseStack = event.getPoseStack();
        SubmitNodeCollector collector = event.getSubmitNodeCollector();
        poseStack.pushPose();
        // 26.x 姿态栈是相机相对的：世界坐标先减相机位置。
        poseStack.translate(-cam.x, -cam.y, -cam.z);

        // 线框：附近可绑台 + 预览连线
        collector.submitCustomGeometry(poseStack, RenderTypes.linesTranslucent(), (pose, buffer) -> {
            for (BlockPos pos : mounts) {
                boxEdges(buffer, pose, Vec3.atCenterOf(pos), 1.0F, 1.0F, 1.0F, 0.9F);
            }
            if (previewEye != null) {
                line(buffer, pose, previewEye,
                        Vec3.atCenterOf(BlockPos.of(previewPosLong)), 1.0F, 1.0F, 1.0F, 0.9F);
            }
        });
        // 填充：准星命中高亮、已绑淡显、预览目标淡显
        collector.submitCustomGeometry(poseStack, RenderTypes.debugQuads(), (pose, buffer) -> {
            for (BlockPos pos : mounts) {
                if (pos.equals(aimed)) {
                    boxFill(buffer, pose, Vec3.atCenterOf(pos), 1.0F, 1.0F, 1.0F, 0.5F);
                } else if (bound.contains(pos.asLong())) {
                    boxFill(buffer, pose, Vec3.atCenterOf(pos), 1.0F, 1.0F, 1.0F, 0.25F);
                }
            }
            if (previewEye != null) {
                boxFill(buffer, pose, Vec3.atCenterOf(BlockPos.of(previewPosLong)), 1.0F, 1.0F, 1.0F, 0.25F);
            }
        });
        poseStack.popPose();
    }

    // ------------------------------------------------------------------
    // 查询辅助
    // ------------------------------------------------------------------

    private static OGEVolunteerWatcherEntity hitVolunteer(Minecraft mc) {
        return mc.hitResult instanceof EntityHitResult entityHit
                && entityHit.getEntity() instanceof OGEVolunteerWatcherEntity volunteer ? volunteer : null;
    }

    /** 准星射线（32 格）命中的锯片台坐标，未命中返回 null。 */
    private static BlockPos aimedMount(Minecraft mc) {
        if (mc.level == null || mc.player == null) {
            return null;
        }
        Vec3 eye = mc.player.getEyePosition();
        Vec3 look = mc.player.getLookAngle();
        BlockHitResult hit = mc.level.clip(
                new ClipContext(eye, eye.add(look.scale(QUICK_BIND_RANGE)), Block.OUTLINE, Fluid.NONE, mc.player));
        if (hit.getType() != Type.BLOCK) {
            return null;
        }
        return mc.level.getBlockState(hit.getBlockPos()).getBlock() instanceof OGESawbladeMountBlock
                ? hit.getBlockPos() : null;
    }

    private static boolean isControl(Pre event) {
        return (event.getModifiers() & GLFW_MOD_CONTROL) != 0 || isControlHeld();
    }

    private static boolean isControlHeld() {
        Window window = Minecraft.getInstance().getWindow();
        return InputConstants.isKeyDown(window, GLFW_KEY_LEFT_CONTROL)
                || InputConstants.isKeyDown(window, GLFW_KEY_RIGHT_CONTROL);
    }

    /** 玩家周围已加载区块里的锯片台位置（同刻缓存）。 */
    private static List<BlockPos> nearbyMounts(Minecraft mc, double radius) {
        long now = mc.level.getGameTime();
        if (mc.level == mountCacheLevel && now == mountCacheTick) {
            return mountCache;
        }
        mountCacheLevel = mc.level;
        mountCacheTick = now;
        BlockPos origin = mc.player.blockPosition();
        int r = (int) Math.ceil(radius);
        int minCx = (origin.getX() - r) >> 4;
        int maxCx = (origin.getX() + r) >> 4;
        int minCz = (origin.getZ() - r) >> 4;
        int maxCz = (origin.getZ() + r) >> 4;
        double r2 = radius * radius;
        List<BlockPos> found = new ArrayList<>();
        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                LevelChunk chunk = mc.level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) {
                    continue;
                }
                for (BlockEntity be : chunk.getBlockEntities().values()) {
                    if (be instanceof OGESawbladeMountBlockEntity) {
                        BlockPos pos = be.getBlockPos();
                        if (pos.distSqr(origin) <= r2) {
                            found.add(pos);
                        }
                    }
                }
            }
        }
        mountCache = found;
        return found;
    }

    // ------------------------------------------------------------------
    // 顶点辅助（POSITION_COLOR：线条需法线与线宽）
    // ------------------------------------------------------------------

    private static void lineVertex(VertexConsumer buffer, PoseStack.Pose pose,
                                   float x, float y, float z, float r, float g, float b, float alpha) {
        float lineWidth = Minecraft.getInstance().gameRenderer
                .getGameRenderState().windowRenderState.appropriateLineWidth;
        buffer.addVertex(pose, x, y, z)
                .setColor(r, g, b, alpha)
                .setNormal(pose, 0.0F, 1.0F, 0.0F)
                .setLineWidth(lineWidth);
    }

    private static void line(VertexConsumer buffer, PoseStack.Pose pose, Vec3 from, Vec3 to,
                             float r, float g, float b, float alpha) {
        lineVertex(buffer, pose, (float) from.x, (float) from.y, (float) from.z, r, g, b, alpha);
        lineVertex(buffer, pose, (float) to.x, (float) to.y, (float) to.z, r, g, b, alpha);
    }

    private static void boxEdges(VertexConsumer buffer, PoseStack.Pose pose, Vec3 center,
                                 float r, float g, float b, float alpha) {
        double x = center.x - 0.5;
        double y = center.y - 0.5;
        double z = center.z - 0.5;
        for (int[] edge : BOX_EDGES) {
            double[] p1 = BOX_CORNERS[edge[0]];
            double[] p2 = BOX_CORNERS[edge[1]];
            lineVertex(buffer, pose, (float) (x + p1[0]), (float) (y + p1[1]), (float) (z + p1[2]), r, g, b, alpha);
            lineVertex(buffer, pose, (float) (x + p2[0]), (float) (y + p2[1]), (float) (z + p2[2]), r, g, b, alpha);
        }
    }

    private static void boxFill(VertexConsumer buffer, PoseStack.Pose pose, Vec3 center,
                                float r, float g, float b, float alpha) {
        double x = center.x - 0.5;
        double y = center.y - 0.5;
        double z = center.z - 0.5;
        for (int[] face : BOX_FACES) {
            for (int idx : face) {
                double[] p = BOX_CORNERS[idx];
                buffer.addVertex(pose, (float) (x + p[0]), (float) (y + p[1]), (float) (z + p[2]))
                        .setColor(r, g, b, alpha);
            }
        }
    }
}
