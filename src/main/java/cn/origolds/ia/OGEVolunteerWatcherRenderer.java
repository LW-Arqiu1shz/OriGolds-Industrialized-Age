package cn.origolds.ia;

import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.resources.ResourceLocation;

/** 用原版 Steve 宽臂玩家模型渲染挂机志愿者，并按模式切换皮肤。 */
public final class OGEVolunteerWatcherRenderer
        extends LivingEntityRenderer<OGEVolunteerWatcherEntity, PlayerModel<OGEVolunteerWatcherEntity>> {

    private static final ResourceLocation PLAYER_SKIN = ResourceLocation.fromNamespaceAndPath(
            OriGoldsIndustrializedAge.MOD_ID, "textures/entity/volunteer_watcher_skin_player.png");
    private static final ResourceLocation HATER_SKIN = ResourceLocation.fromNamespaceAndPath(
            OriGoldsIndustrializedAge.MOD_ID, "textures/entity/volunteer_watcher_skin_hater.png");

    public OGEVolunteerWatcherRenderer(EntityRendererProvider.Context context) {
        super(context, new PlayerModel<>(context.bakeLayer(ModelLayers.PLAYER), false), 0.5F);
    }

    @Override
    public ResourceLocation getTextureLocation(OGEVolunteerWatcherEntity entity) {
        return entity.getMode() == OGEVolunteerWatcherEntity.MOB_TARGET_MODE ? HATER_SKIN : PLAYER_SKIN;
    }
}
