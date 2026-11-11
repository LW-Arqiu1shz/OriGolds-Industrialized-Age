package cn.origolds.ia;

import java.util.Collection;

import org.joml.Matrix4f;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.model.data.ModelData;

/**
 * 金源放置器的投影渲染：半透明虚影 +（仅编辑中的）白色线框。
 *
 * <p>渲染分两档，全部在世界透明方块之后（{@code AFTER_TRANSLUCENT_BLOCKS}）画：</p>
 * <ul>
 *   <li><b>物流显示模式</b>里，<b>手持</b>父模组连接装置时，已保存的投影只以 50% 透明度的
 *       方块模型显示，<b>不再画外框线</b>：那圈 {@code #c99951} 线框既晃眼又费帧，
 *       拿掉之后虚影本身照旧标出投影位置；</li>
 *   <li><b>投影放置模式</b>里，本次已经摆好的投影与准星上的预览同样以 50% 透明度的
 *       方块模型显示，再画一圈白色线框。这里<b>不再叠那层白色半透明高光面</b>：开着光影时
 *       那层白面才是吃帧的大头，去掉后方块本体照旧半透明，线框负责标出每一格边界。</li>
 * </ul>
 *
 * <p>投影的收集、射线命中与预览都由 {@link OGEPlacerGhosts} 提供，预览的方块状态按
 * 正常放置规则（{@link OGEPlacerPlacement}）算出，所以楼梯朝向玩家之类的行为与
 * 实际放置完全一致，也能叠在别的投影上。命中判定仍然用方块轮廓
 * （{@link net.minecraft.world.level.block.state.BlockState#getShape}），与画法无关。</p>
 */
@EventBusSubscriber(modid = OriGoldsIndustrializedAge.MOD_ID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class OGEPlacerRenderer {

    /** 已保存投影的方块模型透明度：50%。 */
    private static final float GHOST_ALPHA = 0.5F;

    /** 编辑中/预览投影的线框颜色：纯白。 */
    private static final float EDIT_FRAME_R = 1.0F;
    private static final float EDIT_FRAME_G = 1.0F;
    private static final float EDIT_FRAME_B = 1.0F;

    // 一个立方体的 8 个角与 12 条棱（角下标），用于画线框。
    private static final double[][] CORNERS = {
            {0.0, 0.0, 0.0}, {1.0, 0.0, 0.0}, {0.0, 1.0, 0.0}, {1.0, 1.0, 0.0},
            {0.0, 0.0, 1.0}, {1.0, 0.0, 1.0}, {0.0, 1.0, 1.0}, {1.0, 1.0, 1.0}
    };
    private static final int[][] EDGES = {
            {0, 1}, {1, 3}, {3, 2}, {2, 0}, {4, 5}, {5, 7}, {7, 6}, {6, 4}, {0, 4}, {1, 5}, {2, 6}, {3, 7}
    };

    private OGEPlacerRenderer() {
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.screen != null) {
            return;
        }
        Collection<OGEPlacerGhosts.Ghost> ghosts = OGEPlacerGhosts.shown(mc);
        if (ghosts.isEmpty()) {
            return;
        }
        renderGhosts(mc, event, ghosts);
    }

    private static void renderGhosts(Minecraft mc, RenderLevelStageEvent event,
                                     Collection<OGEPlacerGhosts.Ghost> ghosts) {
        PoseStack pose = event.getPoseStack();
        Vec3 cam = event.getCamera().getPosition();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();

        pose.pushPose();
        pose.translate(-cam.x, -cam.y, -cam.z);

        // 1) 方块模型：所有投影（已保存 + 编辑中/预览）都画，50% 透明度
        //    （shader 的 ColorModulator 取 setShaderColor 的 alpha）。
        //    上一轮连蓝图模式的方块本体一起删掉了，这里恢复；白色高光叠层不再画。
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, GHOST_ALPHA);
        for (OGEPlacerGhosts.Ghost ghost : ghosts) {
            pose.pushPose();
            pose.translate(ghost.pos().getX(), ghost.pos().getY(), ghost.pos().getZ());
            mc.getBlockRenderer().renderSingleBlock(ghost.state(), pose, buffers,
                    LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY,
                    ModelData.EMPTY, RenderType.translucent());
            pose.popPose();
        }
        buffers.endBatch(RenderType.translucent());
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);

        // 2) 线框：只有编辑中/预览投影画白色线框；物流显示模式里的已保存投影只留
        //    半透明虚影，不再画 #c99951 外框（那圈线既晃眼又费帧）。
        //    一条需要线框的投影都没有时整段跳过：不切 shader、不建顶点缓冲。
        boolean anyFrame = false;
        for (OGEPlacerGhosts.Ghost ghost : ghosts) {
            if (ghost.source() != OGEPlacerGhosts.Source.SAVED) {
                anyFrame = true;
                break;
            }
        }
        if (anyFrame) {
            // 直接画 POSITION_COLOR 顶点，一次 draw call 收工。
            Matrix4f matrix = pose.last().pose();
            RenderSystem.setShader(GameRenderer::getPositionColorShader);
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.depthMask(false);
            RenderSystem.disableCull();

            BufferBuilder lines = Tesselator.getInstance().begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);
            int lineVerts = 0;
            for (OGEPlacerGhosts.Ghost ghost : ghosts) {
                if (ghost.source() != OGEPlacerGhosts.Source.SAVED) {
                    lineVerts = boxEdges(matrix, lines, ghost.pos(), EDIT_FRAME_R, EDIT_FRAME_G, EDIT_FRAME_B, 0.9F, lineVerts);
                }
            }
            if (lineVerts > 0) {
                BufferUploader.drawWithShader(lines.buildOrThrow());
            }

            RenderSystem.enableCull();
            RenderSystem.depthMask(true);
            RenderSystem.disableBlend();
        }
        pose.popPose();
    }

    /** 画一个方块大小的空心外框。 */
    private static int boxEdges(Matrix4f matrix, BufferBuilder buffer, BlockPos pos,
                                float r, float g, float b, float alpha, int verts) {
        double x = pos.getX();
        double y = pos.getY();
        double z = pos.getZ();
        for (int[] edge : EDGES) {
            double[] a = CORNERS[edge[0]];
            double[] c = CORNERS[edge[1]];
            buffer.addVertex(matrix, (float) (x + a[0]), (float) (y + a[1]), (float) (z + a[2]))
                    .setColor(r, g, b, alpha);
            buffer.addVertex(matrix, (float) (x + c[0]), (float) (y + c[1]), (float) (z + c[2]))
                    .setColor(r, g, b, alpha);
            verts += 2;
        }
        return verts;
    }
}
