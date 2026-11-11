package cn.origolds.ia;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

/**
 * 指向父模组“金源无线红石连接装置”的物品引用。
 *
 * <p>本模组不再注册自己的连接装置，而是把金源能量无线连接与信息显示能力
 * 挂到父模组（origolds_ar）原有的“金源无线红石连接装置”上：原物品用
 * Shift 做无线红石，本模组用 Ctrl/Ctrl+Alt，两组能力互不影响。</p>
 *
 * <p>父模组未安装时返回 {@code null}，相关能力自动禁用，不会因此出错。</p>
 */
public final class OGEWirelessConnectorItemRef {

    private static final ResourceLocation PARENT_CONNECTOR =
            ResourceLocation.fromNamespaceAndPath("origolds_ar", "origold_wireless_connector");

    private static boolean lookedUp = false;
    private static Item cached;

    private OGEWirelessConnectorItemRef() {
    }

    /** 返回父模组的“金源无线红石连接装置”；未安装时返回 {@code null}。 */
    public static Item get() {
        if (!lookedUp) {
            lookedUp = true;
            cached = BuiltInRegistries.ITEM.get(PARENT_CONNECTOR);
        }
        return cached;
    }

    /** 当前是否正手持父模组的连接装置（主手/副手）。 */
    public static boolean isHeld(net.minecraft.world.entity.player.Player player) {
        Item item = get();
        if (item == null) {
            return false;
        }
        return player.getMainHandItem().is(item) || player.getOffhandItem().is(item);
    }
}
