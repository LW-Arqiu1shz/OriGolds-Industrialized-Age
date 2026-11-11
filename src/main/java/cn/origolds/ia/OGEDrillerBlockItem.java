package cn.origolds.ia;

import java.util.List;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import java.util.function.Consumer;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.block.Block;

/**
 * 金源采掘模块的物品：在悬浮提示中说明其用法。
 */
public final class OGEDrillerBlockItem extends BlockItem {

    public OGEDrillerBlockItem(Block block, Item.Properties properties) {
        super(block, properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, TooltipDisplay display, Consumer<Component> tooltip, TooltipFlag flag) {
        OGETooltips.add(tooltip, flag,
                "tooltip.origolds_ia.useful.driller", null, "tooltip.origolds_ia.bs.driller");
    }
}
