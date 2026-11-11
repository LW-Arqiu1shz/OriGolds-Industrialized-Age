package cn.origolds.ia;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Matrix4f;

/**
 * 挂机志愿者 / 金源圆锯刃槽的绑定交互（客户端判定、发包与快捷绑定线框）。
 *
 * <ul>
 *   <li>普通右键志愿者（不按 Ctrl）：原版交互直接到服务端，
 *       {@link OGEVolunteerWatcherEntity#mobInteract} 打开配置界面；</li>
 *   <li>Ctrl+左键志愿者：请求进入 {@code 快捷绑定} 模式；</li>
 *   <li>快捷绑定模式里：32 格半径内所有刃槽画白色线框（已绑定的在线框内加 25% 白填充），
 *       准星对准的那个加 50% 白填充，左键点击它切换绑定状态；右键退出。
 *       模式期间左右键只做这两件事，其余一律吃掉，避免误触方块 / 生物。</li>
 * </ul>
 *
 * <p>与 {@link OGEBlowerClient} 同一套写法：Ctrl 不随原版交互包上送，
 * 只能在客户端按修饰键 + 准星命中判定后发专用报文，服务端复核
 * （见 {@link OGEVolunteerBindManager}）。{@code MouseButton.Pre} 吃掉按下瞬间，
 * {@code InteractionKeyMappingTriggered} 吃掉按住不放时每刻重复的攻击/使用。</p>
 */
