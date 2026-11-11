package cn.origolds.ia;

import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.Item.Properties;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.component.TooltipDisplay;

/**
 * 圆锯片物品：锯片台的"弹夹"。
 *
 * <p>面板上是一件 +5 攻/−2.8 攻速的主手近战武器（{@link #createAttributes()} 注册在物品
 * 属性组件里），但设计上不可附魔——26.x 附魔性由 {@code Item.Properties.enchantable(int)}
 * 决定，注册处没有给锯片开附魔值，本类里不再有 {@code isEnchantable}/{@code getEnchantmentValue}
 * 覆写（1.21.1 的这两个覆写在 26.x 已删除，此处按新 API 天然成立）。</p>
 *
 * <p>耐久 {@value #MAX_DURABILITY} 点；钉上锯片台与充能配方都按点数消耗/修复，
 * 悬浮提示末行会报剩余耐久（见底转红）。</p>
 */
public final class OGECircularSawbladeItem extends Item {

    /** 注册时写在 Properties.durability 上的总耐久。 */
    public static final int MAX_DURABILITY = 64;
    /** 面板属性数值（与 createAttributes 的加成对应）。 */
    public static final double ATTACK_DAMAGE = 6.0;
    public static final double ATTACK_SPEED = 1.2;

    public OGECircularSawbladeItem(Properties properties) {
        super(properties);
    }

    /** 主手属性：+5.0 攻击伤害、−2.8 攻击速度（面板见 {@link #ATTACK_DAMAGE}/{@link #ATTACK_SPEED}）。 */
    public static ItemAttributeModifiers createAttributes() {
        return ItemAttributeModifiers.builder()
                .add(Attributes.ATTACK_DAMAGE,
                        new AttributeModifier(BASE_ATTACK_DAMAGE_ID, 5.0, Operation.ADD_VALUE),
                        EquipmentSlotGroup.MAINHAND)
                .add(Attributes.ATTACK_SPEED,
                        new AttributeModifier(BASE_ATTACK_SPEED_ID, -2.8, Operation.ADD_VALUE),
                        EquipmentSlotGroup.MAINHAND)
                .build();
    }

    /** 剩余耐久。 */
    public static int getDurability(ItemStack stack) {
        return Math.max(0, stack.getMaxDamage() - stack.getDamageValue());
    }

    /** 还能不能用（是锯片且没打爆）。 */
    public static boolean isUsable(ItemStack stack) {
        return stack.getItem() instanceof OGECircularSawbladeItem && stack.getDamageValue() < stack.getMaxDamage();
    }

    /** 钉一片锯片扣 1 点耐久；创造/无限材料不扣。 */
    public static void consumeOne(ItemStack stack, Player player) {
        if (!player.hasInfiniteMaterials() && stack.getDamageValue() < stack.getMaxDamage()) {
            stack.setDamageValue(stack.getDamageValue() + 1);
            if (player instanceof ServerPlayer serverPlayer) {
                serverPlayer.inventoryMenu.broadcastChanges();
            }
        }
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, TooltipDisplay display,
                                Consumer<Component> tooltip, TooltipFlag flag) {
        OGETooltips.add(tooltip, flag, "tooltip.origolds_ia.circular_sawblade", null,
                "tooltip.origolds_ia.circular_sawblade.bs");
        int durability = getDurability(stack);
        tooltip.accept(Component.translatable("tooltip.origolds_ia.circular_sawblade.durability", durability)
                .withStyle(durability <= 0 ? ChatFormatting.RED : ChatFormatting.GRAY));
    }
}
