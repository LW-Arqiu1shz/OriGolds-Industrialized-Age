package cn.origolds.ia;

import java.util.List;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.Level;

/**
 * 工作台配方：把虹彩印记和任意数量的魔金之核一起无序合成，一等一地恢复耐久。
 *
 * <p>例如印记只剩 3 点耐久，盘里放 20 个魔金之核，合出来就是 23 点耐久的同一枚印记
 * （上限 {@value OGERainbowMarkItem#MAX_DURABILITY}）。</p>
 *
 * <p>核不会白吃：只扣“补满耐久真正需要”的那一部分，多出来的留在工作台里。例如一枚
 * 还剩 54 点耐久的印记（已损耗 10 点）配 64 个核，合成后印记满耐久，盘里剩 54 个核。
 * 印记满耐久时本配方不再匹配，没法继续拿核去合。</p>
 *
 * <p>原版每个格子只会扣 1 个，凑不出“一次扣十几个核”的效果，所以扣料分两半走：
 * 消耗量比核堆数多时由 {@link OGERainbowMarkEvents#onCrafted} 在结算前压小堆叠数；
 * 消耗量比核堆数少时多扣的那点由 {@link #getRemainingItems} 补回去。</p>
 */
public final class RainbowMarkRechargeRecipe extends CustomRecipe {

    /** 数据包编解码：只有分组字段 {@code category}，缺省 {@code misc}。 */
    public static final MapCodec<RainbowMarkRechargeRecipe> CODEC = RecordCodecBuilder.mapCodec(instance -> instance
            .group(CraftingBookCategory.CODEC.optionalFieldOf("category", CraftingBookCategory.MISC)
                    .forGetter(RainbowMarkRechargeRecipe::category))
            .apply(instance, RainbowMarkRechargeRecipe::new));

    /**
     * 网络编解码：不能用 {@code StreamCodec.unit}——它的 {@code encode} 会校验待发对象
     * 是否就是那个单例，而数据包加载出来的配方是新实例，发送时必然抛
     * {@link IllegalStateException} 导致进世界掉线。这里按原版特殊配方的写法，
     * 真的读写一个分组字节。
     */
    public static final StreamCodec<RegistryFriendlyByteBuf, RainbowMarkRechargeRecipe> STREAM_CODEC =
            StreamCodec.composite(
                    CraftingBookCategory.STREAM_CODEC,
                    RainbowMarkRechargeRecipe::category,
                    RainbowMarkRechargeRecipe::new);

    public RainbowMarkRechargeRecipe(CraftingBookCategory category) {
        super(category);
    }

    /**
     * 盘面是不是“1 枚有损耗的虹彩印记 + 至少 1 个魔金之核，且没有别的东西”。
     *
     * <p>同一份判定同时给 {@link #matches} 与合成事件使用，保证“扣材料”与“算耐久”
     * 认的是同一盘东西。</p>
     */
    public static boolean isRechargeGrid(List<ItemStack> items) {
        boolean mark = false;
        for (ItemStack stack : items) {
            if (stack.isEmpty()) {
                continue;
            }
            if (stack.getItem() == ModBlocks.RAINBOW_MARK.get()) {
                if (mark || stack.getCount() != 1 || stack.getDamageValue() <= 0) {
                    return false;
                }
                mark = true;
            } else if (!OGEMagicCoreRef.isCore(stack)) {
                return false;
            }
        }
        return mark && coreCount(items) > 0;
    }

    /** 盘里魔金之核的总数。 */
    public static int coreCount(List<ItemStack> items) {
        int cores = 0;
        for (ItemStack stack : items) {
            if (OGEMagicCoreRef.isCore(stack)) {
                cores += stack.getCount();
            }
        }
        return cores;
    }

    /** 印记当前的耐久损耗（已损坏点数）；盘里没有印记时为 0。 */
    public static int markDamage(List<ItemStack> items) {
        for (ItemStack stack : items) {
            if (stack.getItem() == ModBlocks.RAINBOW_MARK.get()) {
                return Math.max(0, stack.getDamageValue());
            }
        }
        return 0;
    }

    /** 盘里非空魔金之核堆的数量——原版结算时每堆只会扣 1 个。 */
    public static int coreStacks(List<ItemStack> items) {
        int stacks = 0;
        for (ItemStack stack : items) {
            if (!stack.isEmpty() && OGEMagicCoreRef.isCore(stack)) {
                stacks++;
            }
        }
        return stacks;
    }

    /**
     * 补满这枚印记真正要消耗的魔金之核数量：等于已损耗的耐久，且不超过盘里核的总数。
     *
     * <p>损耗 10 点、盘里 64 个核时只消耗 10 个；损耗 10 点、盘里只有 3 个核时就全吃掉。</p>
     */
    public static int coresToConsume(List<ItemStack> items) {
        return Math.min(markDamage(items), coreCount(items));
    }

    @Override
    public boolean matches(CraftingInput input, Level level) {
        return isRechargeGrid(input.items());
    }

    @Override
    public ItemStack assemble(CraftingInput input, HolderLookup.Provider registries) {
        ItemStack mark = ItemStack.EMPTY;
        for (ItemStack stack : input.items()) {
            if (stack.getItem() == ModBlocks.RAINBOW_MARK.get()) {
                mark = stack;
                break;
            }
        }
        if (mark.isEmpty()) {
            return ItemStack.EMPTY;
        }
        ItemStack result = mark.copyWithCount(1);
        result.setDamageValue(Math.max(0, mark.getDamageValue() - coresToConsume(input.items())));
        return result;
    }

    /**
     * 退料：把原版“每个非空核堆各扣 1 个”多扣掉的那部分补回来。
     *
     * <p>盘里有 <b>n</b> 堆核，总共要消耗 <b>c</b> 个时，原版扣完只剩 <b>总数 - n</b> 个，
     * 而我们真正想留下的是 <b>总数 - c</b> 个，所以要退 <b>n - c</b> 个核（只在 n &gt; c 时）。
     * 每堆各加 1 个正好摊到 n 堆上，单堆不会超过 64。</p>
     *
     * <p>印记那一格不退：充能就是把旧印记吃掉、换成同一档位的新印记，退回来会凭空多一枚。</p>
     */
    @Override
    public NonNullList<ItemStack> getRemainingItems(CraftingInput input) {
        NonNullList<ItemStack> remainders = NonNullList.withSize(input.size(), ItemStack.EMPTY);
        List<ItemStack> items = input.items();
        if (!isRechargeGrid(items)) {
            return remainders;
        }
        int add = coreStacks(items) - coresToConsume(items);
        Item core = OGEMagicCoreRef.get();
        if (add <= 0 || core == null) {
            return remainders;
        }
        for (int i = 0; i < input.size() && add > 0; i++) {
            ItemStack stack = input.getItem(i);
            if (!stack.isEmpty() && OGEMagicCoreRef.isCore(stack)) {
                remainders.set(i, new ItemStack(core));
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
        return ModRecipes.RAINBOW_MARK_RECHARGE.get();
    }
}
