package cn.origolds.ia;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 金源工艺：工业时代的方块注册表。
 *
 * <p>注册四种金源能量发生器与一种金源蓄能电容，并接入自建的 OGE
 * 能量系统：发生装置按“每秒 X 单位 OGE”产能，电容可储存最多 60000 单位；
 * 另有沙砾 / 黏土 / 黑曜石三种资源发生器，每秒消耗 OGE 在顶部生成对应方块。</p>
 */
public final class ModBlocks {

    private static final DeferredRegister.Blocks BLOCKS =
            DeferredRegister.createBlocks(OriGoldsIndustrializedAge.MOD_ID);
    private static final DeferredRegister.Items ITEMS =
            DeferredRegister.createItems(OriGoldsIndustrializedAge.MOD_ID);

    // 四种金源能量发生器。内部缓存上限 = 每秒产率 × 120（例如风驱 1×120=120 u 缓存）
    public static final DeferredBlock<Block> WIND_ORIGOLD_ENERGIA_GENERATOR =
            BLOCKS.registerBlock("wind_origold_energia_generator",
                    props -> new OGEMachineBlock(1, 120, OGEMachineKind.WIND, props),
                    () -> generatorProperties(MapColor.COLOR_YELLOW, 0));
    public static final DeferredBlock<Block> WATER_ORIGOLD_ENERGIA_GENERATOR =
            BLOCKS.registerBlock("water_origold_energia_generator",
                    props -> new OGEMachineBlock(2, 240, OGEMachineKind.WATER, props),
                    () -> generatorProperties(MapColor.COLOR_LIGHT_BLUE, 0));
    public static final DeferredBlock<Block> FIRE_ORIGOLD_ENERGIA_GENERATOR =
            BLOCKS.registerBlock("fire_origold_energia_generator",
                    props -> new OGEMachineBlock(10, 1200, OGEMachineKind.FIRE, props),
                    () -> generatorProperties(MapColor.COLOR_ORANGE, 13));
    public static final DeferredBlock<Block> MAGIC_ORIGOLD_ENERGIA_GENERATOR =
            BLOCKS.registerBlock("magic_origold_energia_generator",
                    props -> new OGEMachineBlock(50, 6000, OGEMachineKind.MAGIC, props),
                    () -> generatorProperties(MapColor.COLOR_PURPLE, 9));

    // 金源能储电池（稳压储能，容量 60000 OGE，不产能）。
    // 1px 厚上下金属底板 + 四角 1×1 立柱构成框架，四侧纯白 50% 半透明玻璃，
    // 内腔全封闭不透明，液面随电量上升；物品栏使用独立简洁立方体模型，
    // 像普通方块一样拿在手上；
    // noOcclusion 让贴邻方块照常渲染接触面，避免透过外壳看见被裁掉的“墙洞”。
    public static final DeferredBlock<Block> ORIGOLD_ENERGIA_CAPACITOR =
            BLOCKS.registerBlock("origold_energia_capacitor",
                    props -> new OGECapacitorBlock(0, 60000, props),
                    () -> generatorProperties(MapColor.GOLD, 7).noOcclusion());

    // 金源伐木模块：接入电网后消耗 OGE 自动伐木，每个面使用斧头侧视贴图。
    public static final DeferredBlock<Block> ORIGOLD_LOGGER =
            BLOCKS.registerBlock("origold_logger",
                    props -> new OGELoggerBlock(props),
                    ModBlocks::loggerProperties);

    // 金源采掘模块：接入电网后消耗 OGE 自动采掘相邻方块，每个面使用镐头侧视贴图。
    public static final DeferredBlock<Block> ORIGOLD_DRILLER =
            BLOCKS.registerBlock("origold_driller",
                    props -> new OGEDrillerBlock(props),
                    ModBlocks::drillerProperties);

    // 资源发生器：接入无线电网后每秒消耗 OGE，在顶部生成对应方块。
    // 沙砾发生器：每 1 秒消耗 5u OGE 在顶部生成 1 个沙砾。顶面 gravelgen_top，四侧与底面 gravelgen_side。
    public static final DeferredBlock<Block> GRAVEL_GENERATOR =
            BLOCKS.registerBlock("gravel_generator",
                    props -> new OGEResourceGeneratorBlock(Blocks.GRAVEL, 5, props),
                    () -> resourceGeneratorProperties(MapColor.STONE));

    // 黏土发生器：每 1 秒消耗 10u OGE 在顶部生成 1 个黏土块。顶面 claygen_top，四侧与底面 claygen_side。
    public static final DeferredBlock<Block> CLAY_GENERATOR =
            BLOCKS.registerBlock("clay_generator",
                    props -> new OGEResourceGeneratorBlock(Blocks.CLAY, 10, props),
                    () -> resourceGeneratorProperties(MapColor.COLOR_LIGHT_GRAY));

