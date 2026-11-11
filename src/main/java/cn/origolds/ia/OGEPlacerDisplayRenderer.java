package cn.origolds.ia;

import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * 金源放置器顶上的浮空展品渲染器。
 *
 * <p>展品内容分两种情况：</p>
 * <ul>
 *   <li><b>未绑定</b>时展示本体模组（origolds）的“魔金之核”物品；父模组未安装时
 *       拿不到这个物品，就什么也不画，不会报错；</li>
 *   <li><b>已绑定</b>放置目标方块后，展示的就是那件被绑定的方块物品。</li>
 * </ul>
 *
 * <p>浮空姿态、顺时针自转（8 秒一圈）与按“纯贴图 0.6 / 带模型 0.4”分档的缩放，
 * 全部复用 {@link OGEFloatingDisplayRenderer}，与自动附魔机保持同一套观感。</p>
 */
public final class OGEPlacerDisplayRenderer extends OGEFloatingDisplayRenderer<OGEPlacerBlockEntity> {

    /** 展品中心的悬浮高度（格）：放置器是满格方块，顶面在 1.0，这里略高一点。 */
    private static final float DISPLAY_HEIGHT = 1.3F;

    public OGEPlacerDisplayRenderer(BlockEntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    protected float displayHeight(OGEPlacerBlockEntity blockEntity) {
        return DISPLAY_HEIGHT;
    }

    /** 已绑定就展示绑定方块，否则回落到父模组的“魔金之核”。 */
    @Override
    protected ItemStack displayStack(OGEPlacerBlockEntity blockEntity) {
        ItemStack bound = blockEntity.getBound();
        if (!bound.isEmpty()) {
            return bound;
        }
        Item core = OGEMagicCoreRef.get();
        return core == null ? ItemStack.EMPTY : new ItemStack(core);
    }
}
