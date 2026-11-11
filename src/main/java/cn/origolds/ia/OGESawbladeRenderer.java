package cn.origolds.ia;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider.Context;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.model.data.ModelData;

/**
 * 锯片实体的渲染器：每帧把 {@link OGESawbladeEntity#getRenderState(int)} 的方块状态
 * 交给 {@link MovingBlockRenderState}，由提交管线按方块模型（cutout）画出来。
 *
 * <p>旧实现直接调 {@code BlockRenderDispatcher#renderSingleBlock}；26.x 的实体渲染改成
 * "抽取状态 + 提交节点"两段式：{@link #extractRenderState} 从实体/世界读数据，
 * {@link #submit} 只交状态。实体位置在钉上时已设到格心 (x+0.5, y, z+0.5)，
 * 提交前照旧平移 (-0.5, 0, -0.5) 对准方块原点。</p>
 *
 * <p>旧版 {@code getTextureLocation}（贴图 {@code textures/block/sawblade.png}）在新管线里
 * 没有对应物：贴图由锯片方块模型自己解析，这里不再返回纹理坐标。</p>
 */
public final class OGESawbladeRenderer extends EntityRenderer<OGESawbladeEntity, OGESawbladeRenderer.SawbladeRenderState> {

    public OGESawbladeRenderer(Context context) {
        super(context);
    }

    @Override
    public SawbladeRenderState createRenderState() {
        return new SawbladeRenderState();
    }

    @Override
    public void extractRenderState(OGESawbladeEntity entity, SawbladeRenderState state, float partialTick) {
        super.extractRenderState(entity, state, partialTick);
        Level level = entity.level();
        BlockPos pos = entity.blockPosition();
        // 每帧查一次动画帧号（同步数据里的 spinning + 游戏刻），换成要画的方块状态。
        state.movingBlock.blockState = entity.getRenderState(entity.animationFrame());
        state.movingBlock.blockPos = pos;
        state.movingBlock.randomSeedPos = pos;
        if (level instanceof ClientLevel clientLevel) {
            state.movingBlock.biome = clientLevel.getBiome(pos);
            state.movingBlock.cardinalLighting = clientLevel.cardinalLighting();
            state.movingBlock.lightEngine = clientLevel.getLightEngine();
            state.movingBlock.modelData = clientLevel.getModelData(pos);
        } else {
            state.movingBlock.modelData = ModelData.EMPTY;
        }
    }

    @Override
    public void submit(SawbladeRenderState state, PoseStack poseStack, SubmitNodeCollector collector,
                       CameraRenderState cameraState) {
        poseStack.pushPose();
        poseStack.translate(-0.5, 0.0, -0.5);
        collector.submitMovingBlock(poseStack, state.movingBlock, 0);
        poseStack.popPose();
        super.submit(state, poseStack, collector, cameraState);
    }

    /** 渲染状态：只带一份可复用的移动方块状态。 */
    public static final class SawbladeRenderState extends EntityRenderState {
        final MovingBlockRenderState movingBlock = new MovingBlockRenderState();
    }
}