    // 黑曜石发生器：每 1 秒消耗 50u OGE 在顶部生成 1 个黑曜石。顶面 obsidiangen_top，四侧与底面 obsidiangen_side。
    public static final DeferredBlock<Block> OBSIDIAN_GENERATOR =
            BLOCKS.registerBlock("obsidian_generator",
                    props -> new OGEResourceGeneratorBlock(Blocks.OBSIDIAN, 50, props),
                    () -> resourceGeneratorProperties(MapColor.COLOR_BLACK));

    // 金源粉碎机：每刻尝试消耗 5u OGE，从四侧+底下的格子（掉落物或容器内物品）取料粉碎——
    // 粗矿变 2 精矿、矿石按正常挖掘产物 ×2、黑曜石出 6~10 个闪亮物品，产物掉在顶部。
    // 四侧 pulverizer_side，顶面 output_top，底面 top_bottom。
    public static final DeferredBlock<Block> ORIGOLD_PULVERIZER =
            BLOCKS.registerBlock("origold_pulverizer",
                    props -> new OGEPulverizerBlock(props),
                    () -> generatorProperties(MapColor.STONE, 0));

    // 金源筛洗机：每刻尝试消耗 5u OGE，从四侧+底下的格子（掉落物或容器内物品）取料筛洗——
    // 沙砾出 3/4/5 碎粒、黏土出 3/4/5 碎粒/碎片、闪亮物品再筛洗出随机矿物，产物掉在顶部。
    // 四侧 sifter_side，顶面 output_top，底面 top_bottom。
    public static final DeferredBlock<Block> ORIGOLD_SIFTER =
            BLOCKS.registerBlock("origold_sifter",
                    props -> new OGESifterBlock(props),
                    () -> generatorProperties(MapColor.STONE, 0));

    // 金源虚空抽屉：只存一种物品、容量 729 组的容器。正面 drawer_front、
    // 背面 container_back、其余四面 container_side；放置时正面水平朝向玩家
    // （只认南北东西四个水平方向，把手永远横着对着玩家镜头）。
    public static final DeferredBlock<Block> ORIGOLD_VOID_DRAWER =
            BLOCKS.registerBlock("origold_void_drawer",
                    props -> new OGEVoidDrawerBlock(props),
                    () -> generatorProperties(MapColor.GOLD, 0));

    // 金源引力匣：外观与虚空抽屉同款（背面 container_back、其余四面 container_side），
    // 正面贴图随吸附档位在 gravitycassette_front333/555/777/999 之间切换；容量 27 组
    // （同普通小箱子），每刻把（2×档位+1）³ 范围内的掉落物吸进存储（1 档 3×3×3 ~ 4 档
    // 9×9×9，默认 4 档）；四向水平朝向，放置时正面朝向玩家，挖掘工具是任意镐，
    // 空手 Alt+左键降档、Alt+右键升档。
    public static final DeferredBlock<Block> ORIGOLD_GRAVITY_CASSETTE =
            BLOCKS.registerBlock("origold_gravity_cassette",
                    props -> new OGEGravityCassetteBlock(props),
                    () -> generatorProperties(MapColor.GOLD, 0));

    // 金源销毁站：读取正上方那一格的容器物品或掉落物，物品数量连续若干刻不减少
    // 就整类销毁。四侧贴图随档位切换（1/2/5/10/20tbin_side），顶面 container_io_top-2、
    // 底面 container_back；放下默认 5tick 模式，空手右键循环切档。
    public static final DeferredBlock<Block> ORIGOLD_VOIDING_STATION =
            BLOCKS.registerBlock("origold_voiding_station",
                    props -> new OGEVoidingStationBlock(props),
                    () -> generatorProperties(MapColor.GOLD, 0));

    // 金源鼓风机：可朝上下南北东西六向放置，接入无线电网后持续工作，每 1 秒
    // 消耗“距离档 × 力量档”u OGE（1 ~ 32）。工作期间正面 fan_front1/fan_front2 每秒
    // 循环 4 次，并沿朝向吹出 9×距离档 格（最多 72 格）长的气流（可穿透非完整方块、
    // 被完整方块挡住），把气流内的实体以 9×力量档 格/秒（最多 36 格/秒）的恒定速度
    // 推走；四个侧面贴图随档位在 blower_side_dM_pN 之间切换。空手 Ctrl+左/右键调距离档、
    // Ctrl+Alt+左/右键调力量档（左下右上）；停机后收尾到 fan_front1。
    public static final DeferredBlock<Block> ORIGOLD_BLOWER =
            BLOCKS.registerBlock("origold_blower",
                    props -> new OGEBlowerBlock(props),
                    () -> generatorProperties(MapColor.GOLD, 0));

