package cn.origolds.ia;

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
 * 工作台配方：把一枚还有至少 1 点耐久的虹彩印记单独合成，换 1 个魔金之核，
 * 印记本身掉 1 点耐久留回格子里。
 *
 * <p>产物是魔金之核，印记通过 {@link #getRemainingItems} 退还到原来的格子，
 * 所以在工作台里可以连点，直到耐久归零为止。耐久为 0 的印记不再匹配。</p>
 */
public final class RainbowMarkRecycleRecipe extends CustomRecipe {

    /**
     * 数据包编解码：只有原版配方都带的分组字段 {@code category}，缺省 {@code misc}。
     */
    public static final MapCodec<RainbowMarkRecycleRecipe> CODEC = RecordCodecBuilder.mapCodec(instance -> instance
            .group(CraftingBookCategory.CODEC.optionalFieldOf("category", CraftingBookCategory.MISC)
                    .forGetter(RainbowMarkRecycleRecipe::category))
            .apply(instance, RainbowMarkRecycleRecipe::new));

    /**
     * 网络编解码：必须真的往缓冲区里读写分组字节。
     *
     * <p>反例是 {@code StreamCodec.unit}——它的 {@code encode} 会先拿
     * {@code Object.equals} 校验待发对象与单例是否同一个，而数据包加载出来的配方
     * 是新建实例，于是发送配方列表时直接抛 {@link IllegalStateException}，
     * 客户端同步阶段就掉线。这里按原版 {@code SimpleCraftingRecipeSerializer}
     * 的写法，把分组当作唯一字段编解码。</p>
     */
    public static final StreamCodec<RegistryFriendlyByteBuf, RainbowMarkRecycleRecipe> STREAM_CODEC =
            StreamCodec.composite(
                    CraftingBookCategory.STREAM_CODEC,
                    RainbowMarkRecycleRecipe::category,
                    RainbowMarkRecycleRecipe::new);

    public RainbowMarkRecycleRecipe(CraftingBookCategory category) {
        super(category);
    }

    /** 盘面是不是“只有 1 枚还有耐久的虹彩印记”。 */
    public static boolean isRecycleGrid(java.util.List<ItemStack> items) {
        // 没装父模组就拿不到魔金之核，配方直接不成立。
        if (OGEMagicCoreRef.get() == null) {
            return false;
        }
        boolean mark = false;
        for (ItemStack stack : items) {
            if (stack.isEmpty()) {
                continue;
            }
            if (mark || stack.getItem() != ModBlocks.RAINBOW_MARK.get()
                    || !OGERainbowMarkItem.isUsable(stack)) {
                return false;
            }
            mark = true;
        }
        return mark;
    }

    @Override
    public boolean matches(CraftingInput input, Level level) {
        return isRecycleGrid(input.items());
    }

    @Override
    public ItemStack assemble(CraftingInput input, HolderLookup.Provider registries) {
        Item core = OGEMagicCoreRef.get();
        return core == null ? ItemStack.EMPTY : new ItemStack(core);
    }

    /** 印记本体退回格子里，耐久 -1。 */
    @Override
    public NonNullList<ItemStack> getRemainingItems(CraftingInput input) {
        NonNullList<ItemStack> remainders = NonNullList.withSize(input.size(), ItemStack.EMPTY);
        for (int i = 0; i < input.size(); i++) {
            ItemStack stack = input.getItem(i);
            if (stack.getItem() == ModBlocks.RAINBOW_MARK.get()) {
                ItemStack back = stack.copyWithCount(1);
                back.setDamageValue(stack.getDamageValue() + 1);
                remainders.set(i, back);
            }
        }
        return remainders;
    }

    @Override
    public boolean canCraftInDimensions(int width, int height) {
        return width * height >= 1;
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return ModRecipes.RAINBOW_MARK_RECYCLE.get();
    }
}
