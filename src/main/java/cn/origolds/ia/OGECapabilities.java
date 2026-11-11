package cn.origolds.ia;

import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.transfer.item.VanillaContainerWrapper;

/**
 * 金源能量（OGE）能力的注册与访问入口。
 *
 * <p>通过 {@link #OGE_STORAGE} 能力，任意方块/逻辑都能查询这些机器的
 * OGE 存储、产率与容量。</p>
 *
 * <p>26.1.2 起 NeoForge 的物品能力由 {@code Capabilities.ItemHandler} 改名为
 * {@link Capabilities.Item#BLOCK}，返回类型也从 {@code IItemHandler} 换成
 * {@code ResourceHandler<ItemResource>}。本模组的物品容器都实现了原版
 * {@code Container}，直接用 {@link VanillaContainerWrapper#of} 暴露即可。</p>
 */
public final class OGECapabilities {

    public static final BlockCapability<IGoldSourceEnergyStorage, Void> OGE_STORAGE =
            BlockCapability.create(
                    Identifier.fromNamespaceAndPath(OriGoldsIndustrializedAge.MOD_ID, "oge_storage"),
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
        // 都能直接读写的物品能力。
        event.registerBlock(Capabilities.Item.BLOCK,
                (level, pos, state, be, context) ->
                        be instanceof OGEVoidDrawerBlockEntity drawer ? VanillaContainerWrapper.of(drawer) : null,
                ModBlocks.ORIGOLD_VOID_DRAWER.get());

        // 金源引力匣同样对外暴露物品能力（27 格、多类型）。
        event.registerBlock(Capabilities.Item.BLOCK,
                (level, pos, state, be, context) ->
                        be instanceof OGEGravityCassetteBlockEntity cassette ? VanillaContainerWrapper.of(cassette) : null,
                ModBlocks.ORIGOLD_GRAVITY_CASSETTE.get());

        // 金源超级漏斗也是标准 27 格物品容器，原版漏斗、物流管道都能读写。
        event.registerBlock(Capabilities.Item.BLOCK,
                (level, pos, state, be, context) ->
                        be instanceof OGEHyperhopperBlockEntity hopper ? VanillaContainerWrapper.of(hopper) : null,
                ModBlocks.ORIGOLD_HYPERHOPPER.get());

        // 点金站：原料/燃料/产物槽对外暴露。
        event.registerBlock(Capabilities.Item.BLOCK,
                (level, pos, state, be, context) ->
                        be instanceof OGEChrysopoeiaStationBlockEntity station ? VanillaContainerWrapper.of(station) : null,
                ModBlocks.ORIGOLD_CHRYSOPOEIA_STATION.get());

        // 超能力熔炉：输入/燃料/产物槽对外暴露。
        event.registerBlock(Capabilities.Item.BLOCK,
                (level, pos, state, be, context) ->
                        be instanceof OGEHyperfurnaceBlockEntity furnace ? VanillaContainerWrapper.of(furnace) : null,
                ModBlocks.ORIGOLD_HYPERFURNACE.get());

        // 机械合成台：3×3 矩阵与产物槽对外暴露（漏斗自动进料的关键）。
        event.registerBlock(Capabilities.Item.BLOCK,
                (level, pos, state, be, context) ->
                        be instanceof OGEMechCrafterBlockEntity crafter ? VanillaContainerWrapper.of(crafter) : null,
                ModBlocks.ORIGOLD_MECH_CRAFTER.get());

        // 锯片台：物流/漏斗只能从对外敞开的槽位抽（IOGESuctionFilter 把关）。
        event.registerBlock(Capabilities.Item.BLOCK,
                (level, pos, state, be, context) ->
                        be instanceof OGESawbladeMountBlockEntity mount ? VanillaContainerWrapper.of(mount) : null,
                ModBlocks.ORIGOLD_SAWBLADE_MOUNT.get());
    }
}