    // 金源自动附魔机：0.75 格高（同附魔台）的合并机器。顶面 enchanter_top、底面 top_bottom，
    // 四个侧面随档位在 enchanter_side_1 / enchanter_side_4 / enchanter_side_16 之间切换；
    // 空手 Ctrl+左键降档、Ctrl+右键升档（1x / 4x / 16x），右键打开三槽位界面。
    public static final DeferredBlock<Block> ORIGOLD_MECH_ENCHANTER =
            BLOCKS.registerBlock("origold_mech_enchanter",
                    props -> new OGEMechEnchanterBlock(props),
                    () -> generatorProperties(MapColor.GOLD, 0).noOcclusion());

    // 金源坷垃机：接入无线电网后每秒跑一轮，每轮消耗本档倍率（3/5/7/9）个骨粉，
    // 以及等于本轮作用范围内待处理方块数的 OGE（每个方块 1 OGE，与档位无关）。
    // 每轮把顶面暴露在空气中的泥土变成草方块，并在整轮内给可随机刻的方块补足随机刻
    // （额外机会 90% 生效，约 100 倍生长速度）；缺电 / 缺骨粉 / 没有目标就停机且不消耗资源。
    // 顶面 output_top、底面 top_bottom，四个侧面随档位在 jinkela_side333/555/777/999
    // 之间切换；空手 Ctrl+左键降档、Ctrl+右键升档（1/2/3/4 档，默认第 4 档 9×9×9）。
    public static final DeferredBlock<Block> ORIGOLD_KELA_MACHINE =
            BLOCKS.registerBlock("origold_kela_machine",
                    props -> new OGEKelaMachineBlock(props),
                    () -> generatorProperties(MapColor.COLOR_YELLOW, 0));

    // 金源放置器：先用手持方块 Alt+中键绑定唯一目标，再用空手 Alt+左键进入投影放置
    // 模式，把绑定方块在 9×9×9 范围内的目标位置与朝向一条条摆好、Alt+右键保存；
    // 之后它从四侧+底下取绑定方块，每 interval 刻往还没被占的投影位置放 1 个，
    // 每成功 1 个消耗 1u OGE。顶面 placer_top、底面 output_top，四侧随间隔档位在
    // placer_side1t/5t/10t/20t 之间切换；空手 Ctrl+左键降档、Ctrl+右键升档。
    public static final DeferredBlock<Block> ORIGOLD_PLACER =
            BLOCKS.registerBlock("origold_placer",
                    props -> new OGEPlacerBlock(props),
                    () -> generatorProperties(MapColor.GOLD, 0));

    // 金源红石脉冲钟：顶面/底面 top_bottom，四个侧面按倒计时进度在 clock0~clock7 之间切换
    // （每走完八分之一周期前进一格）。它是红石信号源，归零时向四周发出一次持续 2tick 的
    // 15 级脉冲并立刻重新计时；右键打开“时 : 分 : 秒 + ▷”的计时界面。
    public static final DeferredBlock<Block> ORIGOLD_REDSTONE_PULSE_CLOCK =
            BLOCKS.registerBlock("origold_redstone_pulse_clock",
                    props -> new OGERedstonePulseClockBlock(props),
                    () -> generatorProperties(MapColor.GOLD, 0));

    // 金源超级漏斗：形状与普通方块一致，六个面按输入端/输出端朝向贴不同箭头贴图。
    // 输出端朝向被放置到的方块、输入端朝上（同普通漏斗）；自身是 27 格容器，
    // 每刻把缓存推给输出端的容器，并按界面设定的间隔（默认 0.05 秒 = 1 刻）
    // 从输入端取一次物品，单次最多取“单次漏取上限”个。空手 Ctrl（+Alt/Shift）点鼠标
    // 可实时旋转输入端/输出端的朝向，转到另一端那面会自动跳过。
    public static final DeferredBlock<Block> ORIGOLD_HYPERHOPPER =
            BLOCKS.registerBlock("origold_hyperhopper",
                    props -> new OGEHyperhopperBlock(props),
                    () -> generatorProperties(MapColor.GOLD, 0));

    // 金源超级熔炉：外观与超级漏斗同族的金属方块，六面 hyperfurnace_*，正面贴图随档位在
    // hyperfurnace_front_1x / _5x / _20x 之间切换；空手 Ctrl+左/右键调档，右键打开界面。
    public static final DeferredBlock<Block> ORIGOLD_HYPERFURNACE =
            BLOCKS.registerBlock("origold_hyperfurnace",
                    props -> new OGEHyperfurnaceBlock(props),
                    () -> generatorProperties(MapColor.GOLD, 0));

