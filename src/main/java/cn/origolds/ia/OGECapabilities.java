package cn.origolds.ia;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

/**
 * 金源能量（OGE）能力的注册与访问入口。
 *
 * <p>通过 {@link #OGE_STORAGE} 能力，任意方块/逻辑都能查询这些机器的
 * OGE 存储、产率与容量。</p>
 */
public final class OGECapabilities {

    public static final BlockCapability<IGoldSourceEnergyStorage, Void> OGE_STORAGE =
            BlockCapability.create(
                    ResourceLocation.fromNamespaceAndPath(OriGoldsIndustrializedAge.MOD_ID, "oge_storage"),
                    IGoldSourceEnergyStorage.class,
                    Void.class);

    private OGECapabilities() {
    }

    public static void register(RegisterCapabilitiesEvent event) {
        event.registerBlock(OGE_STORAGE,
                (level, pos, state, be, context) ->
                        be instanceof OGEMachineBlockEntity machine ? machine.getOGEStorage() : null,
                ModBlocks.WIND_ORIGOLD_ENERGIA_GENERATOR.get(),
                ModBlocks.WATER_ORIGOLD_ENERGIA_GENERATOR.get(),
                ModBlocks.FIRE_ORIGOLD_ENERGIA_GENERATOR.get(),
                ModBlocks.MAGIC_ORIGOLD_ENERGIA_GENERATOR.get(),
                ModBlocks.ORIGOLD_ENERGIA_CAPACITOR.get());

        // 金源虚空抽屉是标准物品容器：对外暴露原版漏斗、本模组无线物流管道等
        // 都能直接读写的 IItemHandler 能力。
        event.registerBlock(net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK,
                (level, pos, state, be, context) ->
                        be instanceof OGEVoidDrawerBlockEntity drawer ? drawer.getItemHandler() : null,
                ModBlocks.ORIGOLD_VOID_DRAWER.get());

        // 金源引力匣同样对外暴露 IItemHandler（27 格、多类型）。
        event.registerBlock(net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK,
                (level, pos, state, be, context) ->
                        be instanceof OGEGravityCassetteBlockEntity cassette ? cassette.getItemHandler() : null,
                ModBlocks.ORIGOLD_GRAVITY_CASSETTE.get());

        // 金源超级漏斗也是标准 27 格物品容器，原版漏斗、物流管道都能读写。
        event.registerBlock(net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK,
                (level, pos, state, be, context) ->
                        be instanceof OGEHyperhopperBlockEntity hopper ? hopper.getItemHandler() : null,
                ModBlocks.ORIGOLD_HYPERHOPPER.get());

        // 金源炼金站的 27 格物品栏同样对外暴露 IItemHandler，方便漏斗 / 物流管道往里送料。
        event.registerBlock(net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK,
                (level, pos, state, be, context) ->
                        be instanceof OGEChrysopoeiaStationBlockEntity station ? station.getItemHandler() : null,
                ModBlocks.ORIGOLD_CHRYSOPOEIA_STATION.get());

        // 金源超级熔炉的 27 格存储也必须暴露 IItemHandler：超级漏斗、无线物流管线的终点
        // 都是先查这个能力再往里塞料，没有注册就完全查不到容器，谁也送不进来。
        event.registerBlock(net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK,
                (level, pos, state, be, context) ->
                        be instanceof OGEHyperfurnaceBlockEntity furnace ? furnace.getItemHandler() : null,
                ModBlocks.ORIGOLD_HYPERFURNACE.get());

        // 金源自动合成机的 27 格存储同理（每列只收本列锁定的物品，准入由 canPlaceItem 把关）。
        event.registerBlock(net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK,
                (level, pos, state, be, context) ->
                        be instanceof OGEMechCrafterBlockEntity crafter ? crafter.getItemHandler() : null,
                ModBlocks.ORIGOLD_MECH_CRAFTER.get());

        // 金源圆锯刃槽的对外视图是刻意不对称的（OGESawbladeMountItemHandler）：
        // 漏斗 / 物流管道往里塞只能进武器槽且必须过近战武器判定，往外抽只能抽废物槽。
        // 不注册这个能力，原版漏斗与自家的物流管道都完全看不见这台机器。
        event.registerBlock(net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK,
                (level, pos, state, be, context) ->
                        be instanceof OGESawbladeMountBlockEntity mount ? mount.getItemHandler() : null,
                ModBlocks.ORIGOLD_SAWBLADE_MOUNT.get());
    }
}
