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
 * 彩虹标记充能配方（无状态自定义合成）：1 个带损伤的彩虹标记 + N 颗魔金之核，
 * 每颗核回 1 点耐久（吃核数 = min(已损耐久, 在场核总数)）；标记必须恰好 1 个、
 * 带损伤，其余非空格必须全是魔金之核。
 *
 * <p>核按堆消耗会留下"不够一整堆"的零头：{@link #getRemainingItems} 与
 * {@link OGERainbowMarkEvents#onCrafted} 一起保证返还的零头总量恰好等于应保留核数。</p>
 *
 * <p>26.x：{@code CustomRecipe} 无状态，编解码器用 unit；matches/assemble 以
 * {@code Recipe<CraftingInput>} 的特化签名直接覆写。</p>
 */
public final class RainbowMarkRechargeRecipe extends CustomRecipe {

    /**
     * 全游戏唯一的实例。原版 {@code RepairItemRecipe.INSTANCE} 的标准写法：
     * 数据包加载（服务端/客户端各自）与网络解码（{@code StreamCodec.unit}）
     * 必须返回同一个对象——否则服务端配方表里的实例与客户端网络同步器解出的
     * 实例身份不等，同步配方时直接报错把玩家踢下线。
     */
    public static final RainbowMarkRechargeRecipe INSTANCE = new RainbowMarkRechargeRecipe();
    public static final MapCodec<RainbowMarkRechargeRecipe> CODEC = MapCodec.unit(INSTANCE);
    public static final StreamCodec<RegistryFriendlyByteBuf, RainbowMarkRechargeRecipe> STREAM_CODEC =
            StreamCodec.unit(INSTANCE);

    public RainbowMarkRechargeRecipe() {
        super();
    }

    /** 网格是否恰好是"1 个带损伤标记 + 至少 1 颗核、别无他物"。 */
    public static boolean isRechargeGrid(List<ItemStack> items) {
        boolean mark = false;
        for (ItemStack stack : items) {
            if (!stack.isEmpty()) {
                if (stack.getItem() == ModBlocks.RAINBOW_MARK.get()) {
                    if (mark || stack.getCount() != 1 || stack.getDamageValue() <= 0) {
                        return false;
                    }
                    mark = true;
                } else if (!OGEMagicCoreRef.isCore(stack)) {
                    return false;
                }
            }
        }
        return mark && coreCount(items) > 0;
    }

    /** 在场核总数（按个计）。 */
    public static int coreCount(List<ItemStack> items) {
        int cores = 0;
        for (ItemStack stack : items) {
            if (OGEMagicCoreRef.isCore(stack)) {
                cores += stack.getCount();
            }
        }
        return cores;
    }

    /** 标记当前的已损耐久。 */
    public static int markDamage(List<ItemStack> items) {
        for (ItemStack stack : items) {
            if (stack.getItem() == ModBlocks.RAINBOW_MARK.get()) {
                return Math.max(0, stack.getDamageValue());
            }
        }
        return 0;
    }

    /** 在场核堆数（每堆至少返 1 个零头）。 */
    public static int coreStacks(List<ItemStack> items) {
        int stacks = 0;
        for (ItemStack stack : items) {
            if (!stack.isEmpty() && OGEMagicCoreRef.isCore(stack)) {
                stacks++;
            }
        }
        return stacks;
    }

    /** 本次充能要吃掉的核数。 */
    public static int coresToConsume(List<ItemStack> items) {
        return Math.min(markDamage(items), coreCount(items));
    }

    @Override
    public boolean matches(CraftingInput input, Level level) {
        return isRechargeGrid(input.items());
    }

    /** 按网格里的核数回复标记耐久。 */
    @Override
    public ItemStack assemble(CraftingInput input) {
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
    public RecipeSerializer<RainbowMarkRechargeRecipe> getSerializer() {
        return ModRecipes.RAINBOW_MARK_RECHARGE.get();
    }
}
