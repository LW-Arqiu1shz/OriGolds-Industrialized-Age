package cn.origolds.ia;

import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * 金源自动合成机的两种显示：
 *
 * <ul>
 *   <li><b>顶部浮空产物</b>：服务端按锁定矩阵算好当前配方的产物、随方块数据同步下来，
 *       浮在机器顶上缓慢自转；没锁或没有配方时不画。姿态、自转与缩放复用
 *       {@link OGEFloatingDisplayRenderer}，与自动附魔机、放置器同一套观感。</li>
 *   <li><b>四个侧面的配方格</b>：四个侧面贴图都是 {@code mechcrafter_front}，里面有一张 3×3
 *       的格子，9 个金色格心在贴图的第 5、8、11 个像素（共 16 像素）上。这里以模型的 north 面
 *       为基准算出每个格心的位置，再把整份 9 格按 Y 轴旋到四个水平面中的每一个上；
 *       配方是否有效都不影响显示，锁了什么就画什么。背对镜头的侧面不画。</li>
 * </ul>
 *
 * <p>物品用 {@link ItemDisplayContext#NONE} 渲染：它会把模型以原点为中心、1 格见方地摆
 * 好，这里按 2/16 缩放就正好填满一个格子。</p>
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
    public void render(OGEMechCrafterBlockEntity blockEntity, float partialTick, PoseStack pose,
                       MultiBufferSource buffers, int packedLight, int packedOverlay) {
        // 1) 顶部浮空产物
        super.render(blockEntity, partialTick, pose, buffers, packedLight, packedOverlay);

        // 2) 四个侧面的配方格
        Level level = blockEntity.getLevel();
        if (level == null) {
            return;
        }
        List<ItemStack> locks = blockEntity.getLocks();
        boolean any = false;
        for (ItemStack lock : locks) {
            any |= !lock.isEmpty();
        }
        if (!any) {
            return;
        }
        BlockPos pos = blockEntity.getBlockPos();
        Vec3 cam = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        boolean[] faceVisible = {
                cam.z < pos.getZ(), cam.x > pos.getX() + 1, cam.z > pos.getZ() + 1, cam.x < pos.getX()};

        for (int face = 0; face < FACE_ANGLES.length; face++) {
            if (!faceVisible[face]) {
                continue;
            }
            for (int cell = 0; cell < OGEMechCrafterBlockEntity.CELL_COUNT; cell++) {
                ItemStack lock = locks.get(cell);
                if (lock.isEmpty()) {
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
                pose.mulPose(Axis.YP.rotationDegrees(FACE_ANGLES[face]));
                pose.translate(-0.5F, -0.5F, -0.5F);
                // 挪到格子中心、贴到面外一点点，再翻 180° 让正面朝外。
                pose.translate(x, y, -FACE_OFFSET);
                pose.mulPose(Axis.YP.rotationDegrees(180.0F));
                pose.scale(ITEM_SCALE, ITEM_SCALE, ITEM_SCALE);
                Minecraft.getInstance().getItemRenderer().renderStatic(
                        lock, ItemDisplayContext.NONE, packedLight, packedOverlay,
                        pose, buffers, level, 0);
                pose.popPose();
            }
        }
    }
}
