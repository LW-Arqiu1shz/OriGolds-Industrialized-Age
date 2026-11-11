package cn.origolds.ia;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/**
 * 客户端渲染层注册。
 *
 * <p>金源能储电池外壳是黑色半透明磨砂玻璃。26.x 取消了模型顶层的
 * {@code render_type} 字段（写在那里会被静默忽略），半透明改由贴图自己声明：
 * 这些模型的贴图已经写成 {@code {"sprite": "...", "force_translucent": true}}，
 * 因此不再需要客户端代码调用 {@code ItemBlockRenderTypes.setRenderLayer}。</p>
 *
 * <p>这里只负责给金源自动附魔机与金源放置器挂上顶上浮空展品的渲染器。</p>
 */
@EventBusSubscriber(modid = OriGoldsIndustrializedAge.MOD_ID,
        value = Dist.CLIENT)
public final class OGEClientRender {

    private OGEClientRender() {
    }

    /** 挂载实体与方块实体的渲染器。 */
    @SubscribeEvent
    public static void registerBlockEntityRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntities.VOLUNTEER_WATCHER.get(), OGEVolunteerWatcherRenderer::new);
        event.registerEntityRenderer(ModEntities.SAWBLADE.get(), OGESawbladeRenderer::new);
        event.registerBlockEntityRenderer(
                ModBlockEntities.OGE_MECH_ENCHANTER.get(), OGEMechEnchanterRenderer::new);
        event.registerBlockEntityRenderer(
                ModBlockEntities.OGE_PLACER.get(), OGEPlacerDisplayRenderer::new);
        event.registerBlockEntityRenderer(
                ModBlockEntities.OGE_MECH_CRAFTER.get(), OGEMechCrafterRenderer::new);
        // 彩虹覆层画在“可上彩虹”的机器实体上：发生器与资源发生器共用挂点。
        event.registerBlockEntityRenderer(
                ModBlockEntities.OGE_MACHINE.get(), OGERainbowOverlayRenderer::new);
        event.registerBlockEntityRenderer(
                ModBlockEntities.OGE_RESOURCE_GENERATOR.get(), OGERainbowOverlayRenderer::new);
    }
}
