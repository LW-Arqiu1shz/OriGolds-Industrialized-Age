package cn.origolds.ia;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;

/**
 * 虹彩印记叠加层渲染：给贴了印记的机器在六个面上罩一层 25% 透明度的虹彩印记。
 *
 * <p>画的是 {@link OGERainbowOverlayBlock} 里按档位取到的整块立方体模型（模型本身
 * {@code render_type} 是 translucent，坐标向外涨了 0.01 格避免与原方块表面打架），
 * 贴图 {@code rainbow_overlay_5x/20x/100x} 的像素已经自带 25% 透明度，所以这里
 * 不必再动 {@code setShaderColor} 的全局状态，光影（Iris/Oculus 等）与非光影下
 * 表现一致。</p>
 *
 * <p>同一个渲染器注册给金源能量机器与资源发生器两种方块实体，靠
 * {@link IRainbowMarked} 取档位。</p>
 */
public final class OGERainbowOverlayRenderer<T extends BlockEntity & IRainbowMarked>
        implements BlockEntityRenderer<T> {

    public OGERainbowOverlayRenderer(BlockEntityRendererProvider.Context context) {
        // 只要方块模型，不需要从 Context 里预取资源。
    }

    @Override
    public void render(T blockEntity, float partialTick, PoseStack pose, MultiBufferSource buffers,
                       int packedLight, int packedOverlay) {
        int tier = blockEntity.getRainbowTier();
        if (tier < 0) {
            return;
        }
        BlockState overlay = ModBlocks.RAINBOW_OVERLAY.get().defaultBlockState()
                .setValue(OGERainbowOverlayBlock.TIER, OGERainbowMarks.clampTier(tier));
        Minecraft.getInstance().getBlockRenderer().renderSingleBlock(overlay, pose, buffers,
                packedLight, packedOverlay, ModelData.EMPTY, RenderType.translucent());
    }
}
