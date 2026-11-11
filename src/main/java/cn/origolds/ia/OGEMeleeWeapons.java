package cn.origolds.ia;

import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.core.Holder;
import net.minecraft.world.item.ItemStack;

/**
 * “近战武器”判定与面板数值计算（金源圆锯刃槽用）。
 *
 * <p>判定标准按需求：<b>任何拥有攻击力和攻击速度的物品</b>——主手槽位上同时带
 * {@code ATTACK_DAMAGE} 与 {@code ATTACK_SPEED} 两种属性修饰量即算数
 * （原版剑 / 斧 / 锄 / 刃片都是这么写进 {@code minecraft:attribute_modifiers} 组件的）。</p>
 *
 * <p>面板值就是物品提示里显示的那两个数：以玩家基础值（伤害 1.0、攻速 4.0）为底，
 * 按原版属性的算法叠加修饰量——先加值、再乘基础百分比、最后乘总百分比。</p>
 */
public final class OGEMeleeWeapons {

    /** 玩家空手的攻击伤害基础值（提示里的显示基数）。 */
    private static final double BASE_ATTACK_DAMAGE = 1.0D;

    /** 玩家空手的攻速基础值（提示里的显示基数）。 */
    private static final double BASE_ATTACK_SPEED = 4.0D;

    private OGEMeleeWeapons() {
    }

    /** 这把“武器”能不能被刃槽当刀片用：主手同时有攻击力与攻速修饰。 */
    public static boolean isMeleeWeapon(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        boolean[] found = new boolean[2];
        stack.forEachModifier(EquipmentSlotGroup.MAINHAND, (attribute, modifier) -> {
            if (attribute.is(Attributes.ATTACK_DAMAGE)) {
                found[0] = true;
            } else if (attribute.is(Attributes.ATTACK_SPEED)) {
                found[1] = true;
            }
        });
        return found[0] && found[1];
    }

    /** 面板攻击伤害（提示里显示的那个数，含玩家基础 1 点）。 */
    public static double panelAttackDamage(ItemStack stack) {
        return panelValue(stack, Attributes.ATTACK_DAMAGE, BASE_ATTACK_DAMAGE);
    }

    /** 面板攻击速度（提示里显示的那个数，含玩家基础 4.0）。 */
    public static double panelAttackSpeed(ItemStack stack) {
        return panelValue(stack, Attributes.ATTACK_SPEED, BASE_ATTACK_SPEED);
    }

    /**
     * 刀刃单次攻击伤害：面板伤害 × 面板攻速 ÷ 2，
     * 即刀片的 DPS 与武器的面板 DPS 一致（每 10 刻出手一次，一秒两次）。
     */
    public static double bladeDamagePerHit(ItemStack stack) {
        return panelAttackDamage(stack) * panelAttackSpeed(stack) / 2.0D;
    }

    private static double panelValue(ItemStack stack, Holder<Attribute> attribute, double base) {
        // 必须在 lambda 里累加，局部变量不可变，用数组装：[加值, 基础百分比, 总百分比因子]
        double[] sum = {0.0D, 0.0D, 1.0D};
        boolean[] any = new boolean[1];
        stack.forEachModifier(EquipmentSlotGroup.MAINHAND, (attr, modifier) -> {
            if (!attr.is(attribute)) {
                return;
            }
            any[0] = true;
            switch (modifier.operation()) {
                case ADD_VALUE -> sum[0] += modifier.amount();
                case ADD_MULTIPLIED_BASE -> sum[1] += modifier.amount();
                case ADD_MULTIPLIED_TOTAL -> sum[2] *= 1.0D + modifier.amount();
            }
        });
        if (!any[0]) {
            return 0.0D;
        }
        return (base + sum[0]) * (1.0D + sum[1]) * sum[2];
    }
}
