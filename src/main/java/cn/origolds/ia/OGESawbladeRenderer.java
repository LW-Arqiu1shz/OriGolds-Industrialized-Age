package cn.origolds.ia;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;

/**
 * 圆锯刀刃实体（{@link OGESawbladeEntity}）的客户端渲染器。
 *
 * <p>外观仍然是原来那三个方块模型，所以这里直接借 {@link BlockRenderDispatcher}
 * 把实体算出来的方块状态画出来：实体位置定在它占的那一格的脚底中心
 * （{@code x+0.5, y, z+0.5}），而方块模型的坐标系从格子角落起算，
 * 因此先 {@code translate(-0.5, 0, -0.5)} 对齐到整格。</p>
 *
 * <p>光照直接用实体自己那份 {@code packedLight}；模型是 cutout，
 * 贴图不会被当作半透明混合。</p>
 */
public final class OGESawbladeRenderer extends EntityRenderer<OGESawbladeEntity> {

    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(
            OriGoldsIndustrializedAge.MOD_ID, "textures/block/sawblade.png");

    private final BlockRenderDispatcher blockRenderer;

    public OGESawbladeRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.blockRenderer = context.getBlockRenderDispatcher();
    }

    @Override
    public void render(OGESawbladeEntity entity, float entityYaw, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffer, int packedLight) {
        // 通电时每 5 刻换一张贴图（sawblade → move1 → move2），断电定格回第一张。
        BlockState state = entity.getRenderState(entity.animationFrame());
        poseStack.pushPose();
        poseStack.translate(-0.5D, 0.0D, -0.5D);
        this.blockRenderer.renderSingleBlock(state, poseStack, buffer, packedLight,
                OverlayTexture.NO_OVERLAY, ModelData.EMPTY, RenderType.cutout());
        poseStack.popPose();
        super.render(entity, entityYaw, partialTick, poseStack, buffer, packedLight);
    }

    /** 实体不用贴图，这里只是接口要求；给刀刃贴图本身即可。 */
    @Override
    public ResourceLocation getTextureLocation(OGESawbladeEntity entity) {
        return TEXTURE;
    }
}
