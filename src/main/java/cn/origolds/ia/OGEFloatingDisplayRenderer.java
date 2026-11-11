package cn.origolds.ia;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * “方块顶上浮着一件自转展品”的公共渲染基类。
 *
 * <p>本模组有多台机器要在顶上展示一件物品（附魔机展示槽位 0 的东西、放置器展示
 * 绑定方块或默认的魔金之核），浮空的姿态、自转与缩放规则完全一致，因此把这段
 * 逻辑集中在这里，各机器只需要说清楚两件事：</p>
 * <ul>
 *   <li>{@link #displayHeight(BlockEntity)}：展品悬在多高；</li>
 *   <li>{@link #displayStack(BlockEntity)}：现在要展示什么（空堆表示不画）。</li>
 * </ul>
 *
 * <p><b>缩放分两档</b>：只有扁平贴图的物品（剑、苹果、附魔书这类）本身很薄，
 * 放大到 <b>0.6 倍</b>；带立体模型的物品（方块、自定义 3D 模型）体积更饱满，
 * 缩到 <b>0.4 倍</b>。判定用 {@link ItemStackRenderState#usesBlockLight()}：
 * 原版 {@code item/generated} 用物品光照（false），{@code block/block} 这类
 * 立体模型用方块光照（true），正好对应两档。</p>
 *
 * <p><b>旋转</b>参考原版附魔台的浮空书本：原版给 Y 轴旋转取了负角，从上方俯视
 * 看过去就是顺时针；这里沿用同样的符号，角速度为每 160 刻（8 秒）一整圈，即每秒
 * <b>0.125 圈</b>。另加一点上下浮动。</p>
 *
 * <p>渲染用的是物品模型，显示上下文取 {@link ItemDisplayContext#NONE}：这样方块和
 * 扁平物品都按 1.0 的原始大小进入姿态栈，再由 {@code pose.scale(...)} 按上面的
 * 档位缩放（若用 {@code FIXED}，方块本身还会再被压到 0.5，缩放就不是所见即所得了）。</p>
 *
 * <p>26.x 的渲染管线把“抽取渲染状态”与“提交几何”分开：本类在
 * {@link #extractRenderState} 里算好角度、缩放并把物品模型解析进
 * {@link DisplayRenderState}，再由 {@link #submit} 提交给
 * {@link SubmitNodeCollector}，不直接触碰任何即时渲染状态。</p>
 *
 * @param <T> 该渲染器负责的方块实体类型
 */
public abstract class OGEFloatingDisplayRenderer<T extends BlockEntity>
        implements BlockEntityRenderer<T, OGEFloatingDisplayRenderer.DisplayRenderState> {

    /** 一整圈所需的刻数：每秒 0.125 圈 → 8 秒一圈 → 160 刻。 */
    protected static final float TICKS_PER_REVOLUTION = 160.0F;

    /** 仅有扁平贴图的展品缩放：60%。 */
    protected static final float FLAT_SCALE = 0.6F;

    /** 带立体模型的展品缩放：40%。 */
    protected static final float MODEL_SCALE = 0.4F;

    /** 上下浮动幅度（格）。 */
    protected static final float BOB_AMPLITUDE = 0.02F;

    /** 把物品堆解析成可提交的物品模型。 */
    protected final ItemModelResolver itemModelResolver;

    protected OGEFloatingDisplayRenderer(BlockEntityRendererProvider.Context context) {
        this.itemModelResolver = context.itemModelResolver();
    }

    /** 展品中心相对方块原点的悬浮高度（格）。 */
    protected abstract float displayHeight(T blockEntity);

    /** 当前要展示的物品；返回空堆表示这一帧不画展品。 */
    protected abstract ItemStack displayStack(T blockEntity);

    @Override
    public DisplayRenderState createRenderState() {
        return new DisplayRenderState();
    }

    @Override
    public void extractRenderState(T blockEntity, DisplayRenderState state, float partialTicks,
                                   Vec3 cameraPosition,
                                   ModelFeatureRenderer.@Nullable CrumblingOverlay breakProgress) {
        BlockEntityRenderer.super.extractRenderState(blockEntity, state, partialTicks, cameraPosition, breakProgress);
        state.visible = false;
        Level level = blockEntity.getLevel();
        if (level == null) {
            return;
        }
        ItemStack display = displayStack(blockEntity);
        if (display.isEmpty()) {
            return;
        }

        // 用整型游戏刻取模再补 partialTick，避免长时间游玩后 float 精度不足以还原角度。
        long gameTime = level.getGameTime();
        state.spin = ((gameTime % (long) TICKS_PER_REVOLUTION) + partialTicks)
                * (360.0F / TICKS_PER_REVOLUTION);
        state.bob = Mth.sin((gameTime + partialTicks) * 0.1F) * BOB_AMPLITUDE;
        state.height = displayHeight(blockEntity);
        this.itemModelResolver.updateForTopItem(state.item, display, ItemDisplayContext.NONE,
                level, null, 0);
        // 展品自己挑档位：纯贴图 0.6，带模型 0.4。
        state.scale = state.item.usesBlockLight() ? MODEL_SCALE : FLAT_SCALE;
        state.visible = true;
    }

    @Override
    public void submit(DisplayRenderState state, PoseStack pose, SubmitNodeCollector submitNodeCollector,
                       CameraRenderState camera) {
        if (!state.visible) {
            return;
        }
        pose.pushPose();
        pose.translate(0.5F, state.height + state.bob, 0.5F);
        // 从上方俯视时 Y 轴正角是逆时针，取负号才是顺时针自转。
        pose.mulPose(Axis.YP.rotationDegrees(-state.spin));
        pose.scale(state.scale, state.scale, state.scale);
        state.item.submit(pose, submitNodeCollector, state.lightCoords, OverlayTexture.NO_OVERLAY, 0);
        pose.popPose();
    }

    /**
     * 展品悬在方块上方，默认只有方块自身那一格的渲染包围盒会在仰视时被剔除，
     * 这里把范围放大到上下各一格，保证从下方经过也能看见浮空的展品。
     */
    @Override
    public AABB getRenderBoundingBox(T blockEntity) {
        return new AABB(blockEntity.getBlockPos()).inflate(1.0D);
    }

    /** 浮空展品的渲染状态：物品模型 + 自转角、浮动、高度与缩放档位。 */
    public static class DisplayRenderState extends BlockEntityRenderState {
        public final ItemStackRenderState item = new ItemStackRenderState();
        public float spin;
        public float bob;
        public float height;
        public float scale = FLAT_SCALE;
        public boolean visible;
    }
}
