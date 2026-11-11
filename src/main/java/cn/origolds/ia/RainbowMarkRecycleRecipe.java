package cn.origolds.ia;

import com.mojang.serialization.MapCodec;
import java.util.List;
import net.minecraft.core.NonNullList;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.Level;

/**
 * 彩虹标记回收配方：合成格里有且仅有 1 个还能用的彩虹标记（其它格必须空）时，
 * 产出 1 颗魔金之核，并把标记自身打回合成格、耐久多扣 1。
 *
 * <p>即"用 1 点耐久换 1 颗核"，用于降级回收玩家手里的标记。</p>
 *
 * <p>26.x：{@code CustomRecipe} 无状态，编解码器用 unit；matches/assemble 以
 * {@code Recipe<CraftingInput>} 的特化签名直接覆写。</p>
 */
public final class RainbowMarkRecycleRecipe extends CustomRecipe {

    /** 全游戏唯一实例：数据包加载与网络解码必须共用同一个对象（原版 RepairItemRecipe.INSTANCE 模式）。 */
    public static final RainbowMarkRecycleRecipe INSTANCE = new RainbowMarkRecycleRecipe();
    public static final MapCodec<RainbowMarkRecycleRecipe> CODEC = MapCodec.unit(INSTANCE);
    public static final StreamCodec<RegistryFriendlyByteBuf, RainbowMarkRecycleRecipe> STREAM_CODEC =
            StreamCodec.unit(INSTANCE);

    public RainbowMarkRecycleRecipe() {
        super();
    }

    /** 网格里有且仅有 1 个还能用的彩虹标记（其它格不限，但不得再有第二个标记）。 */
    public static boolean isRecycleGrid(List<ItemStack> items) {
        if (OGEMagicCoreRef.get() == null) {
            return false;
        }
        boolean mark = false;
        for (ItemStack stack : items) {
            if (!stack.isEmpty()) {
                if (mark || stack.getItem() != ModBlocks.RAINBOW_MARK.get() || !OGERainbowMarkItem.isUsable(stack)) {
                    return false;
                }
                mark = true;
            }
        }
        return mark;
    }

    @Override
    public boolean matches(CraftingInput input, Level level) {
        return isRecycleGrid(input.items());
    }

    @Override
    public ItemStack assemble(CraftingInput input) {
        Item core = OGEMagicCoreRef.get();
        return core == null ? ItemStack.EMPTY : new ItemStack(core);
    }

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
    public RecipeSerializer<RainbowMarkRecycleRecipe> getSerializer() {
        return ModRecipes.RAINBOW_MARK_RECYCLE.get();
    }
}
