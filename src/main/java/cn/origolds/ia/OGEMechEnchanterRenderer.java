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

    /** 槽位 0 空置时展示的小青金石块。 */
    private static final ItemStack DEFAULT_DISPLAY = new ItemStack(Items.LAPIS_BLOCK);

    /** 展品中心的悬浮高度（格）：机器顶面在 12/16 = 0.75，这里略高一点。 */
    private static final float DISPLAY_HEIGHT = 1.05F;

    public OGEMechEnchanterRenderer(BlockEntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    protected float displayHeight(OGEMechEnchanterBlockEntity blockEntity) {
        return DISPLAY_HEIGHT;
    }

    /** 槽位 0 的展品；空着时回落到小青金石块。 */
    @Override
    protected ItemStack displayStack(OGEMechEnchanterBlockEntity blockEntity) {
        ItemStack display = blockEntity.getDisplayStack();
        return display.isEmpty() ? DEFAULT_DISPLAY : display;
    }
}
