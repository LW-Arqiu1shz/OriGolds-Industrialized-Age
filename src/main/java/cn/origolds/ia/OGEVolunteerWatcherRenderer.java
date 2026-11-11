package cn.origolds.ia;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider.Context;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.HumanoidArm;

/**
 * 志愿者观察员渲染器：人形模型（粗手臂的玩家模型几何 + 全部外罩层），按模式换贴图——
 * 玩家模式用 {@code volunteer_watcher_skin_player.png}（笑脸），
 * 仇恨模式用 {@code volunteer_watcher_skin_hater.png}（哭脸）。
 *
 * <p><b>26.1.2 陷阱：</b>{@code EntityRenderDispatcher#getRenderer(EntityRenderState)}
 * 对<b>任何</b> {@code instanceof AvatarRenderState} 的渲染状态都直接分发给原版玩家
 * 渲染器（按 {@code state.skin.model()} 选宽/窄模型），注册的实体渲染器会被整个绕过，
 * 实体就渲染成默认皮肤（{@code slim/steve}，细手臂）、两种模式同一个皮。
 * 所以这里的状态类必须挂在 {@link HumanoidRenderState} 下面，模型用
 * {@link HumanoidModel}：几何照样烘焙 {@link ModelLayers#PLAYER}（宽 4px 手臂，
 * 含帽子/袖子/夹克/裤腿子零件，整棵 root 树都会渲染），但不再触发原版玩家分发。</p>
 */
public final class OGEVolunteerWatcherRenderer
        extends MobRenderer<OGEVolunteerWatcherEntity, OGEVolunteerWatcherRenderer.WatcherRenderState,
                HumanoidModel<HumanoidRenderState>> {

    private static final Identifier PLAYER_SKIN =
            Identifier.fromNamespaceAndPath(OriGoldsIndustrializedAge.MOD_ID,
                    "textures/entity/volunteer_watcher_skin_player.png");
    private static final Identifier HATER_SKIN =
            Identifier.fromNamespaceAndPath(OriGoldsIndustrializedAge.MOD_ID,
                    "textures/entity/volunteer_watcher_skin_hater.png");

    public OGEVolunteerWatcherRenderer(Context context) {
        // PLAYER 层 = 粗手臂（slim=false 烘焙）；HumanoidModel 会渲染 root 树上的
        // 全部子零件（hat/sleeve/jacket/pants），观感与 1.21.1 的 PlayerModel 一致。
        super(context, new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER)), 0.5F);
    }

    @Override
    public WatcherRenderState createRenderState() {
        return new WatcherRenderState();
    }

    @Override
    public void extractRenderState(OGEVolunteerWatcherEntity entity, WatcherRenderState state, float partialTick) {
        super.extractRenderState(entity, state, partialTick);
        state.mobTargetMode = entity.getMode() == OGEVolunteerWatcherEntity.MOB_TARGET_MODE;
        // 站桩监工：固定空手姿势，避免模型读取未初始化字段。
        state.mainArm = HumanoidArm.RIGHT;
        state.leftArmPose = HumanoidModel.ArmPose.EMPTY;
        state.rightArmPose = HumanoidModel.ArmPose.EMPTY;
        state.isCrouching = false;
    }

    @Override
    public Identifier getTextureLocation(WatcherRenderState state) {
        return state.mobTargetMode ? HATER_SKIN : PLAYER_SKIN;
    }

    /** 渲染状态：人形状态 + 一个模式位（绝不能继承 AvatarRenderState，见类注释）。 */
    static final class WatcherRenderState extends HumanoidRenderState {
        boolean mobTargetMode;
    }
}
