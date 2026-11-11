package cn.origolds.ia;

import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/**
 * 客户端渲染层注册。
 *
 * <p>金源能储电池外壳是黑色半透明磨砂玻璃（方块模型 JSON 里
 * {@code "render_type": "minecraft:translucent"}）。这里再显式把电池方块
 * 注册到半透明渲染层，确保其方块物品在背包/手持渲染时也走混合通道，
 * 磨砂的半透明像素不会被 cutout 当作空洞丢弃。</p>
 */
@EventBusSubscriber(modid = OriGoldsIndustrializedAge.MOD_ID,
        bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class OGEClientRender {

    private OGEClientRender() {
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> ItemBlockRenderTypes.setRenderLayer(
                ModBlocks.ORIGOLD_ENERGIA_CAPACITOR.get(), RenderType.translucent()));
    }

    /** 注册方块实体和挂机志愿者的客户端渲染器。 */
    @SubscribeEvent
    public static void registerBlockEntityRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntities.VOLUNTEER_WATCHER.get(), OGEVolunteerWatcherRenderer::new);
        // 装在刃槽上的圆锯刀刃：借原来的方块模型画出薄薄一片。
        event.registerEntityRenderer(ModEntities.SAWBLADE.get(), OGESawbladeRenderer::new);
        event.registerBlockEntityRenderer(
                ModBlockEntities.OGE_MECH_ENCHANTER.get(), OGEMechEnchanterRenderer::new);
        event.registerBlockEntityRenderer(
                ModBlockEntities.OGE_PLACER.get(), OGEPlacerDisplayRenderer::new);
        // 金源自动合成机：把 9 个合成格锁定的物品画在正面的 9 个格子里。
        event.registerBlockEntityRenderer(
                ModBlockEntities.OGE_MECH_CRAFTER.get(), OGEMechCrafterRenderer::new);
        // 虹彩印记叠加层：贴了印记的机器在六个面罩一层 25% 透明度的虹彩。
        event.registerBlockEntityRenderer(
                ModBlockEntities.OGE_MACHINE.get(),
                context -> new OGERainbowOverlayRenderer<OGEMachineBlockEntity>(context));
        event.registerBlockEntityRenderer(
                ModBlockEntities.OGE_RESOURCE_GENERATOR.get(),
                context -> new OGERainbowOverlayRenderer<OGEResourceGeneratorBlockEntity>(context));
    }
}
