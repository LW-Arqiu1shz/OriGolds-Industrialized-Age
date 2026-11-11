package cn.origolds.ia;

import net.minecraft.core.Holder;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;

/**
 * "近战武器"判定与面板数值工具。
 *
 * <p>是否近战武器只看一件物品的 MAINHAND 属性里是否同时带攻击伤害与攻击速度两条修改器
 * （原版剑/斧/锄以及本模组圆锯片都满足）。面板数值按原版属性合成顺序把该属性所有修改器
 * 累加成 "(base + 加值) × (1 + 基础百分比之积) × (总百分比之积)"。</p>
 *
 * <p>26.x 变更：{@code ItemStack.forEachModifier} 的 {@code EquipmentSlotGroup} 版改成
 * 已废弃的 {@code TriConsumer}，这里改用非废弃的 {@code (EquipmentSlot, BiConsumer)} 重载，
 * 对 MAINHAND 而言枚举到的修改器集合（物品组件 + 附魔）与旧写法一致。</p>
 */
public final class OGEMeleeWeapons {

    private static final double BASE_ATTACK_DAMAGE = 1.0;
    private static final double BASE_ATTACK_SPEED = 4.0;

    private OGEMeleeWeapons() {
    }

    /** 主手同时带攻击伤害与攻速两条修改器即视为近战武器。 */
    public static boolean isMeleeWeapon(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        boolean[] found = new boolean[2];
        stack.forEachModifier(EquipmentSlot.MAINHAND, (attribute, modifier) -> {
            if (attribute.is(Attributes.ATTACK_DAMAGE)) {
                found[0] = true;
            } else if (attribute.is(Attributes.ATTACK_SPEED)) {
                found[1] = true;
            }
        });
        return found[0] && found[1];
    }

    /** 面板攻击伤害（含物品与附魔修改器，按原版合成式）。 */
    public static double panelAttackDamage(ItemStack stack) {
        return panelValue(stack, Attributes.ATTACK_DAMAGE, BASE_ATTACK_DAMAGE);
    }

    /** 面板攻击速度（含物品与附魔修改器，按原版合成式）。 */
    public static double panelAttackSpeed(ItemStack stack) {
        return panelValue(stack, Attributes.ATTACK_SPEED, BASE_ATTACK_SPEED);
    }

    /** 锯片单次命中的数值伤害：面板伤害 × 面板攻速 ÷ 2。 */
    public static double bladeDamagePerHit(ItemStack stack) {
        return panelAttackDamage(stack) * panelAttackSpeed(stack) / 2.0;
    }

    private static double panelValue(ItemStack stack, Holder<Attribute> attribute, double base) {
        double[] sum = new double[]{0.0, 0.0, 1.0};
        boolean[] any = new boolean[1];
        stack.forEachModifier(EquipmentSlot.MAINHAND, (attr, modifier) -> {
            if (attr.is(attribute)) {
                any[0] = true;
                switch (modifier.operation()) {
                    case ADD_VALUE -> sum[0] += modifier.amount();
                    case ADD_MULTIPLIED_BASE -> sum[1] += modifier.amount();
                    case ADD_MULTIPLIED_TOTAL -> sum[2] *= 1.0 + modifier.amount();
                }
            }
        });
        return !any[0] ? 0.0 : (base + sum[0]) * (1.0 + sum[1]) * sum[2];
    }
}