    // 金源炼成台：把杂质“点石成金”的台式机器，顶面 enchanter_top、底面 top_bottom，
    // 四侧 chrysopoeia_side，正面 chrysopoeia_side 系；右键打开三槽位界面。
    public static final DeferredBlock<Block> ORIGOLD_CHRYSOPOEIA_STATION =
            BLOCKS.registerBlock("origold_chrysopoeia_station",
                    props -> new OGEChrysopoeiaStationBlock(props),
                    () -> generatorProperties(MapColor.GOLD, 0));

    // 金源机械合成台：自动合成机器，正面 mechcrafter_front、其余面同金属机箱；
    // 右键打开 3×3 合成格 + 锁定槽界面，顶上浮空展示上次产物。
    public static final DeferredBlock<Block> ORIGOLD_MECH_CRAFTER =
            BLOCKS.registerBlock("origold_mech_crafter",
                    props -> new OGEMechCrafterBlock(props),
                    () -> generatorProperties(MapColor.GOLD, 0).noOcclusion());

    // 金源锯片台：装入圆锯片后向贴着的方块持续“锯切”产出物品的机器；空手右键打开界面。
    public static final DeferredBlock<Block> ORIGOLD_SAWBLADE_MOUNT =
            BLOCKS.registerBlock("origold_sawblade_mount",
                    props -> new OGESawbladeMountBlock(props),
                    () -> generatorProperties(MapColor.GOLD, 0));

    // 金源圆锯片（钉在方块上的锯片方块）：无碰撞、无刻面的装饰/功能混合方块，
    // 由锯片实体命中方块后放置；任何推动都会把它打碎。
    public static final DeferredBlock<Block> SAWBLADE =
            BLOCKS.registerBlock("sawblade",
                    props -> new OGESawbladeBlock(props),
                    () -> BlockBehaviour.Properties.of()
                            .mapColor(MapColor.METAL)
                            .strength(0.0F)
                            .noCollision()
                            .noOcclusion()
                            .pushReaction(net.minecraft.world.level.material.PushReaction.POPPED)
                            .sound(SoundType.METAL));

    // 彩虹覆层：机器被彩虹标记点亮后贴在表面的特效方块（无碰撞、无掉落、随机器消失）。
    public static final DeferredBlock<Block> RAINBOW_OVERLAY =
            BLOCKS.registerBlock("rainbow_overlay",
                    props -> new OGERainbowOverlayBlock(props),
                    () -> BlockBehaviour.Properties.of()
                            .mapColor(MapColor.COLOR_PURPLE)
                            .strength(0.0F)
                            .noCollision()
                            .noOcclusion()
                            .noLootTable());

