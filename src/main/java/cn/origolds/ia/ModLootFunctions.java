package cn.origolds.ia;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.storage.loot.functions.LootItemFunctionType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 本模组的战利品函数类型注册表。
 */
public final class ModLootFunctions {

    private static final DeferredRegister<LootItemFunctionType<?>> FUNCTION_TYPES =
            DeferredRegister.create(Registries.LOOT_FUNCTION_TYPE, OriGoldsIndustrializedAge.MOD_ID);

    /** 挖掘机器时把内部缓存 OGE 写进掉落物（{@code origolds_ia:copy_oge_stored}）。 */
    public static final DeferredHolder<LootItemFunctionType<?>, LootItemFunctionType<OGECopyEnergyStoredFunction>> COPY_OGE_STORED =
            FUNCTION_TYPES.register("copy_oge_stored",
                    () -> new LootItemFunctionType<>(OGECopyEnergyStoredFunction.CODEC));

    private ModLootFunctions() {
    }

    public static void register(IEventBus modBus) {
        FUNCTION_TYPES.register(modBus);
    }
}
