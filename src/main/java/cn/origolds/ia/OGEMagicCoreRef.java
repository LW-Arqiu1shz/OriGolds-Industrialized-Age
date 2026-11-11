package cn.origolds.ia;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * 指向父模组（origolds）“魔金之核”的物品引用。
 *
 * <p>魔驱金源能量发生器把“魔金之核”当作燃料使用；未安装父模组时返回
 * {@code null}，魔驱自动无法工作，不会因此出错。</p>
 */
public final class OGEMagicCoreRef {

    private static final ResourceLocation MAGIC_CORE =
            ResourceLocation.fromNamespaceAndPath("origolds", "magigold_core");

    private static boolean lookedUp = false;
    private static Item cached;

    private OGEMagicCoreRef() {
    }

    /** 返回父模组的“魔金之核”物品；未安装时返回 {@code null}。 */
    public static Item get() {
        if (!lookedUp) {
            lookedUp = true;
            cached = BuiltInRegistries.ITEM.get(MAGIC_CORE);
        }
        return cached;
    }

    /** 判断一组物品是否为“魔金之核”。 */
    public static boolean isCore(ItemStack stack) {
        Item item = get();
        return item != null && stack.is(item);
    }
}
