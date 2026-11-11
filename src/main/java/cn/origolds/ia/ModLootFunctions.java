package cn.origolds.ia;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.storage.loot.functions.LootItemFunction;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 本模组的战利品函数类型注册表。
 *
 * <p>26.x 起战利品函数注册表直接存放 {@link MapCodec}，
 * 不再有单独的 {@code LootItemFunctionType} 包装类型。</p>
 */
public final class ModLootFunctions {

    private static final DeferredRegister<MapCodec<? extends LootItemFunction>> FUNCTION_TYPES =
            DeferredRegister.create(Registries.LOOT_FUNCTION_TYPE, OriGoldsIndustrializedAge.MOD_ID);

    /** 挖掘机器时把内部缓存 OGE 写进掉落物（{@code origolds_ia:copy_oge_stored}）。 */
    public static final DeferredHolder<MapCodec<? extends LootItemFunction>, MapCodec<OGECopyEnergyStoredFunction>> COPY_OGE_STORED =
            FUNCTION_TYPES.register("copy_oge_stored", () -> OGECopyEnergyStoredFunction.CODEC);

    private ModLootFunctions() {
    }

    public static void register(IEventBus modBus) {
        FUNCTION_TYPES.register(modBus);
    }
}
