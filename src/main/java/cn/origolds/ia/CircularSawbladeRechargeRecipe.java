package cn.origolds.ia;

import java.util.List;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.Level;

/**
 * 工作台配方：把圆锯刃片和任意数量的铁块一起无序合成，一等一地恢复耐久。
 *
 * <p>规则与{@link RainbowMarkRechargeRecipe 虹彩印记充能}完全一样，只是材料从
 * 魔金之核换成了铁块：1 个铁块补 1 点耐久，只扣“补满真正需要”的那一部分，
 * 多出来的留在工作台里（见 {@link OGECircularSawbladeEvents#onCrafted}）。</p>
 */
public final class CircularSawbladeRechargeRecipe extends CustomRecipe {

    /** 数据包编解码：只有分组字段 {@code category}，缺省 {@code misc}。 */
    public static final MapCodec<CircularSawbladeRechargeRecipe> CODEC =
            RecordCodecBuilder.mapCodec(instance -> instance
                    .group(CraftingBookCategory.CODEC.optionalFieldOf("category", CraftingBookCategory.MISC)
                            .forGetter(CircularSawbladeRechargeRecipe::category))
                    .apply(instance, CircularSawbladeRechargeRecipe::new));

    /** 网络编解码：按原版特殊配方的写法读写分组字节，不能用 {@code StreamCodec.unit}。 */
    public static final StreamCodec<RegistryFriendlyByteBuf, CircularSawbladeRechargeRecipe> STREAM_CODEC =
            StreamCodec.composite(
                    CraftingBookCategory.STREAM_CODEC,
                    CircularSawbladeRechargeRecipe::category,
                    CircularSawbladeRechargeRecipe::new);

    public CircularSawbladeRechargeRecipe(CraftingBookCategory category) {
        super(category);
    }

    /** 这块材料是不是用来补耐久的铁块。 */
    public static boolean isMaterial(ItemStack stack) {
        return stack.is(Items.IRON_BLOCK);
    }

    /**
     * 盘面是不是“1 枚有损耗的圆锯刃片 + 至少 1 个铁块，且没有别的东西”。
     * 同一份判定同时给 {@link #matches} 与合成事件使用。
     */
    public static boolean isRechargeGrid(List<ItemStack> items) {
        boolean blade = false;
        for (ItemStack stack : items) {
            if (stack.isEmpty()) {
                continue;
            }
            if (stack.getItem() == ModBlocks.CIRCULAR_SAWBLADE.get()) {
                if (blade || stack.getCount() != 1 || stack.getDamageValue() <= 0) {
                    return false;
                }
                blade = true;
            } else if (!isMaterial(stack)) {
                return false;
            }
        }
        return blade && materialCount(items) > 0;
    }

    /** 盘里铁块的总数。 */
    public static int materialCount(List<ItemStack> items) {
        int count = 0;
        for (ItemStack stack : items) {
            if (isMaterial(stack)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    /** 刃片的耐久损耗（已损坏点数）；盘里没有刃片时为 0。 */
    public static int bladeDamage(List<ItemStack> items) {
        for (ItemStack stack : items) {
            if (stack.getItem() == ModBlocks.CIRCULAR_SAWBLADE.get()) {
                return Math.max(0, stack.getDamageValue());
            }
        }
        return 0;
    }

    /** 盘里非空铁块堆的数量——原版结算时每堆只会扣 1 个。 */
    public static int materialStacks(List<ItemStack> items) {
        int stacks = 0;
        for (ItemStack stack : items) {
            if (!stack.isEmpty() && isMaterial(stack)) {
                stacks++;
            }
        }
        return stacks;
    }

    /** 补满这枚刃片真正要消耗的铁块数量：等于已损耗的耐久，且不超过盘里铁块总数。 */
    public static int materialToConsume(List<ItemStack> items) {
        return Math.min(bladeDamage(items), materialCount(items));
    }

    @Override
    public boolean matches(CraftingInput input, Level level) {
        return isRechargeGrid(input.items());
    }

    @Override
    public ItemStack assemble(CraftingInput input, HolderLookup.Provider registries) {
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

    /**
     * 退料：把原版“每个非空铁块堆各扣 1 个”多扣掉的那部分补回来。
     * 盘里有 n 堆铁块、总共要消耗 c 个时，退 n - c 个铁块（每堆各 1 个）。
     */
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
    public boolean canCraftInDimensions(int width, int height) {
        return width * height >= 2;
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return ModRecipes.CIRCULAR_SAWBLADE_RECHARGE.get();
    }
}
