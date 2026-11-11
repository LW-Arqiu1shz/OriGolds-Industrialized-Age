package cn.origolds.ia;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 金源能量方块实体注册表。
 *
 * <p>四种发生器与电池共用一个方块实体类型 {@link #OGE_MACHINE}，
 * 实际产率/容量由各自方块携带并在构造方块实体时读取。</p>
 */
public final class ModBlockEntities {

    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(BuiltInRegistries.BLOCK_ENTITY_TYPE, OriGoldsIndustrializedAge.MOD_ID);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<OGEMachineBlockEntity>> OGE_MACHINE =
            BLOCK_ENTITIES.register("oge_machine", () -> new BlockEntityType<>(
                    OGEMachineBlockEntity::new,
                    ModBlocks.WIND_ORIGOLD_ENERGIA_GENERATOR.get(),
                    ModBlocks.WATER_ORIGOLD_ENERGIA_GENERATOR.get(),
                    ModBlocks.FIRE_ORIGOLD_ENERGIA_GENERATOR.get(),
                    ModBlocks.MAGIC_ORIGOLD_ENERGIA_GENERATOR.get(),
                    ModBlocks.ORIGOLD_ENERGIA_CAPACITOR.get()
            ));

    /** 金源伐木模块（Origold Logger）的方块实体。 */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<OGELoggerBlockEntity>> OGE_LOGGER =
            BLOCK_ENTITIES.register("oge_logger", () -> new BlockEntityType<>(
                    OGELoggerBlockEntity::new,
                    ModBlocks.ORIGOLD_LOGGER.get()
            ));

    /** 金源采掘模块（Origold Driller）的方块实体。 */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<OGEDrillerBlockEntity>> OGE_DRILLER =
            BLOCK_ENTITIES.register("oge_driller", () -> new BlockEntityType<>(
                    OGEDrillerBlockEntity::new,
                    ModBlocks.ORIGOLD_DRILLER.get()
            ));

    /**
     * 资源发生器（沙砾 / 黏土 / 黑曜石）的共用方块实体类型。
     *
     * <p>注册名沿用最初的 "oge_gravel_generator"（当时只有沙砾发生器一种），
     * 以免已有存档里的方块实体类型对不上号。</p>
     */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<OGEResourceGeneratorBlockEntity>> OGE_RESOURCE_GENERATOR =
            BLOCK_ENTITIES.register("oge_gravel_generator", () -> new BlockEntityType<>(
                    OGEResourceGeneratorBlockEntity::new,
                    ModBlocks.GRAVEL_GENERATOR.get(),
                    ModBlocks.CLAY_GENERATOR.get(),
                    ModBlocks.OBSIDIAN_GENERATOR.get()
            ));

    /** 金源粉碎机（Origold Pulverizer）的方块实体。 */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<OGEPulverizerBlockEntity>> OGE_PULVERIZER =
            BLOCK_ENTITIES.register("oge_pulverizer", () -> new BlockEntityType<>(
                    OGEPulverizerBlockEntity::new,
                    ModBlocks.ORIGOLD_PULVERIZER.get()
            ));

    /** 金源筛洗机（Origold Sifter）的方块实体。 */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<OGESifterBlockEntity>> OGE_SIFTER =
            BLOCK_ENTITIES.register("oge_sifter", () -> new BlockEntityType<>(
                    OGESifterBlockEntity::new,
                    ModBlocks.ORIGOLD_SIFTER.get()
            ));

    /** 金源虚空抽屉（Origold Void Drawer）的方块实体。 */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<OGEVoidDrawerBlockEntity>> OGE_VOID_DRAWER =
            BLOCK_ENTITIES.register("oge_void_drawer", () -> new BlockEntityType<>(
                    OGEVoidDrawerBlockEntity::new,
                    ModBlocks.ORIGOLD_VOID_DRAWER.get()
            ));

    /** 金源引力匣（Origold Gravity Cassette）的方块实体。 */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<OGEGravityCassetteBlockEntity>> OGE_GRAVITY_CASSETTE =
            BLOCK_ENTITIES.register("oge_gravity_cassette", () -> new BlockEntityType<>(
                    OGEGravityCassetteBlockEntity::new,
                    ModBlocks.ORIGOLD_GRAVITY_CASSETTE.get()
            ));

    /** 金源销毁站（Origold Voiding Station）的方块实体。 */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<OGEVoidingStationBlockEntity>> OGE_VOIDING_STATION =
            BLOCK_ENTITIES.register("oge_voiding_station", () -> new BlockEntityType<>(
                    OGEVoidingStationBlockEntity::new,
                    ModBlocks.ORIGOLD_VOIDING_STATION.get()
            ));

    /** 金源鼓风机（Origold Blower）的方块实体。 */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<OGEBlowerBlockEntity>> OGE_BLOWER =
            BLOCK_ENTITIES.register("oge_blower", () -> new BlockEntityType<>(
                    OGEBlowerBlockEntity::new,
                    ModBlocks.ORIGOLD_BLOWER.get()
            ));

    /** 金源自动附魔机（Origold Mech-Enchanter）的方块实体。 */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<OGEMechEnchanterBlockEntity>> OGE_MECH_ENCHANTER =
            BLOCK_ENTITIES.register("oge_mech_enchanter", () -> new BlockEntityType<>(
                    OGEMechEnchanterBlockEntity::new,
                    ModBlocks.ORIGOLD_MECH_ENCHANTER.get()
            ));

    /** 金源坷垃机（Origold-KeLa Machine）的方块实体。 */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<OGEKelaMachineBlockEntity>> OGE_KELA_MACHINE =
            BLOCK_ENTITIES.register("oge_kela_machine", () -> new BlockEntityType<>(
                    OGEKelaMachineBlockEntity::new,
                    ModBlocks.ORIGOLD_KELA_MACHINE.get()
            ));

    /** 金源放置器（Origold Placer）的方块实体。 */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<OGEPlacerBlockEntity>> OGE_PLACER =
            BLOCK_ENTITIES.register("oge_placer", () -> new BlockEntityType<>(
                    OGEPlacerBlockEntity::new,
                    ModBlocks.ORIGOLD_PLACER.get()
            ));

    /** 金源红石脉冲钟（Origold Redstone-Pulse Clock）的方块实体。 */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<OGERedstonePulseClockBlockEntity>> OGE_REDSTONE_PULSE_CLOCK =
            BLOCK_ENTITIES.register("oge_redstone_pulse_clock", () -> new BlockEntityType<>(
                    OGERedstonePulseClockBlockEntity::new,
                    ModBlocks.ORIGOLD_REDSTONE_PULSE_CLOCK.get()
            ));

    /** 金源超级漏斗（Origold Hyperhopper）的方块实体。 */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<OGEHyperhopperBlockEntity>> OGE_HYPERHOPPER =
            BLOCK_ENTITIES.register("oge_hyperhopper", () -> new BlockEntityType<>(
                    OGEHyperhopperBlockEntity::new,
                    ModBlocks.ORIGOLD_HYPERHOPPER.get()
            ));

    /** 金源超级熔炉（Origold Hyperfurnace）的方块实体。 */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<OGEHyperfurnaceBlockEntity>> OGE_HYPERFURNACE =
            BLOCK_ENTITIES.register("oge_hyperfurnace", () -> new BlockEntityType<>(
                    OGEHyperfurnaceBlockEntity::new,
                    ModBlocks.ORIGOLD_HYPERFURNACE.get()
            ));

    /** 金源炼成台（Origold Chrysopoeia Station）的方块实体。 */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<OGEChrysopoeiaStationBlockEntity>> OGE_CHRYSOPOEIA_STATION =
            BLOCK_ENTITIES.register("oge_chrysopoeia_station", () -> new BlockEntityType<>(
                    OGEChrysopoeiaStationBlockEntity::new,
                    ModBlocks.ORIGOLD_CHRYSOPOEIA_STATION.get()
            ));

    /** 金源机械合成台（Origold Mech Crafter）的方块实体。 */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<OGEMechCrafterBlockEntity>> OGE_MECH_CRAFTER =
            BLOCK_ENTITIES.register("oge_mech_crafter", () -> new BlockEntityType<>(
                    OGEMechCrafterBlockEntity::new,
                    ModBlocks.ORIGOLD_MECH_CRAFTER.get()
            ));

    /** 金源锯片台（Origold Sawblade Mount）的方块实体。 */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<OGESawbladeMountBlockEntity>> OGE_SAWBLADE_MOUNT =
            BLOCK_ENTITIES.register("oge_sawblade_mount", () -> new BlockEntityType<>(
                    OGESawbladeMountBlockEntity::new,
                    ModBlocks.ORIGOLD_SAWBLADE_MOUNT.get()
            ));

    private ModBlockEntities() {
    }

    public static void register(IEventBus modBus) {
        BLOCK_ENTITIES.register(modBus);
    }
}
