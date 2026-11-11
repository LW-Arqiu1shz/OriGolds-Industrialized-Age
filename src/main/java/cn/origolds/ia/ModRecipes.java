package cn.origolds.ia;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 本模组的配方序列化器注册表。
 *
 * <p>三种都是按合成格规则判定的特殊配方（彩虹标记充能 / 回收、圆锯片充能）。
 * 26.x 起 {@link RecipeSerializer} 是 (MapCodec, StreamCodec) 的记录类型，直接构造即可。</p>
 */
public final class ModRecipes {

    private static final DeferredRegister<RecipeSerializer<?>> SERIALIZERS =
            DeferredRegister.create(Registries.RECIPE_SERIALIZER, OriGoldsIndustrializedAge.MOD_ID);

    /** 彩虹标记充能：标记 + 任意颗魔金之核，按核数恢复耐久。 */
    public static final DeferredHolder<RecipeSerializer<?>, RecipeSerializer<RainbowMarkRechargeRecipe>> RAINBOW_MARK_RECHARGE =
            SERIALIZERS.register("rainbow_mark_recharge",
                    () -> new RecipeSerializer<>(RainbowMarkRechargeRecipe.CODEC,
                            RainbowMarkRechargeRecipe.STREAM_CODEC));

    /** 彩虹标记回收：标记 + 染料若干，回收出魔金之核。 */
    public static final DeferredHolder<RecipeSerializer<?>, RecipeSerializer<RainbowMarkRecycleRecipe>> RAINBOW_MARK_RECYCLE =
            SERIALIZERS.register("rainbow_mark_recycle",
                    () -> new RecipeSerializer<>(RainbowMarkRecycleRecipe.CODEC,
                            RainbowMarkRecycleRecipe.STREAM_CODEC));

    /** 圆锯片充能：锯片 + 魔金之核，按核数恢复耐久。 */
    public static final DeferredHolder<RecipeSerializer<?>, RecipeSerializer<CircularSawbladeRechargeRecipe>> CIRCULAR_SAWBLADE_RECHARGE =
            SERIALIZERS.register("circular_sawblade_recharge",
                    () -> new RecipeSerializer<>(CircularSawbladeRechargeRecipe.CODEC,
                            CircularSawbladeRechargeRecipe.STREAM_CODEC));

    private ModRecipes() {
    }

    public static void register(IEventBus modBus) {
        SERIALIZERS.register(modBus);
    }
}