    // 方块物品（带“实用提示（浅蓝正体）+ 废话提示（浅灰斜体）”悬浮提示）
    public static final DeferredItem<BlockItem> WIND_ORIGOLD_ENERGIA_GENERATOR_ITEM =
            ITEMS.registerItem("wind_origold_energia_generator",
                    props -> new OGEMachineBlockItem(WIND_ORIGOLD_ENERGIA_GENERATOR.get(), props, 120, "wind"),
                    props -> props.useBlockDescriptionPrefix());
    public static final DeferredItem<BlockItem> WATER_ORIGOLD_ENERGIA_GENERATOR_ITEM =
            ITEMS.registerItem("water_origold_energia_generator",
                    props -> new OGEMachineBlockItem(WATER_ORIGOLD_ENERGIA_GENERATOR.get(), props, 240, "water"),
                    props -> props.useBlockDescriptionPrefix().rarity(Rarity.UNCOMMON));
    public static final DeferredItem<BlockItem> FIRE_ORIGOLD_ENERGIA_GENERATOR_ITEM =
            ITEMS.registerItem("fire_origold_energia_generator",
                    props -> new OGEMachineBlockItem(FIRE_ORIGOLD_ENERGIA_GENERATOR.get(), props, 1200, "fire"),
                    props -> props.useBlockDescriptionPrefix().rarity(Rarity.RARE));
    public static final DeferredItem<BlockItem> MAGIC_ORIGOLD_ENERGIA_GENERATOR_ITEM =
            ITEMS.registerItem("magic_origold_energia_generator",
                    props -> new OGEMachineBlockItem(MAGIC_ORIGOLD_ENERGIA_GENERATOR.get(), props, 6000, "magic"),
                    props -> props.useBlockDescriptionPrefix().rarity(Rarity.EPIC));
    public static final DeferredItem<BlockItem> ORIGOLD_ENERGIA_CAPACITOR_ITEM =
            ITEMS.registerItem("origold_energia_capacitor",
                    props -> new OGEMachineBlockItem(ORIGOLD_ENERGIA_CAPACITOR.get(), props, 60000, "capacitor"),
                    props -> props.useBlockDescriptionPrefix().rarity(Rarity.UNCOMMON));
    public static final DeferredItem<BlockItem> ORIGOLD_LOGGER_ITEM =
            ITEMS.registerItem("origold_logger",
                    props -> new OGELoggerBlockItem(ORIGOLD_LOGGER.get(), props),
                    props -> props.useBlockDescriptionPrefix());
    public static final DeferredItem<BlockItem> ORIGOLD_DRILLER_ITEM =
            ITEMS.registerItem("origold_driller",
                    props -> new OGEDrillerBlockItem(ORIGOLD_DRILLER.get(), props),
                    props -> props.useBlockDescriptionPrefix());
    public static final DeferredItem<BlockItem> GRAVEL_GENERATOR_ITEM =
            ITEMS.registerItem("gravel_generator",
                    props -> new OGEResourceGeneratorBlockItem(GRAVEL_GENERATOR.get(), props,
                            "tooltip.origolds_ia.useful.gravel", null),
                    props -> props.useBlockDescriptionPrefix().rarity(Rarity.RARE));
    public static final DeferredItem<BlockItem> CLAY_GENERATOR_ITEM =
            ITEMS.registerItem("clay_generator",
                    props -> new OGEResourceGeneratorBlockItem(CLAY_GENERATOR.get(), props,
                            "tooltip.origolds_ia.useful.clay", null),
                    props -> props.useBlockDescriptionPrefix().rarity(Rarity.RARE));
    public static final DeferredItem<BlockItem> OBSIDIAN_GENERATOR_ITEM =
            ITEMS.registerItem("obsidian_generator",
                    props -> new OGEResourceGeneratorBlockItem(OBSIDIAN_GENERATOR.get(), props,
                            "tooltip.origolds_ia.useful.obsidian", null),
                    props -> props.useBlockDescriptionPrefix().rarity(Rarity.RARE));
    public static final DeferredItem<BlockItem> ORIGOLD_PULVERIZER_ITEM =
            ITEMS.registerItem("origold_pulverizer",
                    props -> new OGEResourceGeneratorBlockItem(ORIGOLD_PULVERIZER.get(), props,
                            "tooltip.origolds_ia.useful.pulverizer", "tooltip.origolds_ia.bs.pulverizer"),
                    props -> props.useBlockDescriptionPrefix().rarity(Rarity.UNCOMMON));
    public static final DeferredItem<BlockItem> ORIGOLD_SIFTER_ITEM =
            ITEMS.registerItem("origold_sifter",
                    props -> new OGEResourceGeneratorBlockItem(ORIGOLD_SIFTER.get(), props,
                            "tooltip.origolds_ia.useful.sifter", "tooltip.origolds_ia.bs.sifter"),
                    props -> props.useBlockDescriptionPrefix().rarity(Rarity.UNCOMMON));
    public static final DeferredItem<BlockItem> ORIGOLD_VOID_DRAWER_ITEM =
            ITEMS.registerItem("origold_void_drawer",
                    props -> new OGEResourceGeneratorBlockItem(ORIGOLD_VOID_DRAWER.get(), props,
                            "tooltip.origolds_ia.useful.drawer", "tooltip.origolds_ia.bs.drawer"),
                    props -> props.useBlockDescriptionPrefix().rarity(Rarity.UNCOMMON));
    // 引力匣：提示写法为“实用 + 废话”。
    public static final DeferredItem<BlockItem> ORIGOLD_GRAVITY_CASSETTE_ITEM =
            ITEMS.registerItem("origold_gravity_cassette",
                    props -> new OGEResourceGeneratorBlockItem(ORIGOLD_GRAVITY_CASSETTE.get(), props,
                            "tooltip.origolds_ia.useful.gravity_cassette", "tooltip.origolds_ia.bs.gravity_cassette"),
                    props -> props.useBlockDescriptionPrefix().rarity(Rarity.UNCOMMON));
    public static final DeferredItem<BlockItem> ORIGOLD_VOIDING_STATION_ITEM =
            ITEMS.registerItem("origold_voiding_station",
                    props -> new OGEResourceGeneratorBlockItem(ORIGOLD_VOIDING_STATION.get(), props,
                            "tooltip.origolds_ia.useful.voiding_station",
                            "tooltip.origolds_ia.ctrl.voiding_station",
                            "tooltip.origolds_ia.bs.voiding_station"),
                    props -> props.useBlockDescriptionPrefix().rarity(Rarity.UNCOMMON));
    // 鼓风机：提示写法为“实用 + 废话”。
    public static final DeferredItem<BlockItem> ORIGOLD_BLOWER_ITEM =
            ITEMS.registerItem("origold_blower",
                    props -> new OGEResourceGeneratorBlockItem(ORIGOLD_BLOWER.get(), props,
                            "tooltip.origolds_ia.useful.blower", "tooltip.origolds_ia.bs.blower"),
                    props -> props.useBlockDescriptionPrefix());
    // 自动附魔机：提示写法为“实用 + 按住Ctrl详情 + 废话”。
    public static final DeferredItem<BlockItem> ORIGOLD_MECH_ENCHANTER_ITEM =
            ITEMS.registerItem("origold_mech_enchanter",
                    props -> new OGEResourceGeneratorBlockItem(ORIGOLD_MECH_ENCHANTER.get(), props,
                            "tooltip.origolds_ia.useful.mech_enchanter",
                            "tooltip.origolds_ia.ctrl.mech_enchanter",
                            "tooltip.origolds_ia.bs.mech_enchanter"),
                    props -> props.useBlockDescriptionPrefix().rarity(Rarity.EPIC));
    // 坷垃机：提示写法为“实用 + 废话”。
    public static final DeferredItem<BlockItem> ORIGOLD_KELA_MACHINE_ITEM =
            ITEMS.registerItem("origold_kela_machine",
                    props -> new OGEResourceGeneratorBlockItem(ORIGOLD_KELA_MACHINE.get(), props,
                            "tooltip.origolds_ia.useful.kela", "tooltip.origolds_ia.bs.kela"),
                    props -> props.useBlockDescriptionPrefix());
    // 金源放置器：实用提示 + 废话提示。
    public static final DeferredItem<BlockItem> ORIGOLD_PLACER_ITEM =
            ITEMS.registerItem("origold_placer",
                    props -> new OGEResourceGeneratorBlockItem(ORIGOLD_PLACER.get(), props,
                            "tooltip.origolds_ia.useful.placer", "tooltip.origolds_ia.bs.placer"),
                    props -> props.useBlockDescriptionPrefix());
    // 金源红石脉冲钟：提示写法为“实用 + 废话”。
    public static final DeferredItem<BlockItem> ORIGOLD_REDSTONE_PULSE_CLOCK_ITEM =
            ITEMS.registerItem("origold_redstone_pulse_clock",
                    props -> new OGEResourceGeneratorBlockItem(ORIGOLD_REDSTONE_PULSE_CLOCK.get(), props,
                            "tooltip.origolds_ia.useful.redstone_pulse_clock",
                            "tooltip.origolds_ia.bs.redstone_pulse_clock"),
                    props -> props.useBlockDescriptionPrefix());
    // 金源超级漏斗：提示写法为“实用 + 废话”。
    public static final DeferredItem<BlockItem> ORIGOLD_HYPERHOPPER_ITEM =
            ITEMS.registerItem("origold_hyperhopper",
                    props -> new OGEResourceGeneratorBlockItem(ORIGOLD_HYPERHOPPER.get(), props,
                            "tooltip.origolds_ia.useful.hyperhopper",
                            "tooltip.origolds_ia.bs.hyperhopper"),
                    props -> props.useBlockDescriptionPrefix());
    // 金源超级熔炉：提示写法为“实用 + 废话”。
    public static final DeferredItem<BlockItem> ORIGOLD_HYPERFURNACE_ITEM =
            ITEMS.registerItem("origold_hyperfurnace",
                    props -> new OGEResourceGeneratorBlockItem(ORIGOLD_HYPERFURNACE.get(), props,
                            "tooltip.origolds_ia.useful.hyperfurnace",
                            "tooltip.origolds_ia.bs.hyperfurnace"),
                    props -> props.useBlockDescriptionPrefix());
    // 金源炼成台：提示写法为“实用 + 废话”。
    public static final DeferredItem<BlockItem> ORIGOLD_CHRYSOPOEIA_STATION_ITEM =
            ITEMS.registerItem("origold_chrysopoeia_station",
                    props -> new OGEResourceGeneratorBlockItem(ORIGOLD_CHRYSOPOEIA_STATION.get(), props,
                            "tooltip.origolds_ia.useful.chrysopoeia_station",
                            "tooltip.origolds_ia.bs.chrysopoeia_station"),
                    props -> props.useBlockDescriptionPrefix().rarity(Rarity.COMMON));
    // 金源机械合成台：提示写法为“实用 + 废话”。
    public static final DeferredItem<BlockItem> ORIGOLD_MECH_CRAFTER_ITEM =
            ITEMS.registerItem("origold_mech_crafter",
                    props -> new OGEResourceGeneratorBlockItem(ORIGOLD_MECH_CRAFTER.get(), props,
                            "tooltip.origolds_ia.useful.mech_crafter",
                            "tooltip.origolds_ia.bs.mech_crafter"),
                    props -> props.useBlockDescriptionPrefix().rarity(Rarity.COMMON));
    // 金源锯片台：提示写法为“实用 + 按住Ctrl详情 + 废话”。
    public static final DeferredItem<BlockItem> ORIGOLD_SAWBLADE_MOUNT_ITEM =
            ITEMS.registerItem("origold_sawblade_mount",
                    props -> new OGEResourceGeneratorBlockItem(ORIGOLD_SAWBLADE_MOUNT.get(), props,
                            "tooltip.origolds_ia.useful.sawblade_mount",
                            "tooltip.origolds_ia.ctrl.sawblade_mount",
                            "tooltip.origolds_ia.bs.sawblade_mount"),
                    props -> props.useBlockDescriptionPrefix().rarity(Rarity.COMMON));

