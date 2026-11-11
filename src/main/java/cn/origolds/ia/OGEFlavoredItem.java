package cn.origolds.ia;

import java.util.List;

import net.minecraft.network.chat.Component;
import java.util.function.Consumer;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

/**
 * 带统一悬浮提示的普通物品：浅蓝实用行 +（可选）浅灰斜体废话行。
 * 目前用于“资源发生器核心”。
 */
public final class OGEFlavoredItem extends Item {

    private final String usefulKey;
    private final String flavorKey;

    public OGEFlavoredItem(Properties properties, String usefulKey, String flavorKey) {
        super(properties);
        this.usefulKey = usefulKey;
        this.flavorKey = flavorKey;
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, TooltipDisplay display, Consumer<Component> tooltip, TooltipFlag flag) {
        OGETooltips.add(tooltip, flag, usefulKey, null, flavorKey);
    }
}