@EventBusSubscriber(modid = OriGoldsIndustrializedAge.MOD_ID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class OGEVolunteerBindClient {

    private static final int GLFW_KEY_LEFT_CONTROL = 341;
    private static final int GLFW_KEY_RIGHT_CONTROL = 345;
    private static final int GLFW_MOD_CONTROL = 2;

    /** 快捷绑定模式的显示与操作半径。 */
    private static final double QUICK_BIND_RANGE = 32.0;

    /** 快捷绑定模式的目标志愿者实体 id（-1 = 未开启）。 */
    private static int quickBindVolunteerId = -1;
    /** 该志愿者已绑定的刃槽（服务端下发，坐标打包成 long）。 */
    private static final Set<Long> quickBindBound = new HashSet<>();

    /** 预览模式：配置界面里右键某行三横杠时，连线并高亮那一行对应的刃槽（-1 = 未开启）。 */
    private static int previewVolunteerId = -1;
    private static long previewPosLong;

    /** 进入 / 切换预览模式（配置界面右键三横杠调用）。 */
    public static void startPreview(int volunteerId, long packedPos) {
        previewVolunteerId = volunteerId;
        previewPosLong = packedPos;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(
                    Component.translatable("message.origolds_ia.volunteer_preview_on"), true);
        }
    }

    /** 退出预览模式。 */
    public static void stopPreview() {
        if (previewVolunteerId < 0) {
            return;
        }
        previewVolunteerId = -1;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(
                    Component.translatable("message.origolds_ia.volunteer_preview_off"), true);
        }
    }

    /** 当前是否正在预览指定刃槽。 */
    public static boolean isPreviewing(int volunteerId, long packedPos) {
        return previewVolunteerId == volunteerId && previewPosLong == packedPos;
    }

    /** 当前是否正在预览这个志愿者的某个刃槽（界面据此决定是否画常驻提示）。 */
    public static boolean isPreviewingVolunteer(int volunteerId) {
        return previewVolunteerId == volunteerId;
    }

    /** 附近刃槽列表的每刻缓存（多帧渲染只扫一次区块）。 */
    private static Level mountCacheLevel;
    private static long mountCacheTick = -1L;
    private static List<BlockPos> mountCache = List.of();

    private static final int[][] BOX_EDGES = new int[][]{{0, 1}, {1, 3}, {3, 2}, {2, 0}, {4, 5}, {5, 7}, {7, 6}, {6, 4}, {0, 4}, {1, 5}, {2, 6}, {3, 7}};
    private static final double[][] BOX_CORNERS = new double[][]{{0.0, 0.0, 0.0}, {1.0, 0.0, 0.0}, {0.0, 1.0, 0.0}, {1.0, 1.0, 0.0}, {0.0, 0.0, 1.0}, {1.0, 0.0, 1.0}, {0.0, 1.0, 1.0}, {1.0, 1.0, 1.0}};
    private static final int[][] BOX_FACES = new int[][]{{0, 1, 3, 2}, {4, 6, 7, 5}, {0, 2, 6, 4}, {1, 5, 7, 3}, {0, 4, 5, 1}, {2, 3, 7, 6}};

    private OGEVolunteerBindClient() {
    }

    // ------------------------------------------------------------------
    // 服务端下发的快捷绑定表
    // ------------------------------------------------------------------

    /** 服务端下发绑定表：进入 / 刷新快捷绑定模式。 */
    public static void handleQuickBind(OGEVolunteerQuickBindPayload payload) {
        boolean entering = quickBindVolunteerId < 0;
        quickBindVolunteerId = payload.entityId();
        quickBindBound.clear();
        quickBindBound.addAll(payload.positions());
        if (entering) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                mc.player.displayClientMessage(
                        Component.translatable("message.origolds_ia.volunteer_quickbind_on"), true);
            }
        }
    }

    /** 退出快捷绑定模式；{@code announce} 为真时在动作栏提示一下。 */
    private static void exitQuickBind(Minecraft mc, boolean announce) {
        if (quickBindVolunteerId < 0) {
            return;
        }
        quickBindVolunteerId = -1;
        quickBindBound.clear();
        if (announce && mc.player != null) {
            mc.player.displayClientMessage(
                    Component.translatable("message.origolds_ia.volunteer_quickbind_off"), true);
        }
    }

    // ------------------------------------------------------------------
    // 鼠标交互
    // ------------------------------------------------------------------

    @SubscribeEvent
    public static void onMouseButton(InputEvent.MouseButton.Pre event) {
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
            // 快捷绑定模式：左右键全部由这里接管，只有"左键点刃槽线框（切换绑定）"
            // 与"右键（退出）"会做事，其余按下瞬间就吃掉，绝不透给方块 / 生物交互。
            if (button == 1) {
                exitQuickBind(mc, true);
                event.setCanceled(true);
                return;
            }
            BlockPos aimed = aimedMount(mc);
            if (aimed != null) {
                PacketDistributor.sendToServer(new OGEVolunteerBindTogglePayload(
                        quickBindVolunteerId, aimed.asLong()));
            }
            event.setCanceled(true);
            return;
        }

        // 预览模式：配置界面在进入时已经关掉，这里用右键退出预览。
        if (previewVolunteerId >= 0 && button == 1) {
            stopPreview();
            event.setCanceled(true);
            return;
        }

        // Ctrl+左键志愿者：进入快捷绑定模式。
        if (button == 0 && isControl(event) && hitVolunteer(mc) instanceof OGEVolunteerWatcherEntity volunteer) {
            PacketDistributor.sendToServer(new OGEVolunteerCtrlPayload(volunteer.getId()));
            event.setCanceled(true);
        }
    }

    /** 按住不放时每刻重复的攻击/使用调用：快捷绑定模式里全部吃掉，Ctrl+左键志愿者也吃掉。 */
    @SubscribeEvent
    public static void onClickInput(InputEvent.InteractionKeyMappingTriggered event) {
        if (!event.isAttack() && !event.isUseItem()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null || mc.player == null || mc.level == null) {
            return;
        }
        if (quickBindVolunteerId >= 0) {
            // 模式内左右键已由 MouseButton.Pre 处理，这里再吃掉按住不放的重复调用，
            // 免得退出用的右键顺带打开配置界面、或攻击/使用伤到东西。
            event.setCanceled(true);
            event.setSwingHand(false);
            return;
        }
        if (!isControlHeld()) {
            return;
        }
        if (event.isAttack() && hitVolunteer(mc) != null) {
            event.setCanceled(true);
            event.setSwingHand(false);
        }
    }

    /** 每一客户端刻：志愿者没了 / 拉出 32 格就自动退出快捷绑定模式；预览目标没了也退出。 */
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (quickBindVolunteerId >= 0
                && (mc.level == null || mc.player == null
                || !(mc.level.getEntity(quickBindVolunteerId) instanceof OGEVolunteerWatcherEntity volunteer)
                || volunteer.distanceToSqr(mc.player) > QUICK_BIND_RANGE * QUICK_BIND_RANGE)) {
            exitQuickBind(mc, true);
        }
        if (previewVolunteerId >= 0
                && (mc.level == null || mc.player == null
                || !(mc.level.getEntity(previewVolunteerId) instanceof OGEVolunteerWatcherEntity))) {
            stopPreview();
        }
    }

    /** 准星指向的挂机志愿者；没有则 {@code null}。 */
    private static OGEVolunteerWatcherEntity hitVolunteer(Minecraft mc) {
        if (mc.hitResult instanceof net.minecraft.world.phys.EntityHitResult entityHit
                && entityHit.getEntity() instanceof OGEVolunteerWatcherEntity volunteer) {
            return volunteer;
        }
        return null;
    }

    /** 准星方向上 32 格内命中的金源圆锯刃槽；没有则 {@code null}。 */
    private static BlockPos aimedMount(Minecraft mc) {
        if (mc.level == null || mc.player == null) {
            return null;
        }
        Vec3 eye = mc.player.getEyePosition();
        Vec3 look = mc.player.getLookAngle();
        BlockHitResult hit = mc.level.clip(new ClipContext(eye, eye.add(look.scale(QUICK_BIND_RANGE)),
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
        if (hit.getType() != HitResult.Type.BLOCK) {
            return null;
        }
        return mc.level.getBlockState(hit.getBlockPos()).getBlock() instanceof OGESawbladeMountBlock
                ? hit.getBlockPos() : null;
    }

    private static boolean isControl(InputEvent.MouseButton.Pre event) {
        if ((event.getModifiers() & GLFW_MOD_CONTROL) != 0) {
            return true;
        }
        return isControlHeld();
    }

    private static boolean isControlHeld() {
        long window = Minecraft.getInstance().getWindow().getWindow();
        return InputConstants.isKeyDown(window, GLFW_KEY_LEFT_CONTROL)
                || InputConstants.isKeyDown(window, GLFW_KEY_RIGHT_CONTROL);
    }

    // ------------------------------------------------------------------
    // 白色线框渲染
    // ------------------------------------------------------------------

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        boolean quick = quickBindVolunteerId >= 0;
        boolean preview = previewVolunteerId >= 0;
        if ((!quick && !preview) || event.getStage() != RenderLevelStageEvent.Stage.AFTER_WEATHER) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }
        // 快捷绑定是世界里的操作模式，界面打开时不该出现；预览模式正是从配置界面里开的。
        if (quick && mc.screen != null) {
            return;
        }
        List<BlockPos> mounts = quick ? nearbyMounts(mc, QUICK_BIND_RANGE) : List.of();
        BlockPos aimed = quick ? aimedMount(mc) : null;
        OGEVolunteerWatcherEntity previewVolunteer = preview
                && mc.level.getEntity(previewVolunteerId) instanceof OGEVolunteerWatcherEntity v ? v : null;

        Vec3 cam = event.getCamera().getPosition();
        PoseStack poseStack = event.getPoseStack();
        poseStack.pushPose();
        poseStack.translate(-cam.x, -cam.y, -cam.z);
        Matrix4f matrix = poseStack.last().pose();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.depthMask(false);
        RenderSystem.disableDepthTest();
        RenderSystem.disableCull();

        BufferBuilder lines = Tesselator.getInstance().begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);
        int lineVerts = 0;
        for (BlockPos pos : mounts) {
            lineVerts = boxEdges(matrix, lines, Vec3.atCenterOf(pos), 1.0f, 1.0f, 1.0f, 0.9f, lineVerts);
        }
        if (previewVolunteer != null) {
            // 预览模式：从志愿者拉到那一行对应的刃槽。
            lineVerts = line(matrix, lines, previewVolunteer.getEyePosition(),
                    Vec3.atCenterOf(BlockPos.of(previewPosLong)), 1.0f, 1.0f, 1.0f, 0.9f, lineVerts);
        }
        if (lineVerts > 0) {
            BufferUploader.drawWithShader(lines.buildOrThrow());
        }

        BufferBuilder quads = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        int quadVerts = 0;
        for (BlockPos pos : mounts) {
            if (pos.equals(aimed)) {
                quadVerts = boxFill(matrix, quads, Vec3.atCenterOf(pos), 1.0f, 1.0f, 1.0f, 0.5f, quadVerts);
            } else if (quickBindBound.contains(pos.asLong())) {
                quadVerts = boxFill(matrix, quads, Vec3.atCenterOf(pos), 1.0f, 1.0f, 1.0f, 0.25f, quadVerts);
            }
        }
        if (previewVolunteer != null) {
            quadVerts = boxFill(matrix, quads, Vec3.atCenterOf(BlockPos.of(previewPosLong)),
                    1.0f, 1.0f, 1.0f, 0.25f, quadVerts);
        }
        if (quadVerts > 0) {
            BufferUploader.drawWithShader(quads.buildOrThrow());
        }

        RenderSystem.enableCull();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
        poseStack.popPose();
    }

    /**
     * 玩家 32 格内已加载区块里的所有金源圆锯刃槽；同一游戏刻内只重扫一次，
     * 避免多帧渲染反复遍历区块。
     */
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

    private static int boxEdges(Matrix4f m, BufferBuilder buffer, Vec3 center,
                                float r, float g, float b, float alpha, int verts) {
        double x = center.x - 0.5;
        double y = center.y - 0.5;
        double z = center.z - 0.5;
        for (int[] edge : BOX_EDGES) {
            double[] p1 = BOX_CORNERS[edge[0]];
            double[] p2 = BOX_CORNERS[edge[1]];
            buffer.addVertex(m, (float) (x + p1[0]), (float) (y + p1[1]), (float) (z + p1[2])).setColor(r, g, b, alpha);
            buffer.addVertex(m, (float) (x + p2[0]), (float) (y + p2[1]), (float) (z + p2[2])).setColor(r, g, b, alpha);
            verts += 2;
        }
        return verts;
    }

    /** 两点之间的一条线段（DEBUG_LINES 顶点）。 */
    private static int line(Matrix4f m, BufferBuilder buffer, Vec3 from, Vec3 to,
                            float r, float g, float b, float alpha, int verts) {
        buffer.addVertex(m, (float) from.x, (float) from.y, (float) from.z).setColor(r, g, b, alpha);
        buffer.addVertex(m, (float) to.x, (float) to.y, (float) to.z).setColor(r, g, b, alpha);
        return verts + 2;
    }

    private static int boxFill(Matrix4f m, BufferBuilder buffer, Vec3 center,
                               float r, float g, float b, float alpha, int verts) {
        double x = center.x - 0.5;
        double y = center.y - 0.5;
        double z = center.z - 0.5;
        for (int[] face : BOX_FACES) {
            for (int idx : face) {
                double[] p = BOX_CORNERS[idx];
                buffer.addVertex(m, (float) (x + p[0]), (float) (y + p[1]), (float) (z + p[2])).setColor(r, g, b, alpha);
                ++verts;
            }
        }
        return verts;
    }
}