    // 闪亮系列：筛洗沙砾/黏土、粉碎黑曜石的产物，也可再进筛洗机换随机矿物。
    // 稀有度：碎块=稀有（附魔闪光）、碎片=罕见、碎粒=普通。提示写法为“实用 + 废话”。
    public static final DeferredItem<Item> SHINY_CHUNK =
            ITEMS.registerItem("shiny_chunk",
                    props -> new OGEFlavoredItem(props,
                            "tooltip.origolds_ia.useful.shiny_chunk", "tooltip.origolds_ia.bs.shiny_chunk"),
                    props -> props.rarity(Rarity.RARE));
    public static final DeferredItem<Item> SHINY_SHARD =
            ITEMS.registerItem("shiny_shard",
                    props -> new OGEFlavoredItem(props,
                            "tooltip.origolds_ia.useful.shiny_shard", "tooltip.origolds_ia.bs.shiny_shard"),
                    props -> props.rarity(Rarity.UNCOMMON));
    public static final DeferredItem<Item> SHINY_CRUMB =
            ITEMS.registerItem("shiny_crumb",
                    props -> new OGEFlavoredItem(props,
                            "tooltip.origolds_ia.useful.shiny_crumb", "tooltip.origolds_ia.bs.shiny_crumb"),
                    props -> props.rarity(Rarity.COMMON));

