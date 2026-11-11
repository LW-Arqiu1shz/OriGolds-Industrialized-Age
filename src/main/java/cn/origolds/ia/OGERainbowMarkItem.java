package cn.origolds.ia;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomModelData;

/**
 * 虹彩印记（Rainbow Mark）：贴在资源发生器 / 火力发电机 / 魔力发电机上，
 * 把机器按 5x / 20x / 100x 档位超频。
 *
 * <p>它是一件“有耐久但正常玩法扣不掉”的物品：耐久上限 {@value #MAX_DURABILITY}，
 * 合成出来只有 1 点；在工作台里与任意数量的魔金之核无序合成可一等一地换回耐久，
 * 也可以把还有耐久的印记回炉成 1 个魔金之核（消耗 1 点耐久）。耐久为 0 时不能贴、
 * 也不能切档。</p>
 *
 * <p>档位存在 {@link ModDataComponents#RAINBOW_TIER} 组件里；为了让同一个物品
 * 按档位换图标，另把档位同时写进原版 {@code custom_model_data} 组件，
 * 由物品模型 JSON 的 overrides 选贴图（见 {@code models/item/rainbow_mark.json}）。</p>
 */
public final class OGERainbowMarkItem extends Item {

    /** 耐久上限：64 点，也就是最多能换回 64 次耐久。 */
    public static final int MAX_DURABILITY = 64;

    public OGERainbowMarkItem(Properties properties) {
        super(properties);
    }

    /** 读取档位（0/1/2）；没有组件时按默认 5x。 */
    public static int getTier(ItemStack stack) {
        Integer tier = stack.get(ModDataComponents.RAINBOW_TIER.get());
        return tier == null ? OGERainbowMarks.DEFAULT_TIER : OGERainbowMarks.clampTier(tier);
    }

    /** 写入档位：逻辑组件与换贴图用的 custom_model_data 一起写，保证图标与效果一致。 */
    public static void setTier(ItemStack stack, int tier) {
        int clamped = OGERainbowMarks.clampTier(tier);
        stack.set(ModDataComponents.RAINBOW_TIER.get(), clamped);
        stack.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(clamped));
    }

    /** 剩余耐久（0 ~ {@value #MAX_DURABILITY}）。 */
    public static int getDurability(ItemStack stack) {
        return Math.max(0, stack.getMaxDamage() - stack.getDamageValue());
    }

    /** 还算不算“能用的印记”：物品对得上且有至少 1 点耐久。 */
    public static boolean isUsable(ItemStack stack) {
        return stack.getItem() instanceof OGERainbowMarkItem
                && stack.getDamageValue() < stack.getMaxDamage();
    }

    /** 按“档位 + 剩余耐久”造一枚印记（供需要还原印记状态的代码使用）。 */
    public static ItemStack createStack(int tier, int durability) {
        ItemStack stack = new ItemStack(ModBlocks.RAINBOW_MARK.get());
        setTier(stack, tier);
        int remaining = Math.max(1, Math.min(MAX_DURABILITY, durability));
        stack.setDamageValue(MAX_DURABILITY - remaining);
        return stack;
    }

    /**
     * 服务端侧切档：由客户端“手持印记、对着空气按 Ctrl+左/右键”的报文触发
     * （见 {@link OGERainbowMarkTierPayload}）。Ctrl 不是原版交互包会携带的修饰键，
     * 所以按键判定只能在客户端做，这里再把“主手确实是印记、耐久没耗尽”重新校验一遍。
     *
     * @param player 发起操作的玩家
     * @param delta  档位变化量：-1 降档、+1 升档（三档首尾相接循环）
     */
    public static void adjustTier(ServerPlayer player, int delta) {
        if (delta == 0) {
            return;
        }
        ItemStack stack = player.getMainHandItem();
        if (!(stack.getItem() instanceof OGERainbowMarkItem)) {
            return;
        }
        if (!isUsable(stack)) {
            player.displayClientMessage(
                    Component.translatable("message.origolds_ia.rainbow_mark_broken"), true);
            return;
        }
        int next = Math.floorMod(getTier(stack) + delta, OGERainbowMarks.TIER_COUNT);
        setTier(stack, next);
        // 主手物品是原版物品栏菜单里的一个槽位，改完立刻广播一次，客户端图标才会跟着换。
        player.inventoryMenu.broadcastChanges();
        player.displayClientMessage(Component.translatable(
                "message.origolds_ia.rainbow_mark_tier", OGERainbowMarks.label(next)), true);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        OGETooltips.add(tooltip, flag,
                "tooltip.origolds_ia.rainbow_mark",
                null,
                "tooltip.origolds_ia.rainbow_mark.bs");

        int tier = getTier(stack);
        tooltip.add(Component.translatable("tooltip.origolds_ia.rainbow_mark.tier",
                        OGERainbowMarks.label(tier),
                        OGERainbowMarks.speedMultiplier(tier),
                        OGERainbowMarks.resourceMultiplier(tier))
                .withStyle(ChatFormatting.GOLD));

        // 只显示“还能用几次”：上限 64 与原版耐久条重复，不再写进提示。
        int durability = getDurability(stack);
        tooltip.add(Component.translatable("tooltip.origolds_ia.rainbow_mark.durability", durability)
                .withStyle(durability <= 0 ? ChatFormatting.RED : ChatFormatting.GRAY));
    }
}
