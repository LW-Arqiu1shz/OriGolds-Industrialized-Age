package cn.origolds.ia;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;

/**
 * 金源机械合成台的两种显示，与 1.21.1 版保持一致：
 *
 * <ul>
 *   <li><b>顶部浮空产物</b>：机器按锁定矩阵算出当前配方的产物（服务端算好、随方块实体同步包下发），
 *       浮在机器顶上缓慢自转；没锁或没有配方时不画。姿态、自转与缩放复用
 *       {@link OGEFloatingDisplayRenderer}，与自动附魔机、放置器同一套观感。</li>
 *   <li><b>四个侧面的配方格</b>：四个侧面贴图都是一张 3×3 格子面板（格心在贴图第 5、8、11 像素），
 *       把 9 个锁定格的物品画进对应格子里；锁了什么就画什么，与配方是否有效无关。
 *       背对镜头的侧面不提交，省掉一半的物品绘制。</li>
 * </ul>
 *
 * <p>26.3 的 {@code PoseStack.mulPose} 只收矩阵，所以旋转一律写进复用的 {@link Matrix4f}，
 * 26.1.2 与 26.3 共用这份代码。</p>
 */
public final class OGEMechCrafterRenderer extends OGEFloatingDisplayRenderer<OGEMechCrafterBlockEntity> {

    /** 展品中心的悬浮高度（格）：机器是满格方块，顶面在 1.0，展品再浮起一截。 */
    private static final float DISPLAY_HEIGHT = 1.35F;

    /** 贴图的格心（像素）：第 5、8、11 个像素，换算成 0~1 的比例。 */
    private static final float[] CELL_CENTERS = {5.0F / 16.0F, 8.0F / 16.0F, 11.0F / 16.0F};

    /** 每个格子里的物品大小：贴图里格子是 2 像素宽，即 2/16 格。 */
    private static final float ITEM_SCALE = 2.0F / 16.0F;

    /** 贴到面外一点点，避免与方块表面打架。 */
    private static final float FACE_OFFSET = 0.005F;

    /** 四个水平侧面（north、east、south、west）各自的 Y 轴角度：把模型的 north 面转到该方向。 */
    private static final float[] FACE_ANGLES = {0.0F, -90.0F, 180.0F, 90.0F};

    private final Matrix4f rotation = new Matrix4f();

    public OGEMechCrafterRenderer(BlockEntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    protected float displayHeight(OGEMechCrafterBlockEntity blockEntity) {
        return DISPLAY_HEIGHT;
    }

    @Override
    protected ItemStack displayStack(OGEMechCrafterBlockEntity blockEntity) {
        return blockEntity.getPreviewResult();
    }

    @Override
    public DisplayRenderState createRenderState() {
        return new CrafterRenderState();
    }

    @Override
    public void extractRenderState(OGEMechCrafterBlockEntity blockEntity, DisplayRenderState state, float partialTicks,
                                   Vec3 cameraPosition,
                                   ModelFeatureRenderer.@Nullable CrumblingOverlay breakProgress) {
        super.extractRenderState(blockEntity, state, partialTicks, cameraPosition, breakProgress);
        CrafterRenderState crafter = (CrafterRenderState) state;
        crafter.anyLock = false;
        Level level = blockEntity.getLevel();
        for (int cell = 0; cell < OGEMechCrafterBlockEntity.CELL_COUNT; cell++) {
            ItemStack lock = blockEntity.getLock(cell);
            boolean show = level != null && !lock.isEmpty();
            crafter.lockShown[cell] = show;
            if (show) {
                this.itemModelResolver.updateForTopItem(crafter.locks[cell], lock, ItemDisplayContext.NONE,
                        level, null, 0);
                crafter.anyLock = true;
            }
        }
        // 只画朝向镜头的侧面（north / east / south / west）。
        BlockPos pos = blockEntity.getBlockPos();
        crafter.faceVisible[0] = cameraPosition.z < pos.getZ();
        crafter.faceVisible[1] = cameraPosition.x > pos.getX() + 1;
        crafter.faceVisible[2] = cameraPosition.z > pos.getZ() + 1;
        crafter.faceVisible[3] = cameraPosition.x < pos.getX();
    }

    @Override
    public void submit(DisplayRenderState state, PoseStack pose, SubmitNodeCollector submitNodeCollector,
                       CameraRenderState camera) {
        super.submit(state, pose, submitNodeCollector, camera);
        CrafterRenderState crafter = (CrafterRenderState) state;
        if (!crafter.anyLock) {
            return;
        }
        for (int face = 0; face < FACE_ANGLES.length; face++) {
            if (!crafter.faceVisible[face]) {
                continue;
            }
            for (int cell = 0; cell < OGEMechCrafterBlockEntity.CELL_COUNT; cell++) {
                if (!crafter.lockShown[cell]) {
                    continue;
                }
                int col = cell % OGEMechCrafterBlockEntity.GRID_SIZE;
                int row = cell / OGEMechCrafterBlockEntity.GRID_SIZE;
                // 基准（模型 north 面）上：x = 1 - u、y = 1 - v（贴图从正面看不会被镜像）。
                float x = 1.0F - CELL_CENTERS[col];
                float y = 1.0F - CELL_CENTERS[row];

                pose.pushPose();
                // 以方块中心为轴把模型的 north 面转到当前这个侧面。
                pose.translate(0.5F, 0.5F, 0.5F);
                pose.mulPose(this.rotation.identity().rotateY(FACE_ANGLES[face] * Mth.DEG_TO_RAD));
                pose.translate(-0.5F, -0.5F, -0.5F);
                // 挪到格子中心、贴到面外一点点，再翻 180° 让正面朝外。
                pose.translate(x, y, -FACE_OFFSET);
                pose.mulPose(this.rotation.identity().rotateY(180.0F * Mth.DEG_TO_RAD));
                pose.scale(ITEM_SCALE, ITEM_SCALE, ITEM_SCALE);
                crafter.locks[cell].submit(pose, submitNodeCollector, state.lightCoords, OverlayTexture.NO_OVERLAY, 0);
                pose.popPose();
            }
        }
    }

    /** 浮空展品之外，再带上 9 个锁定格的物品模型与四个侧面的可见性。 */
    public static final class CrafterRenderState extends DisplayRenderState {
        public final ItemStackRenderState[] locks = new ItemStackRenderState[OGEMechCrafterBlockEntity.CELL_COUNT];
        public final boolean[] lockShown = new boolean[OGEMechCrafterBlockEntity.CELL_COUNT];
        public final boolean[] faceVisible = new boolean[4];
        public boolean anyLock;

        public CrafterRenderState() {
            for (int i = 0; i < this.locks.length; i++) {
                this.locks[i] = new ItemStackRenderState();
            }
        }
    }
}