    // 资源发生器核心：各类资源生成器的共同合成材料（罕见，堆叠上限沿用默认 64）。
    public static final DeferredItem<Item> RESOURCE_GENERATOR_CORE =
            ITEMS.registerItem("resource_generator_core",
                    props -> new OGEFlavoredItem(props,
                            "tooltip.origolds_ia.useful.core", "tooltip.origolds_ia.bs.core"),
                    props -> props.rarity(Rarity.UNCOMMON));

    // 志愿者观察员：放下去就有一个小人替你盯着机器报警的实体物品（16 可堆叠）。
    public static final DeferredItem<Item> VOLUNTEER_WATCHER =
            ITEMS.registerItem("volunteer_watcher",
                    props -> new OGEVolunteerWatcherItem(props),
                    props -> props.stacksTo(16).rarity(Rarity.UNCOMMON));

    // 彩虹标记：给机器上“彩虹 buff”的工具，耐久 64，三档倍率（5x/20x/100x）。
    public static final DeferredItem<Item> RAINBOW_MARK =
            ITEMS.registerItem("rainbow_mark",
                    props -> new OGERainbowMarkItem(props),
                    props -> props.stacksTo(1).durability(64).rarity(Rarity.EPIC));

    // 金源圆锯片：装进锯片台的高速锯片，耐久 64，兼作近战武器。
    public static final DeferredItem<Item> CIRCULAR_SAWBLADE =
            ITEMS.registerItem("circular_sawblade",
                    props -> new OGECircularSawbladeItem(props),
                    props -> props.stacksTo(1).durability(64)
                            .attributes(OGECircularSawbladeItem.createAttributes())
                            .rarity(Rarity.UNCOMMON));

    /** 父模组（origolds）“金源工艺”创造页签。 */
    private static final ResourceKey<CreativeModeTab> ORIGOLDS_TAB =
            ResourceKey.create(Registries.CREATIVE_MODE_TAB,
                    Identifier.fromNamespaceAndPath("origolds", "origolds"));

    private ModBlocks() {
    }

