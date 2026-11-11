package cn.origolds.ia;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

/**
 * 精炼粗矿（携带 {@code origolds_ia:oge_concentrated} 组件的粗矿）的悬浮提示。
 *
 * <p>精矿与粗矿是同一个物品，只是多了数据组件和改名，无法用物品子类承载提示，
 * 因此在客户端工具提示事件里按组件识别并追加，样式与模组其它物品一致：
 * 浅蓝色正体的实用主内容 + 浅灰色斜体的废话文本（废话固定在最后一行）。
 * 实用行中的“原来的粗矿名称”由组件里记录的原料物品动态给出。</p>
 */
@EventBusSubscriber(modid = OriGoldsIndustrializedAge.MOD_ID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class OGEConcentratedTooltipClient {

    private OGEConcentratedTooltipClient() {
    }

    @SubscribeEvent
    public static void onItemTooltip(ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();
        Holder<Item> raw = stack.get(ModDataComponents.OGE_CONCENTRATED.get());
        if (raw == null) {
            return;
        }
        List<Component> tooltip = event.getToolTip();
        tooltip.add(Component.translatable("tooltip.origolds_ia.useful.concentrated",
                        Component.translatable(raw.value().getDescriptionId()))
                .withStyle(ChatFormatting.AQUA));
        tooltip.add(Component.translatable("tooltip.origolds_ia.bs.concentrated")
                .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
    }
}
