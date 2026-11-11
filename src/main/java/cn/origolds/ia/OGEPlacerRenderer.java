package cn.origolds.ia;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;

/**
 * 金源放置器的投影渲染：半透明虚影 + 白色线框 + #c99951 外框线。
 *
 * <p>渲染分两部分，统一提交给 26.x 的新渲染管线（{@link SubmitCustomGeometryEvent}）：</p>
 * <ul>
 *   <li><b>物流显示模式</b>里，<b>手持</b>父模组连接装置时，已保存的投影以 50% 透明度的
 *       方块模型显示出来，并在它要占的那一格画一圈 {@code #c99951} 外框线；</li>
 *   <li><b>投影放置模式</b>里，本次已经摆好的投影与准星上的预览同样以 50% 透明度的
 *       方块模型显示，再画一圈白色线框。这里<b>不再叠那层白色半透明高光面</b>：开着光影时
 *       那层白面才是吃帧的大头，去掉后方块本体照旧半透明，线框负责标出每一格边界。</li>
 * </ul>
 *
 * <p>投影的收集、射线命中与预览都由 {@link OGEPlacerGhosts} 提供，预览的方块状态按
 * 正常放置规则（{@link OGEPlacerPlacement}）算出，所以楼梯朝向玩家之类的行为与
 * 实际放置完全一致，也能叠在别的投影上。命中判定仍然用方块轮廓
 * （{@link net.minecraft.world.level.block.state.BlockState#getShape}），与画法无关。</p>
 *
 * <p>半透明靠顶点颜色里的 alpha 实现：把方块模型的每个四边形取出来，用
 * {@link QuadInstance} 把四个顶点都染成 50% 白，再交给半透明方块层渲染，
 * 于是贴图本身的不透明像素也变成半透明虚影。</p>
 */
@EventBusSubscriber(modid = OriGoldsIndustrializedAge.MOD_ID, value = Dist.CLIENT)
public final class OGEPlacerRenderer {

    /** 投影方块模型的顶点颜色：50% 不透明度的白（与贴图相乘）。 */
    private static final int GHOST_COLOR = 0x80FFFFFF;

    /** 已保存投影的外框线颜色 #c99951，alpha 0.9。 */
    private static final int FRAME_COLOR = 0xE6C99951;

    /** 编辑中/预览投影的线框颜色：纯白，alpha 0.9。 */
    private static final int EDIT_FRAME_COLOR = 0xE6FFFFFF;

    // 一个立方体的 8 个角与 12 条棱（角下标），用于画线框。
    private static final double[][] CORNERS = {
            {0.0, 0.0, 0.0}, {1.0, 0.0, 0.0}, {0.0, 1.0, 0.0}, {1.0, 1.0, 0.0},
            {0.0, 0.0, 1.0}, {1.0, 0.0, 1.0}, {0.0, 1.0, 1.0}, {1.0, 1.0, 1.0}
    };
    private static final int[][] EDGES = {
            {0, 1}, {1, 3}, {3, 2}, {2, 0}, {4, 5}, {5, 7}, {7, 6}, {6, 4}, {0, 4}, {1, 5}, {2, 6}, {3, 7}
    };

    /** 渲染事件在客户端渲染线程执行，可复用可见列表与包围盒，避免每帧分配。 */
    private static final List<OGEPlacerGhosts.Ghost> VISIBLE_GHOSTS = new ArrayList<>();
    private static final AABB CULL_BOX = new AABB(0.0, 0.0, 0.0, 1.0, 1.0, 1.0);

    private OGEPlacerRenderer() {
    }

