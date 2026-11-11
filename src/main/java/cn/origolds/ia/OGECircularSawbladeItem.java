package cn.origolds.ia;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.ItemAttributeModifiers;

/**
 * 圆锯刃片（Circular Sawblade）：既能拿在手上砍，也能装到
 * {@link OGESawbladeMountBlock 金源圆锯刃槽}上充当刀刃的耐久件。
 *
 * <p>耐久机制与{@link OGERainbowMarkItem 虹彩印记}一致：耐久上限
 * {@value #MAX_DURABILITY}，合成出来只有 1 点；在工作台里与铁块无序合成可以
 * 一等一地补回耐久（1 个铁块换 1 点，见 {@link CircularSawbladeRechargeRecipe}）。
 * 唯一会扣掉刃片耐久的动作是“往刃槽上装刀刃”（见 {@link OGESawbladeMountEvents}）。</p>
 *
 * <h2>攻击不掉耐久</h2>
 * <p>近战扣耐久只有两条原版路径：物品自己重写 {@code postHurtEnemy}（剑、斧就是这么掉的），
 * 以及带 {@code minecraft:tool} 组件时挖矿方块扣 {@code damage_per_block}。
 * 刃片是普通 {@link Item}：没有 {@code tool} 组件、也不重写那两个钩子，
 * 所以打人、挖方块都不会消耗耐久，只有装刀刃会。</p>
 *
 * <h2>面板数值</h2>
 * <p>攻击伤害与攻速都按“提示里显示的那个数”来定：原版提示会把修饰量加上玩家基础值
 * （伤害基础 1.0、攻速基础 4.0）再显示，所以 {@link #ATTACK_DAMAGE}{@code =6}、
 * {@link #ATTACK_SPEED}{@code =1.2} 对应的修饰量是 {@code +5.0} 与 {@code -2.8}，
 * 手上显示的就是“6 攻击伤害 / 1.2 攻击速度”。</p>
 */
public final class OGECircularSawbladeItem extends Item {

    /** 耐久上限：64 点。 */
    public static final int MAX_DURABILITY = 64;

    /** 提示里显示的攻击伤害（含玩家基础的 1 点）。 */
    public static final double ATTACK_DAMAGE = 6.0;

    /** 提示里显示的攻速（每秒攻击次数，含玩家基础的 4.0）。 */
    public static final double ATTACK_SPEED = 1.2;

    /** 玩家空手的攻击伤害基础值。 */
    private static final double PLAYER_BASE_ATTACK_DAMAGE = 1.0;

    /** 玩家空手的攻速基础值。 */
    private static final double PLAYER_BASE_ATTACK_SPEED = 4.0;

    public OGECircularSawbladeItem(Properties properties) {
        super(properties);
    }

    /**
     * 刃片的主手属性：攻击伤害与攻速都写成“原版提示显示的值”，
     * 所以修饰量要减掉玩家基础值。用原版的 {@code base_attack_damage /
     * base_attack_speed} 两个 ID，提示才会按“基础值 + 修饰量”显示成 6 和 1.2。
     */
    public static ItemAttributeModifiers createAttributes() {
        return ItemAttributeModifiers.builder()
                .add(Attributes.ATTACK_DAMAGE,
                        new AttributeModifier(BASE_ATTACK_DAMAGE_ID,
                                ATTACK_DAMAGE - PLAYER_BASE_ATTACK_DAMAGE,
                                AttributeModifier.Operation.ADD_VALUE),
                        EquipmentSlotGroup.MAINHAND)
                .add(Attributes.ATTACK_SPEED,
                        new AttributeModifier(BASE_ATTACK_SPEED_ID,
                                ATTACK_SPEED - PLAYER_BASE_ATTACK_SPEED,
                                AttributeModifier.Operation.ADD_VALUE),
                        EquipmentSlotGroup.MAINHAND)
                .build();
    }

    /** 剩余耐久（0 ~ {@value #MAX_DURABILITY}）。 */
    public static int getDurability(ItemStack stack) {
        return Math.max(0, stack.getMaxDamage() - stack.getDamageValue());
    }

    /** 还算不算“能用的刃片”：物品对得上且有至少 1 点耐久。 */
    public static boolean isUsable(ItemStack stack) {
        return stack.getItem() instanceof OGECircularSawbladeItem
                && stack.getDamageValue() < stack.getMaxDamage();
    }

    /**
     * 扣 1 点耐久（装一次刀刃的代价）：刃片不会用完消失，只是耐久见底后
     * 再也装不上，得回工作台用铁块补——与虹彩印记同一套手感。
     * 创造模式不扣。
     */
    public static void consumeOne(ItemStack stack, Player player) {
        if (player.hasInfiniteMaterials() || stack.getDamageValue() >= stack.getMaxDamage()) {
            return;
        }
        stack.setDamageValue(stack.getDamageValue() + 1);
        if (player instanceof ServerPlayer serverPlayer) {
            serverPlayer.inventoryMenu.broadcastChanges();
        }
    }

    /** 刃片不可附魔：锋利之类一律不上身。 */
    @Override
    public boolean isEnchantable(ItemStack stack) {
        return false;
    }

    /** 附魔价值 0，双重保险（配合不进任何 {@code enchantable/*} 标签）。 */
    @Override
    public int getEnchantmentValue() {
        return 0;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        OGETooltips.add(tooltip, flag,
                "tooltip.origolds_ia.circular_sawblade",
                null,
                "tooltip.origolds_ia.circular_sawblade.bs");

        // 只显示“还能用几次”：上限 64 与原版耐久条重复，不再写进提示。
        int durability = getDurability(stack);
        tooltip.add(Component.translatable("tooltip.origolds_ia.circular_sawblade.durability", durability)
                .withStyle(durability <= 0 ? ChatFormatting.RED : ChatFormatting.GRAY));
    }
}
