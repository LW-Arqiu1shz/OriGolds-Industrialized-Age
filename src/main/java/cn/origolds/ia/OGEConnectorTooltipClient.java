package cn.origolds.ia;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * 在父模组（origolds_ar）“金源无线红石连接装置”原有物品提示之后，
 * 追加本模组能力的说明。
 *
 * <p>两段模式说明（金源能量传输 / 无线物流管道）固定插在父模组“无线红石”
 * 提示行的下方：处理器用 {@link EventPriority#LOWEST} 保证在所有其它处理器
 * 之后执行，再按父模组语言键（{@code tooltip.origolds_ar.*}）定位其提示的
 * 最后一行插入，不依赖原版管线中 appendHoverText 与事件的先后。</p>
 *
 * <p>金源能量（Ctrl）：常驻三行——用途（天蓝色）、&quot;（按Ctrl显示详情）&quot;（深灰色）、
 * 斜体废话提示（浅灰色，带双引号，位于按键提示下方）；按住 Ctrl 时再列出
 * 建线、组网、显示模式、悬停选中、Delete 删除与 Ctrl+Z/Ctrl+Y 撤销重做说明。</p>
 *
 * <p>无线物流（Alt）：常驻三行——用途（天蓝色）、&quot;（按Alt显示详情）&quot;（深灰色）、
 * 斜体废话提示（浅灰色，带双引号，位于按键提示下方）；按住 Alt 时列出
 * 单向管道建线、线路显示模式、悬停选中、Delete 删除、Backspace 反转与撤销重做说明。</p>
 */
@EventBusSubscriber(modid = OriGoldsIndustrializedAge.MOD_ID, value = Dist.CLIENT)
public final class OGEConnectorTooltipClient {

    /** 父模组自身提示的语言键前缀，用于定位“无线红石提示”的最后一行。 */
    private static final String PARENT_TOOLTIP_PREFIX = "tooltip.origolds_ar.";

    private OGEConnectorTooltipClient() {
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onItemTooltip(ItemTooltipEvent event) {
        Item connector = OGEWirelessConnectorItemRef.get();
        if (connector == null) {
            return;
        }
        ItemStack stack = event.getItemStack();
        if (stack.isEmpty() || !stack.is(connector)) {
            return;
        }
        List<Component> tooltip = event.getToolTip();
        // ---- 金源能量（Ctrl）常驻说明 ----
        List<Component> ours = new ArrayList<>();
        ours.add(Component.translatable("tooltip.origolds_ia.oge_wire_desc").withStyle(ChatFormatting.AQUA));
        ours.add(Component.translatable("tooltip.origolds_ia.oge_wire_press_ctrl").withStyle(ChatFormatting.DARK_GRAY));
        ours.add(Component.translatable("tooltip.origolds_ia.oge_wire_tip_joke").withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
        // 按住 Ctrl 显示金源能量详情
        if (Minecraft.getInstance().hasControlDown()) {
            ours.add(Component.translatable("tooltip.origolds_ia.oge_wire_tip_select").withStyle(ChatFormatting.DARK_GRAY));
            ours.add(Component.translatable("tooltip.origolds_ia.oge_wire_tip_network").withStyle(ChatFormatting.DARK_GRAY));
            ours.add(Component.translatable("tooltip.origolds_ia.oge_wire_tip_display").withStyle(ChatFormatting.DARK_GRAY));
            ours.add(Component.translatable("tooltip.origolds_ia.oge_wire_tip_hover").withStyle(ChatFormatting.DARK_GRAY));
            ours.add(Component.translatable("tooltip.origolds_ia.oge_wire_tip_delete").withStyle(ChatFormatting.DARK_GRAY));
            ours.add(Component.translatable("tooltip.origolds_ia.oge_wire_tip_undo").withStyle(ChatFormatting.DARK_GRAY));
        }
        // ---- 无线物流（Alt）常驻说明 ----
        ours.add(Component.translatable("tooltip.origolds_ia.oge_pipe_desc").withStyle(ChatFormatting.AQUA));
        ours.add(Component.translatable("tooltip.origolds_ia.oge_pipe_press_alt").withStyle(ChatFormatting.DARK_GRAY));
        ours.add(Component.translatable("tooltip.origolds_ia.oge_pipe_tip_joke").withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
        // 按住 Alt 显示无线物流详情
        if (Minecraft.getInstance().hasAltDown()) {
            ours.add(Component.translatable("tooltip.origolds_ia.oge_pipe_tip_select").withStyle(ChatFormatting.DARK_GRAY));
            ours.add(Component.translatable("tooltip.origolds_ia.oge_pipe_tip_display").withStyle(ChatFormatting.DARK_GRAY));
            ours.add(Component.translatable("tooltip.origolds_ia.oge_pipe_tip_hover").withStyle(ChatFormatting.DARK_GRAY));
            ours.add(Component.translatable("tooltip.origolds_ia.oge_pipe_tip_edit").withStyle(ChatFormatting.DARK_GRAY));
            ours.add(Component.translatable("tooltip.origolds_ia.oge_pipe_tip_undo").withStyle(ChatFormatting.DARK_GRAY));
        }
        // ---- 定位父模组“无线红石提示”的最后一行，把两段说明插在它下方 ----
        int insertAt = -1;
        for (int i = 0; i < tooltip.size(); i++) {
            if (tooltip.get(i).getContents() instanceof TranslatableContents tc
                    && tc.getKey().startsWith(PARENT_TOOLTIP_PREFIX)) {
                insertAt = i + 1;
            }
        }
        if (insertAt < 0) {
            insertAt = tooltip.size();
        }
        tooltip.addAll(insertAt, ours);
    }
}