    @SubscribeEvent
    public static void onSubmitCustomGeometry(SubmitCustomGeometryEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.screen != null) {
            return;
        }
        Collection<OGEPlacerGhosts.Ghost> ghosts = OGEPlacerGhosts.shown(mc);
        if (ghosts.isEmpty()) {
            return;
        }
        Vec3 cam = event.getLevelRenderState().cameraRenderState.pos;
        Frustum frustum = event.getLevelRenderState().cameraRenderState.cullFrustum;
        VISIBLE_GHOSTS.clear();
        for (OGEPlacerGhosts.Ghost ghost : ghosts) {
            if (isVisible(ghost.pos(), frustum)) {
                VISIBLE_GHOSTS.add(ghost);
            }
        }
        if (!VISIBLE_GHOSTS.isEmpty()) {
            // SubmitNodeCollector 延后执行顶点回调；使用本帧快照，避免下一帧复用列表时覆盖数据。
            submitGhosts(mc, event, List.copyOf(VISIBLE_GHOSTS), cam);
        }
    }

    private static boolean isVisible(BlockPos pos, Frustum frustum) {
        if (frustum == null) {
            return true;
        }
        // 适当膨胀以覆盖围栏、植物等可能伸出方块边界的模型。
        CULL_BOX.setMinX(pos.getX() - 0.5).setMinY(pos.getY() - 0.5).setMinZ(pos.getZ() - 0.5)
                .setMaxX(pos.getX() + 1.5).setMaxY(pos.getY() + 1.5).setMaxZ(pos.getZ() + 1.5);
        return frustum.isVisible(CULL_BOX);
    }

    private static void submitGhosts(Minecraft mc, SubmitCustomGeometryEvent event,
                                     Collection<OGEPlacerGhosts.Ghost> ghosts, Vec3 cam) {
        PoseStack pose = event.getPoseStack();
        SubmitNodeCollector collector = event.getSubmitNodeCollector();
        RandomSource random = RandomSource.create();

        // 1) 方块模型：所有投影（已保存 + 编辑中/预览）都画，50% 透明度。
        for (OGEPlacerGhosts.Ghost ghost : ghosts) {
            BlockState state = ghost.state();
            BlockPos pos = ghost.pos();
            BlockStateModel model = mc.getModelManager().getBlockStateModelSet().get(state);
            List<BlockStateModelPart> parts = new ArrayList<>();
            random.setSeed(pos.asLong());
            model.collectParts(mc.level, pos, state, random, parts);
            if (parts.isEmpty()) {
                continue;
            }
            pose.pushPose();
            pose.translate(pos.getX() - cam.x, pos.getY() - cam.y, pos.getZ() - cam.z);
            collector.submitCustomGeometry(pose, Sheets.translucentBlockSheet(), (localPose, buffer) -> {
                QuadInstance quad = new QuadInstance();
                quad.setColor(GHOST_COLOR);
                for (BlockStateModelPart part : parts) {
                    for (Direction direction : Direction.values()) {
                        for (BakedQuad baked : part.getQuads(direction)) {
                            buffer.putBakedQuad(localPose, baked, quad);
                        }
                    }
                    for (BakedQuad baked : part.getQuads(null)) {
                        buffer.putBakedQuad(localPose, baked, quad);
                    }
                }
            });
            pose.popPose();
        }

        // 2) 线框：已保存投影画 #c99951 外框，编辑中/预览画白色线框。
        // 父模组的 WireVisuals 通道可用时这部分改由 OGEWireVisualsChannels 经父模组管线
        // 绘制（穿墙可见、Iris 兼容），这里只跳过线框、半透明方块虚影仍由本类提交。
        if (OGEWireVisualsBridge.isDelegated()) {
            return;
        }
        float lineWidth = mc.gameRenderer.getGameRenderState().windowRenderState.appropriateLineWidth;
        // 所有可见投影的线框共用一个提交节点；之前每个方块各提交一次，投影多时
        // 会造成大量小型 translucent draw submission，光影下的成本尤其明显。
        collector.submitCustomGeometry(pose, RenderTypes.linesTranslucent(), (localPose, buffer) -> {
            for (OGEPlacerGhosts.Ghost ghost : ghosts) {
                int color = ghost.source() == OGEPlacerGhosts.Source.SAVED ? FRAME_COLOR : EDIT_FRAME_COLOR;
                BlockPos pos = ghost.pos();
                float ox = (float) (pos.getX() - cam.x);
                float oy = (float) (pos.getY() - cam.y);
                float oz = (float) (pos.getZ() - cam.z);
                for (int[] edge : EDGES) {
                    double[] a = CORNERS[edge[0]];
                    double[] c = CORNERS[edge[1]];
                    lineVertex(buffer, localPose, ox + (float) a[0], oy + (float) a[1], oz + (float) a[2], color, lineWidth);
                    lineVertex(buffer, localPose, ox + (float) c[0], oy + (float) c[1], oz + (float) c[2], color, lineWidth);
                }
            }
        });
    }

    /** 写一个线框端点：位置 + 颜色 + 法线 + 线宽（线渲染类型的顶点格式要求）。 */
    private static void lineVertex(VertexConsumer buffer, PoseStack.Pose pose,
                                   float x, float y, float z, int color, float lineWidth) {
        buffer.addVertex(pose, x, y, z)
                .setColor(color)
                .setNormal(pose, 0.0F, 1.0F, 0.0F)
                .setLineWidth(lineWidth);
    }
}
