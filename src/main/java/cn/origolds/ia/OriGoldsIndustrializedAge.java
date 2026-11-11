package cn.origolds.ia;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

/**
 * 金源工艺：工业时代（OriGolds: Industrialized Age）
 *
 * <p>OriGolds（origolds）模组的附属模组。接入自建的“金源能量（OGE）”
 * 能量系统：方块、方块实体与能量能力在此统一注册。</p>
 */
@Mod(OriGoldsIndustrializedAge.MOD_ID)
public final class OriGoldsIndustrializedAge {
    public static final String MOD_ID = "origolds_ia";

    public OriGoldsIndustrializedAge(IEventBus modBus) {
        ModBlocks.register(modBus);
        ModEntities.register(modBus);
        ModBlockEntities.register(modBus);
        ModMenus.register(modBus);
        ModDataComponents.register(modBus);
        ModRecipes.register(modBus);
        ModLootFunctions.register(modBus);
        modBus.addListener(OGECapabilities::register);
    }
}
