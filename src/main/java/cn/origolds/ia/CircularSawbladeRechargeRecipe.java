package cn.origolds.ia;

import com.mojang.serialization.MapCodec;
import java.util.List;
import net.minecraft.core.NonNullList;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.Level;

/**
 * 圆锯片充能配方（无状态自定义合成）：1 片带伤的圆锯片 + N 块铁块，
 * 每块铁块回 1 点耐久（吃铁块数 = min(已损耐久, 在场铁块总数)），锯片必须恰好 1 片、
 * 带损伤，其余非空格必须全是铁块。
 *
 * <p>铁块按堆消耗会留下"不够一整堆"的零头：配方与 {@code getRemainingItems} 一起保证
 * 返还的零头总量恰好等于"应保留块数"；{@link OGECircularSawbladeEvents} 在合成结束后
 * 把合成格里的整堆收缩到同样口径。</p>
 *
 * <p>26.x 变更：{@code CustomRecipe} 变为无状态（无构造参数、category/placementInfo 自带
 * 实现），编解码器用 unit；{@code matches/assemble} 直接以 {@link CraftingInput} 覆写
 * （Recipe&lt;CraftingInput&gt; 特化）；{@code canCraftInDimensions} 已删除——
 * 充能网格判定要求同时有锯片和铁块，本身就至少占两格。</p>
 */
public final class CircularSawbladeRechargeRecipe extends CustomRecipe {

    /** 全游戏唯一实例：数据包加载与网络解码必须共用同一个对象（原版 RepairItemRecipe.INSTANCE 模式）。 */
    public static final CircularSawbladeRechargeRecipe INSTANCE = new CircularSawbladeRechargeRecipe();
    public static final MapCodec<CircularSawbladeRechargeRecipe> CODEC = MapCodec.unit(INSTANCE);
    public static final StreamCodec<RegistryFriendlyByteBuf, CircularSawbladeRechargeRecipe> STREAM_CODEC =
            StreamCodec.unit(INSTANCE);

    public CircularSawbladeRechargeRecipe() {
        super();
    }

    /** 充能材料：铁块。 */
    public static boolean isMaterial(ItemStack stack) {
        return stack.is(Items.IRON_BLOCK);
    }

    /** 网格是否恰好是"1 片带伤圆锯片 + 至少 1 块铁块、别无他物"。 */
    public static boolean isRechargeGrid(List<ItemStack> items) {
        boolean blade = false;
        for (ItemStack stack : items) {
            if (!stack.isEmpty()) {
                if (stack.getItem() == ModBlocks.CIRCULAR_SAWBLADE.get()) {
                    if (blade || stack.getCount() != 1 || stack.getDamageValue() <= 0) {
                        return false;
                    }
                    blade = true;
                } else if (!isMaterial(stack)) {
                    return false;
                }
            }
        }
        return blade && materialCount(items) > 0;
    }

    /** 在场铁块总数（按个计）。 */
    public static int materialCount(List<ItemStack> items) {
        int count = 0;
        for (ItemStack stack : items) {
            if (isMaterial(stack)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    /** 锯片当前已损耐久。 */
    public static int bladeDamage(List<ItemStack> items) {
        for (ItemStack stack : items) {
            if (stack.getItem() == ModBlocks.CIRCULAR_SAWBLADE.get()) {
                return Math.max(0, stack.getDamageValue());
            }
        }
        return 0;
    }

    /** 在场铁块堆数（按堆计）。 */
    public static int materialStacks(List<ItemStack> items) {
        int stacks = 0;
        for (ItemStack stack : items) {
            if (!stack.isEmpty() && isMaterial(stack)) {
                stacks++;
            }
        }
        return stacks;
    }

    /** 本网格实际会被吃掉的铁块数：min(已损耐久, 在场铁块总数)。 */
    public static int materialToConsume(List<ItemStack> items) {
        return Math.min(bladeDamage(items), materialCount(items));
    }

    @Override
    public boolean matches(CraftingInput input, Level level) {
        return isRechargeGrid(input.items());
    }

    /** 产出：修好 min(损失, 铁块数) 点耐久的锯片（复制 1 片，保留其余组件）。 */
    @Override
    public ItemStack assemble(CraftingInput input) {
        ItemStack blade = ItemStack.EMPTY;
        for (ItemStack stack : input.items()) {
            if (stack.getItem() == ModBlocks.CIRCULAR_SAWBLADE.get()) {
                blade = stack;
                break;
            }
        }
        if (blade.isEmpty()) {
            return ItemStack.EMPTY;
        }
        ItemStack result = blade.copyWithCount(1);
        result.setDamageValue(Math.max(0, blade.getDamageValue() - materialToConsume(input.items())));
        return result;
    }

    /** 整堆铁块被吃但只需部分块数时，按"堆数 − 应吃块数"的零头逐格返还 1 块铁块。 */
    @Override
    public NonNullList<ItemStack> getRemainingItems(CraftingInput input) {
        NonNullList<ItemStack> remainders = NonNullList.withSize(input.size(), ItemStack.EMPTY);
        List<ItemStack> items = input.items();
        if (!isRechargeGrid(items)) {
            return remainders;
        }
        int add = materialStacks(items) - materialToConsume(items);
        if (add <= 0) {
            return remainders;
        }
        for (int i = 0; i < input.size() && add > 0; i++) {
            ItemStack stack = input.getItem(i);
            if (!stack.isEmpty() && isMaterial(stack)) {
                remainders.set(i, new ItemStack(Items.IRON_BLOCK));
                add--;
            }
        }
        return remainders;
    }

    @Override
    public RecipeSerializer<CircularSawbladeRechargeRecipe> getSerializer() {
        return ModRecipes.CIRCULAR_SAWBLADE_RECHARGE.get();
    }
}
