package cn.origolds.ia;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.model.data.ModelData;

/**
 * 彩虹覆层渲染器：机器被彩虹标记点亮时，在机器格子上叠画一层 rainbow_overlay 方块模型。
 *
 * <p>挂在发生器（{@link ModBlockEntities#OGE_MACHINE}）与资源发生器
 * （{@link ModBlockEntities#OGE_RESOURCE_GENERATOR}）两种实体类型上；档位由实体的
 * {@link IRainbowMarked#getRainbowTier()} 给出（未上标记时什么都不画）。</p>
 *
 * <p>26.x 的方块实体渲染拆成“抽取状态 + 提交节点”两段：{@link #extractRenderState}
 * 把档位换算成要画的方块状态，{@link #submit} 用 {@link MovingBlockRenderState}
 * 走方块模型提交管线（同 {@link OGESawbladeRenderer} 的做法）。</p>
 *
 * @param <T> 实现了 {@link IRainbowMarked} 的方块实体
 */
public final class OGERainbowOverlayRenderer<T extends BlockEntity & IRainbowMarked>
        implements BlockEntityRenderer<T, OGERainbowOverlayRenderer.OverlayRenderState> {

    public OGERainbowOverlayRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public OverlayRenderState createRenderState() {
        return new OverlayRenderState();
    }

    @Override
    public void extractRenderState(T blockEntity, OverlayRenderState state, float partialTick,
                                   Vec3 cameraPosition, ModelFeatureRenderer.CrumblingOverlay breakProgress) {
        int tier = blockEntity.getRainbowTier();
        if (tier < 0) {
            state.visible = false;
            return;
        }
        BlockState overlay = ModBlocks.RAINBOW_OVERLAY.get().defaultBlockState()
                .setValue(OGERainbowOverlayBlock.TIER, OGERainbowMarks.clampTier(tier));
        BlockPos pos = blockEntity.getBlockPos();
        state.movingBlock.blockState = overlay;
        state.movingBlock.blockPos = pos;
        state.movingBlock.randomSeedPos = pos;
        if (blockEntity.getLevel() instanceof ClientLevel clientLevel) {
            state.movingBlock.biome = clientLevel.getBiome(pos);
            state.movingBlock.cardinalLighting = clientLevel.cardinalLighting();
            state.movingBlock.lightEngine = clientLevel.getLightEngine();
            state.movingBlock.modelData = clientLevel.getModelData(pos);
        } else {
            state.movingBlock.modelData = ModelData.EMPTY;
        }
        state.visible = true;
    }

    @Override
    public void submit(OverlayRenderState state, PoseStack poseStack, SubmitNodeCollector collector,
                       CameraRenderState cameraState) {
        if (state.visible) {
            collector.submitMovingBlock(poseStack, state.movingBlock, 0);
        }
    }

    /** 渲染状态：可见标志 + 一份可复用的移动方块状态。 */
    public static final class OverlayRenderState extends BlockEntityRenderState {
        final MovingBlockRenderState movingBlock = new MovingBlockRenderState();
        boolean visible;
    }
}