    /**
     * 金源能量装置通用方块属性：金属手感，可按需设置发光等级。
     */
    private static BlockBehaviour.Properties generatorProperties(MapColor color, int lightLevel) {
        BlockBehaviour.Properties props = BlockBehaviour.Properties.of()
                .mapColor(color)
                .strength(3.0F, 6.0F)
                .sound(SoundType.METAL)
                .requiresCorrectToolForDrops();
        if (lightLevel > 0) {
            props = props.lightLevel(state -> lightLevel);
        }
        return props;
    }

    /** 金源伐木模块的方块属性：金属机器，红色系。 */
    private static BlockBehaviour.Properties loggerProperties() {
        return BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_RED)
                .strength(3.0F, 6.0F)
                .sound(SoundType.METAL)
                .requiresCorrectToolForDrops();
    }

    /** 金源采掘模块的方块属性：金属机器，红色系。 */
    private static BlockBehaviour.Properties drillerProperties() {
        return BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_RED)
                .strength(3.0F, 6.0F)
                .sound(SoundType.METAL)
                .requiresCorrectToolForDrops();
    }

    /** 资源发生器的方块属性：金属机器，色系随产物材质而定。 */
    private static BlockBehaviour.Properties resourceGeneratorProperties(MapColor color) {
        return BlockBehaviour.Properties.of()
                .mapColor(color)
                .strength(3.0F, 6.0F)
                .sound(SoundType.METAL)
                .requiresCorrectToolForDrops();
    }

    /** 注册到模组事件总线。 */
    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        modBus.addListener(ModBlocks::onBuildCreativeTab);
    }

    /** 把金源能量装置加入“金源工艺”创造页签（以及原“功能性方块”页签）。 */
    private static void onBuildCreativeTab(BuildCreativeModeTabContentsEvent event) {
        if (CreativeModeTabs.FUNCTIONAL_BLOCKS.equals(event.getTabKey()) || ORIGOLDS_TAB.equals(event.getTabKey())) {
            event.accept(WIND_ORIGOLD_ENERGIA_GENERATOR.toStack(), CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
            event.accept(WATER_ORIGOLD_ENERGIA_GENERATOR.toStack(), CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
            event.accept(FIRE_ORIGOLD_ENERGIA_GENERATOR.toStack(), CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
            event.accept(MAGIC_ORIGOLD_ENERGIA_GENERATOR.toStack(), CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
            event.accept(ORIGOLD_ENERGIA_CAPACITOR.toStack(), CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
            event.accept(ORIGOLD_LOGGER.toStack(), CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
            event.accept(ORIGOLD_DRILLER.toStack(), CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
            event.accept(GRAVEL_GENERATOR.toStack(), CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
            event.accept(CLAY_GENERATOR.toStack(), CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
            event.accept(OBSIDIAN_GENERATOR.toStack(), CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
            event.accept(ORIGOLD_PULVERIZER.toStack(), CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
            event.accept(ORIGOLD_SIFTER.toStack(), CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
            event.accept(ORIGOLD_VOID_DRAWER.toStack(), CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
            event.accept(ORIGOLD_GRAVITY_CASSETTE.toStack(), CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
            event.accept(ORIGOLD_VOIDING_STATION.toStack(), CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
            event.accept(ORIGOLD_BLOWER.toStack(), CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
            event.accept(ORIGOLD_MECH_ENCHANTER.toStack(), CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
            event.accept(ORIGOLD_KELA_MACHINE.toStack(), CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
            event.accept(ORIGOLD_PLACER.toStack(), CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
            event.accept(ORIGOLD_REDSTONE_PULSE_CLOCK.toStack(), CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
            event.accept(ORIGOLD_HYPERHOPPER.toStack(), CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
            event.accept(ORIGOLD_HYPERFURNACE.toStack(), CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
            event.accept(ORIGOLD_CHRYSOPOEIA_STATION.toStack(), CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
            event.accept(ORIGOLD_MECH_CRAFTER.toStack(), CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
            event.accept(ORIGOLD_SAWBLADE_MOUNT.toStack(), CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
        }
        // 资源发生器核心是合成材料：进“原材料”页签与父模组“金源工艺”页签。
        if (CreativeModeTabs.INGREDIENTS.equals(event.getTabKey()) || ORIGOLDS_TAB.equals(event.getTabKey())) {
            event.accept(RESOURCE_GENERATOR_CORE.toStack(), CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
            event.accept(VOLUNTEER_WATCHER.toStack(), CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
            event.accept(RAINBOW_MARK.toStack(), CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
            event.accept(CIRCULAR_SAWBLADE.toStack(), CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
            event.accept(SHINY_CHUNK.toStack(), CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
            event.accept(SHINY_SHARD.toStack(), CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
            event.accept(SHINY_CRUMB.toStack(), CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
        }
    }
}
