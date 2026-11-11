package cn.origolds.ia;

import java.util.List;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.Item.Properties;
import net.minecraft.world.item.Item.TooltipContext;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.ItemLike;

/**
 * 彩虹标记：手持（主手）对准机器 Shift+右键上标记、Shift+左键清标记（返还魔金之核）；
 * 对着空地 Ctrl+左/右键切换档位（5x/20x/100x）。耐久 64，每次上标记消耗 1 点。
 *
 * <p>档位存在数据组件 {@link ModDataComponents#RAINBOW_TIER}，同时写进
 * {@code CUSTOM_MODEL_DATA} 让物品模型按档位换外观。</p>
 */
public final class OGERainbowMarkItem extends Item {

    /** 满耐久：一次上标记耗 1 点。 */
    public static final int MAX_DURABILITY = 64;

    public OGERainbowMarkItem(Properties properties) {
        super(properties);
    }

    /** 当前档位（越界钳到 0~2）。 */
    public static int getTier(ItemStack stack) {
        Integer tier = stack.get(ModDataComponents.RAINBOW_TIER.get());
        return tier == null ? 0 : OGERainbowMarks.clampTier(tier);
    }

    /** 写档位数据组件并同步物品模型。 */
    public static void setTier(ItemStack stack, int tier) {
        int clamped = OGERainbowMarks.clampTier(tier);
        stack.set(ModDataComponents.RAINBOW_TIER.get(), clamped);
        // 26.x：CUSTOM_MODEL_DATA 为四列表记录；把档位写进 floats[0]，
        // 供客户端物品定义（items/rainbow_mark.json）的 range_dispatch 选择贴图。
        stack.set(DataComponents.CUSTOM_MODEL_DATA,
                new CustomModelData(List.of((float) clamped), List.of(), List.of(), List.of()));
    }

    /** 剩余耐久（= 最大耐久 - 已损）。 */
    public static int getDurability(ItemStack stack) {
        return Math.max(0, stack.getMaxDamage() - stack.getDamageValue());
    }

    /** 还能不能再上标记（未用尽耐久）。 */
    public static boolean isUsable(ItemStack stack) {
        return stack.getItem() instanceof OGERainbowMarkItem && stack.getDamageValue() < stack.getMaxDamage();
    }

    /** 造一个指定档位与剩余耐久的标记（给奖励/测试用）。 */
    public static ItemStack createStack(int tier, int durability) {
        ItemStack stack = new ItemStack((ItemLike) ModBlocks.RAINBOW_MARK.get());
        setTier(stack, tier);
        int remaining = Math.max(1, Math.min(MAX_DURABILITY, durability));
        stack.setDamageValue(MAX_DURABILITY - remaining);
        return stack;
    }

    /** 网络层入口：Ctrl+空挥换挡（delta=±1，循环三档）。 */
    public static void adjustTier(ServerPlayer player, int delta) {
        if (delta == 0) {
            return;
        }
        ItemStack stack = player.getMainHandItem();
        if (!(stack.getItem() instanceof OGERainbowMarkItem)) {
            return;
        }
        if (!isUsable(stack)) {
            player.sendOverlayMessage(Component.translatable("message.origolds_ia.rainbow_mark_broken"));
            return;
        }
        int next = Math.floorMod(getTier(stack) + delta, OGERainbowMarks.TIER_COUNT);
        setTier(stack, next);
        player.inventoryMenu.broadcastChanges();
        player.sendOverlayMessage(
                Component.translatable("message.origolds_ia.rainbow_mark_tier", OGERainbowMarks.label(next)));
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
                                Consumer<Component> tooltip, TooltipFlag flag) {
        OGETooltips.add(tooltip, flag, "tooltip.origolds_ia.rainbow_mark", null,
                "tooltip.origolds_ia.rainbow_mark.bs");
        int tier = getTier(stack);
        tooltip.accept(Component.translatable("tooltip.origolds_ia.rainbow_mark.tier",
                        OGERainbowMarks.label(tier),
                        OGERainbowMarks.speedMultiplier(tier),
                        OGERainbowMarks.resourceMultiplier(tier))
                .withStyle(ChatFormatting.GOLD));
        int durability = getDurability(stack);
        tooltip.accept(Component.translatable("tooltip.origolds_ia.rainbow_mark.durability", durability)
                .withStyle(durability <= 0 ? ChatFormatting.RED : ChatFormatting.GRAY));
    }
}
