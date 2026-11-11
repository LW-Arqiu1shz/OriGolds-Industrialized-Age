package cn.origolds.ia;

import com.mojang.serialization.MapCodec;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 本模组的自定义配方序列化器注册表。
 *
 * <p>目前只有虹彩印记的两条“特殊配方”（不显示在配方书里、结果按盘面动态算）：
 * {@link RainbowMarkRechargeRecipe 充能}与{@link RainbowMarkRecycleRecipe 回收}。
 * 两者除了原版配方通用的 {@code category} 分组外没有别的字段，代码里跟原版
 * {@code SimpleCraftingRecipeSerializer} 一样，用 RecordCodecBuilder + composite
 * 把分组字段序列化出去。数据包里写一行
 * {@code {"type": "origolds_ia:rainbow_mark_recharge"}} 即可，分组默认按
 * {@code misc} 处理。</p>
 */
public final class ModRecipes {

    private static final DeferredRegister<RecipeSerializer<?>> SERIALIZERS =
            DeferredRegister.create(Registries.RECIPE_SERIALIZER, OriGoldsIndustrializedAge.MOD_ID);

    /** 虹彩印记 + 任意数量魔金之核 → 恢复耐久。 */
    public static final DeferredHolder<RecipeSerializer<?>, RecipeSerializer<RainbowMarkRechargeRecipe>>
            RAINBOW_MARK_RECHARGE = SERIALIZERS.register("rainbow_mark_recharge",
            () -> new RecipeSerializer<RainbowMarkRechargeRecipe>() {
                @Override
                public MapCodec<RainbowMarkRechargeRecipe> codec() {
                    return RainbowMarkRechargeRecipe.CODEC;
                }

                @Override
                public StreamCodec<RegistryFriendlyByteBuf, RainbowMarkRechargeRecipe> streamCodec() {
                    return RainbowMarkRechargeRecipe.STREAM_CODEC;
                }
            });

    /** 虹彩印记单独合成 → 1 个魔金之核，印记耐久 -1。 */
    public static final DeferredHolder<RecipeSerializer<?>, RecipeSerializer<RainbowMarkRecycleRecipe>>
            RAINBOW_MARK_RECYCLE = SERIALIZERS.register("rainbow_mark_recycle",
            () -> new RecipeSerializer<RainbowMarkRecycleRecipe>() {
                @Override
                public MapCodec<RainbowMarkRecycleRecipe> codec() {
                    return RainbowMarkRecycleRecipe.CODEC;
                }

                @Override
                public StreamCodec<RegistryFriendlyByteBuf, RainbowMarkRecycleRecipe> streamCodec() {
                    return RainbowMarkRecycleRecipe.STREAM_CODEC;
                }
            });

    /** 圆锯刃片 + 任意数量铁块 → 恢复耐久。 */
    public static final DeferredHolder<RecipeSerializer<?>, RecipeSerializer<CircularSawbladeRechargeRecipe>>
            CIRCULAR_SAWBLADE_RECHARGE = SERIALIZERS.register("circular_sawblade_recharge",
            () -> new RecipeSerializer<CircularSawbladeRechargeRecipe>() {
                @Override
                public MapCodec<CircularSawbladeRechargeRecipe> codec() {
                    return CircularSawbladeRechargeRecipe.CODEC;
                }

                @Override
                public StreamCodec<RegistryFriendlyByteBuf, CircularSawbladeRechargeRecipe> streamCodec() {
                    return CircularSawbladeRechargeRecipe.STREAM_CODEC;
                }
            });

    private ModRecipes() {
    }

    public static void register(IEventBus modBus) {
        SERIALIZERS.register(modBus);
    }
}
