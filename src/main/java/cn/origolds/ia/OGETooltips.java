package cn.origolds.ia;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.TooltipFlag;

import java.util.function.Consumer;

/**
 * 金源工艺：工业时代 —— 物品悬浮提示的统一样式工具。
 *
 * <p>与父模组（origolds）保持一致：</p>
 * <ul>
 *   <li>实用主内容：浅蓝色正体（{@link ChatFormatting#AQUA}）；</li>
 *   <li>按住 Ctrl 才展开的详情：深灰色正体（{@link ChatFormatting#DARK_GRAY}），
 *       常驻一行“（按住Ctrl显示详情）”作为入口；</li>
 *   <li>废话文本：浅灰色斜体（{@link ChatFormatting#GRAY} + {@link ChatFormatting#ITALIC}）。</li>
 * </ul>
 *
 * <p>按键判定走 NeoForge 扩展的 {@link TooltipFlag#hasControlDown()}，
 * 由工具提示管线按当前按键状态给出，不在物品代码里直接引用客户端类。</p>
 */
public final class OGETooltips {

    /** 常驻的“按住 Ctrl 显示详情”入口行。 */
    public static final String HOLD_CTRL_KEY = "tooltip.origolds_ia.hold_ctrl";

    private OGETooltips() {
    }

    /** 一条实用提示：浅蓝正体。 */
    public static Component useful(String translationKey) {
        return Component.translatable(translationKey).withStyle(ChatFormatting.AQUA);
    }

    /** 一条废话提示：浅灰斜体；键为 null 时返回 null（调用方跳过）。 */
    public static Component flavor(String translationKey) {
        return Component.translatable(translationKey).withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC);
    }

    /** 追加“实用 + 废话（可空）”两行，没有按住 Ctrl 的部分。 */
    public static void addUsefulAndFlavor(Consumer<Component> tooltip, String usefulKey, String flavorKey) {
        add(tooltip, null, usefulKey, null, flavorKey);
    }

    /**
     * 追加完整提示：实用主内容（浅蓝正体）+ 按住 Ctrl 的入口与详情（深灰正体）+
     * 废话（浅灰斜体）。
     *
     * @param usefulKey      实用主内容，允许内含换行符 {@code \n} 拆成多行；为 null 时跳过
     * @param ctrlDetailsKey 按住 Ctrl 才显示的详情；为 null 时没有这一部分
     * @param flavorKey      废话文本；为 null 时跳过
     */
    public static void add(Consumer<Component> tooltip,
                           TooltipFlag flag,
                           String usefulKey,
                           String ctrlDetailsKey,
                           String flavorKey) {
        if (usefulKey != null) {
            addMultiline(tooltip, usefulKey, ChatFormatting.AQUA);
        }
        if (ctrlDetailsKey != null) {
            tooltip.accept(Component.translatable(HOLD_CTRL_KEY).withStyle(ChatFormatting.DARK_GRAY));
            if (flag != null && flag.hasControlDown()) {
                addMultiline(tooltip, ctrlDetailsKey, ChatFormatting.DARK_GRAY);
            }
        }
        if (flavorKey != null) {
            addMultiline(tooltip, flavorKey, ChatFormatting.GRAY, ChatFormatting.ITALIC);
        }
    }

    /** 把译文里的 {@code \n} 拆成独立的提示行，每行统一上色（多行详情的关键一步）。 */
    private static void addMultiline(Consumer<Component> tooltip, String translationKey,
                                     ChatFormatting... styles) {
        String text = Component.translatable(translationKey).getString();
        for (String line : text.split("\n", -1)) {
            tooltip.accept(Component.literal(line).withStyle(styles));
        }
    }
}
