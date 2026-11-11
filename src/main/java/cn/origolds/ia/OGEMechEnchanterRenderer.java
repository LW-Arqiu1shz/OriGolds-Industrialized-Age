package cn.origolds.ia;

import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 金源自动附魔机顶上的浮空展品渲染器。
 *
 * <p>机器槽位 0 空着时顶上浮着一个小青金石块；槽位 0 放了东西就把那件东西顶替上来。
 * 浮空的姿态、顺时针自转（8 秒一圈）与按“纯贴图 0.6 / 带模型 0.4”分档的缩放，
 * 全部复用 {@link OGEFloatingDisplayRenderer}，这里只提供高度与展品。</p>
 *
 * <p>展品中心比机器顶面（12/16 = 0.75）略高，留出浮空感。靠近时不需要像附魔台
 * 那样翻书，因此只渲染、不做额外反应。</p>
 */
public final class OGEMechEnchanterRenderer extends OGEFloatingDisplayRenderer<OGEMechEnchanterBlockEntity> {

    /** 展品中心的悬浮高度（格）：机器顶面在 12/16 = 0.75，这里略高一点。 */
    private static final float DISPLAY_HEIGHT = 1.05F;

    /**
     * 槽位 0 空置时展示的小青金石块，首次使用时才创建。
     *
     * <p><b>不要在静态初始化里直接 {@code new ItemStack(...)}</b>：渲染器注册发生在
     * 资源重载阶段，类一加载就会执行静态字段初始化，此时原版物品注册表还没就绪，
     * 构造 {@code ItemStack} 会抛 NPE，进而让资源包加载失败、游戏移除全部资源包、
     * 主菜单黑屏。这里改成渲染第一帧用到时才建，并缓存复用。</p>
     */
    private static volatile ItemStack defaultDisplayCache;

    /** 空置时的默认展品（青金石块）；惰性创建，注册阶段绝不触碰物品注册表。 */
    private static ItemStack defaultDisplay() {
        ItemStack cached = defaultDisplayCache;
        if (cached == null) {
            cached = new ItemStack(Items.LAPIS_BLOCK);
            defaultDisplayCache = cached;
        }
        return cached;
    }

    public OGEMechEnchanterRenderer(BlockEntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    protected float displayHeight(OGEMechEnchanterBlockEntity blockEntity) {
        return DISPLAY_HEIGHT;
    }

    /** 槽位 0 的展品；空着时回落到惰性创建的默认青金石块。 */
    @Override
    protected ItemStack displayStack(OGEMechEnchanterBlockEntity blockEntity) {
        ItemStack display = blockEntity.getDisplayStack();
        return display.isEmpty() ? defaultDisplay() : display;
    }
}
