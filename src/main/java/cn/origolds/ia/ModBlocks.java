package cn.origolds.ia;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
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
import net.minecraft.world.level.material.PushReaction;
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
            BLOCKS.register("wind_origold_energia_generator",
                    () -> new OGEMachineBlock(1, 120, OGEMachineKind.WIND, generatorProperties(MapColor.COLOR_YELLOW, 0)));
    public static final DeferredBlock<Block> WATER_ORIGOLD_ENERGIA_GENERATOR =
            BLOCKS.register("water_origold_energia_generator",
                    () -> new OGEMachineBlock(2, 240, OGEMachineKind.WATER, generatorProperties(MapColor.COLOR_LIGHT_BLUE, 0)));
    public static final DeferredBlock<Block> FIRE_ORIGOLD_ENERGIA_GENERATOR =
            BLOCKS.register("fire_origold_energia_generator",
                    () -> new OGEMachineBlock(10, 1200, OGEMachineKind.FIRE, generatorProperties(MapColor.COLOR_ORANGE, 13)));
    public static final DeferredBlock<Block> MAGIC_ORIGOLD_ENERGIA_GENERATOR =
            BLOCKS.register("magic_origold_energia_generator",
                    () -> new OGEMachineBlock(50, 6000, OGEMachineKind.MAGIC, generatorProperties(MapColor.COLOR_PURPLE, 9)));

    // 金源能储电池（稳压储能，容量 60000 OGE，不产能）。
    // 1px 厚上下金属底板 + 四角 1×1 立柱构成框架，四侧纯白 50% 半透明玻璃，
    // 内腔全封闭不透明，液面随电量上升；物品栏使用独立简洁立方体模型，
    // 像普通方块一样拿在手上；
    // noOcclusion 让贴邻方块照常渲染接触面，避免透过外壳看见被裁掉的“墙洞”。
    public static final DeferredBlock<Block> ORIGOLD_ENERGIA_CAPACITOR =
            BLOCKS.register("origold_energia_capacitor",
                    () -> new OGECapacitorBlock(0, 60000,
                            generatorProperties(MapColor.GOLD, 7).noOcclusion()));

    // 金源伐木模块：接入电网后消耗 OGE 自动伐木，每个面使用斧头侧视贴图。
    public static final DeferredBlock<Block> ORIGOLD_LOGGER =
            BLOCKS.register("origold_logger",
                    () -> new OGELoggerBlock(loggerProperties()));

    // 金源采掘模块：接入电网后消耗 OGE 自动采掘相邻方块，每个面使用镐头侧视贴图。
    public static final DeferredBlock<Block> ORIGOLD_DRILLER =
            BLOCKS.register("origold_driller",
                    () -> new OGEDrillerBlock(drillerProperties()));

    // 资源发生器：接入无线电网后每秒消耗 OGE，在顶部生成对应方块。
    // 沙砾发生器：每 1 秒消耗 5u OGE 在顶部生成 1 个沙砾。顶面 gravelgen_top，四侧与底面 gravelgen_side。
    public static final DeferredBlock<Block> GRAVEL_GENERATOR =
            BLOCKS.register("gravel_generator",
                    () -> new OGEResourceGeneratorBlock(Blocks.GRAVEL, 5, resourceGeneratorProperties(MapColor.STONE)));

    // 黏土发生器：每 1 秒消耗 10u OGE 在顶部生成 1 个黏土块。顶面 claygen_top，四侧与底面 claygen_side。
    public static final DeferredBlock<Block> CLAY_GENERATOR =
            BLOCKS.register("clay_generator",
                    () -> new OGEResourceGeneratorBlock(Blocks.CLAY, 10, resourceGeneratorProperties(MapColor.COLOR_LIGHT_GRAY)));

    // 黑曜石发生器：每 1 秒消耗 50u OGE 在顶部生成 1 个黑曜石。顶面 obsidiangen_top，四侧与底面 obsidiangen_side。
    public static final DeferredBlock<Block> OBSIDIAN_GENERATOR =
            BLOCKS.register("obsidian_generator",
                    () -> new OGEResourceGeneratorBlock(Blocks.OBSIDIAN, 50, resourceGeneratorProperties(MapColor.COLOR_BLACK)));

    // 金源粉碎机：每刻尝试消耗 5u OGE，从四侧+底下的格子（掉落物或容器内物品）取料粉碎——
    // 粗矿变 2 精矿、矿石按正常挖掘产物 ×2、黑曜石出 6~10 个闪亮物品，产物掉在顶部。
    // 四侧 pulverizer_side，顶面 output_top，底面 top_bottom。
    public static final DeferredBlock<Block> ORIGOLD_PULVERIZER =
            BLOCKS.register("origold_pulverizer",
                    () -> new OGEPulverizerBlock(generatorProperties(MapColor.STONE, 0)));

    // 金源筛洗机：每刻尝试消耗 5u OGE，从四侧+底下的格子（掉落物或容器内物品）取料筛洗——
    // 沙砾出 3/4/5 碎粒、黏土出 3/4/5 碎粒/碎片、闪亮物品再筛洗出随机矿物，产物掉在顶部。
    // 四侧 sifter_side，顶面 output_top，底面 top_bottom。
    public static final DeferredBlock<Block> ORIGOLD_SIFTER =
            BLOCKS.register("origold_sifter",
                    () -> new OGESifterBlock(generatorProperties(MapColor.STONE, 0)));

    // 金源虚空抽屉：只存一种物品、容量 729 组的容器。正面 drawer_front、
    // 背面 container_back、其余四面 container_side；放置时正面水平朝向玩家
    // （只认南北东西四个水平方向，把手永远横着对着玩家镜头）。
    public static final DeferredBlock<Block> ORIGOLD_VOID_DRAWER =
            BLOCKS.register("origold_void_drawer",
                    () -> new OGEVoidDrawerBlock(generatorProperties(MapColor.GOLD, 0)));

    // 金源引力匣：外观与虚空抽屉同款（背面 container_back、其余四面 container_side），
    // 正面贴图随吸附档位在 gravitycassette_front333/555/777/999 之间切换；容量 27 组
    // （同普通小箱子），每刻把（2×档位+1）³ 范围内的掉落物吸进存储（1 档 3×3×3 ~ 4 档
    // 9×9×9，默认 4 档）；四向水平朝向，放置时正面朝向玩家，挖掘工具是任意镐，
    // 空手 Alt+左键降档、Alt+右键升档。
    public static final DeferredBlock<Block> ORIGOLD_GRAVITY_CASSETTE =
            BLOCKS.register("origold_gravity_cassette",
                    () -> new OGEGravityCassetteBlock(generatorProperties(MapColor.GOLD, 0)));

    // 金源销毁站：读取正上方那一格的容器物品或掉落物，物品数量连续若干刻不减少
    // 就整类销毁。四侧贴图随档位切换（1/2/5/10/20tbin_side），顶面 container_io_top-2、
    // 底面 container_back；放下默认 5tick 模式，空手右键循环切档。
    public static final DeferredBlock<Block> ORIGOLD_VOIDING_STATION =
            BLOCKS.register("origold_voiding_station",
                    () -> new OGEVoidingStationBlock(generatorProperties(MapColor.GOLD, 0)));

    // 金源鼓风机：可朝上下南北东西六向放置，接入无线电网后持续工作，每 1 秒
    // 消耗“距离档 × 力量档”u OGE（1 ~ 32）。工作期间正面 fan_front1/fan_front2 每秒
    // 循环 4 次，并沿朝向吹出 9×距离档 格（最多 72 格）长的气流（可穿透非完整方块、
    // 被完整方块挡住），把气流内的实体以 9×力量档 格/秒（最多 36 格/秒）的恒定速度
    // 推走；四个侧面贴图随档位在 blower_side_dM_pN 之间切换。空手 Ctrl+左/右键调距离档、
    // Ctrl+Alt+左/右键调力量档（左下右上）；停机后收尾到 fan_front1。
    public static final DeferredBlock<Block> ORIGOLD_BLOWER =
            BLOCKS.register("origold_blower",
                    () -> new OGEBlowerBlock(generatorProperties(MapColor.GOLD, 0)));

    // 金源自动附魔机：0.75 格高（同附魔台）的合并机器。顶面 enchanter_top、底面 top_bottom，
    // 四个侧面随档位在 enchanter_side_1 / enchanter_side_4 / enchanter_side_16 之间切换；
    // 空手 Ctrl+左键降档、Ctrl+右键升档（1x / 4x / 16x），右键打开三槽位界面。
    public static final DeferredBlock<Block> ORIGOLD_MECH_ENCHANTER =
            BLOCKS.register("origold_mech_enchanter",
                    () -> new OGEMechEnchanterBlock(generatorProperties(MapColor.GOLD, 0).noOcclusion()));

    // 金源坷垃机：接入无线电网后每秒跑一轮，每轮消耗本档倍率（3/5/7/9）个骨粉，
    // 以及等于本轮作用范围内待处理方块数的 OGE（每个方块 1 OGE，与档位无关）。
    // 每轮把顶面暴露在空气中的泥土变成草方块，并在整轮内给可随机刻的方块补足随机刻
    // （额外机会 90% 生效，约 100 倍生长速度）；缺电 / 缺骨粉 / 没有目标就停机且不消耗资源。
    // 顶面 output_top、底面 top_bottom，四个侧面随档位在 jinkela_side333/555/777/999
    // 之间切换；空手 Ctrl+左键降档、Ctrl+右键升档（1/2/3/4 档，默认第 4 档 9×9×9）。
    public static final DeferredBlock<Block> ORIGOLD_KELA_MACHINE =
            BLOCKS.register("origold_kela_machine",
                    () -> new OGEKelaMachineBlock(generatorProperties(MapColor.COLOR_YELLOW, 0)));

    // 金源放置器：先用手持方块 Alt+中键绑定唯一目标，再用空手 Alt+左键进入投影放置
    // 模式，把绑定方块在 9×9×9 范围内的目标位置与朝向一条条摆好、Alt+右键保存；
    // 之后它从四侧+底下取绑定方块，每 interval 刻往还没被占的投影位置放 1 个，
    // 每成功 1 个消耗 1u OGE。顶面 placer_top、底面 output_top，四侧随间隔档位在
    // placer_side1t/5t/10t/20t 之间切换；空手 Ctrl+左键降档、Ctrl+右键升档。
    public static final DeferredBlock<Block> ORIGOLD_PLACER =
            BLOCKS.register("origold_placer",
                    () -> new OGEPlacerBlock(generatorProperties(MapColor.GOLD, 0)));

    // 金源红石脉冲钟：顶面/底面 top_bottom，四个侧面按倒计时进度在 clock0~clock7 之间切换
    // （每走完八分之一周期前进一格）。它是红石信号源，归零时向四周发出一次持续 2tick 的
    // 15 级脉冲并立刻重新计时；右键打开“时 : 分 : 秒 + ▷”的计时界面。
    public static final DeferredBlock<Block> ORIGOLD_REDSTONE_PULSE_CLOCK =
            BLOCKS.register("origold_redstone_pulse_clock",
                    () -> new OGERedstonePulseClockBlock(generatorProperties(MapColor.GOLD, 0)));

    // 金源超级漏斗：形状与普通方块一致，六个面按输入端/输出端朝向贴不同箭头贴图。
    // 输出端朝向被放置到的方块、输入端朝上（同普通漏斗）；自身是 27 格容器，
    // 每刻把缓存推给输出端的容器，并按界面设定的间隔（默认 0.05 秒 = 1 刻）
    // 从输入端取一次物品，单次最多取“单次漏取上限”个。空手 Ctrl（+Alt/Shift）点鼠标
    // 可实时旋转输入端/输出端的朝向，转到另一端那面会自动跳过。
    public static final DeferredBlock<Block> ORIGOLD_HYPERHOPPER =
            BLOCKS.register("origold_hyperhopper",
                    () -> new OGEHyperhopperBlock(generatorProperties(MapColor.GOLD, 0)));

    // 金源超级熔炉：正面 hyperfurnace_front_1x/5x/20x 随档位切换，其余五面 top_bottom；
    // 消耗 OGE 烧炼物品，速率随档位为每秒 1/5/20 个（每 20/4/1 tick 一个），
    // 每个消耗 2/4/8u OGE。从四侧+底下取料，产物掉在顶部，空手 Ctrl+左/右键切档。
    public static final DeferredBlock<Block> ORIGOLD_HYPERFURNACE =
            BLOCKS.register("origold_hyperfurnace",
                    () -> new OGEHyperfurnaceBlock(generatorProperties(MapColor.GOLD, 0)));

    // 金源炼金站：四侧 chrysopoeia_side、顶面 output_top、底面 top_bottom。
    // 27 格物品栏只接受拥有炼金凝造价量（CRV）的物品，收到即销毁并累加 CRV；
    // 每累计到 2048 就尝试消耗 10u OGE，成功则在顶部冒出 1 个金锭。
    public static final DeferredBlock<Block> ORIGOLD_CHRYSOPOEIA_STATION =
            BLOCKS.register("origold_chrysopoeia_station",
                    () -> new OGEChrysopoeiaStationBlock(generatorProperties(MapColor.GOLD, 0)));

    // 金源自动合成机：正面 mechcrafter_front（9 个格子按锁定内容动态渲染）、
    // 顶面 output_top、底面 top_bottom、其余三面 chrysopoeia_side。
    // 9 个合成格用“拿着物品左键锁定、右键清除”来摆配方，27 格存储拆成 9 个竖着的
    // 3 格、每列只收本列锁定的物品；配方有效且材料齐时每刻扣 1u OGE 自动合成，
    // 产物从顶部输出端冒出。
    public static final DeferredBlock<Block> ORIGOLD_MECH_CRAFTER =
            BLOCKS.register("origold_mech_crafter",
                    () -> new OGEMechCrafterBlock(generatorProperties(MapColor.GOLD, 0)));

    // 金源圆锯刃槽：手持圆锯刃片 Shift+右键某个面，就在那个面上装一片刀刃（扣 1 点耐久）。
    // 顶面/底面 circular_saw_top_bottom、四侧 circular_saw_sides，放置时不需要定向。
    // 刀刃本体见 ModEntities.SAWBLADE（实体）；刃槽被破坏会清掉挂在它上面的刀刃实体，
    // 每片掉 1 个铁块。
    public static final DeferredBlock<Block> ORIGOLD_SAWBLADE_MOUNT =
            BLOCKS.register("origold_sawblade_mount",
                    () -> new OGESawbladeMountBlock(generatorProperties(MapColor.GOLD, 0)));

    // 圆锯刀刃的模型载体方块：刀刃本体已经是实体（ModEntities.SAWBLADE），这个方块不会再被
    // 放进世界，留在注册表里只是给 OGESawbladeRenderer 提供 blockstates/sawblade.json 的三个
    // 模型。没有物品、不进创造页签。
    public static final DeferredBlock<Block> SAWBLADE =
            BLOCKS.register("sawblade",
                    () -> new OGESawbladeBlock(BlockBehaviour.Properties.of()
                            .mapColor(MapColor.METAL)
                            .strength(0.0F)
                            .noCollission()
                            .noOcclusion()
                            .pushReaction(PushReaction.DESTROY)
                            .sound(SoundType.METAL)));

    // 虹彩印记叠加层的技术性方块：没有物品、没有战利品表，只作为 OGERainbowOverlayRenderer
    // 的渲染载体，按 tier 0/1/2 提供 25% 透明度的三档虹彩立方体模型。
    public static final DeferredBlock<Block> RAINBOW_OVERLAY =
            BLOCKS.register("rainbow_overlay",
                    () -> new OGERainbowOverlayBlock(BlockBehaviour.Properties.of()
                            .mapColor(MapColor.COLOR_PURPLE)
                            .strength(0.0F)
                            .noCollission()
                            .noOcclusion()
                            .noLootTable()));

    // 方块物品（带“实用提示（浅蓝正体）+ 废话提示（浅灰斜体）”悬浮提示）
    public static final DeferredItem<BlockItem> WIND_ORIGOLD_ENERGIA_GENERATOR_ITEM =
            ITEMS.register("wind_origold_energia_generator",
                    () -> new OGEMachineBlockItem(WIND_ORIGOLD_ENERGIA_GENERATOR.get(), new Item.Properties(), 120, "wind"));
    public static final DeferredItem<BlockItem> WATER_ORIGOLD_ENERGIA_GENERATOR_ITEM =
            ITEMS.register("water_origold_energia_generator",
                    () -> new OGEMachineBlockItem(WATER_ORIGOLD_ENERGIA_GENERATOR.get(), new Item.Properties().rarity(Rarity.UNCOMMON), 240, "water"));
    public static final DeferredItem<BlockItem> FIRE_ORIGOLD_ENERGIA_GENERATOR_ITEM =
            ITEMS.register("fire_origold_energia_generator",
                    () -> new OGEMachineBlockItem(FIRE_ORIGOLD_ENERGIA_GENERATOR.get(), new Item.Properties().rarity(Rarity.RARE), 1200, "fire"));
    public static final DeferredItem<BlockItem> MAGIC_ORIGOLD_ENERGIA_GENERATOR_ITEM =
            ITEMS.register("magic_origold_energia_generator",
                    () -> new OGEMachineBlockItem(MAGIC_ORIGOLD_ENERGIA_GENERATOR.get(), new Item.Properties().rarity(Rarity.EPIC), 6000, "magic"));
    public static final DeferredItem<BlockItem> ORIGOLD_ENERGIA_CAPACITOR_ITEM =
            ITEMS.register("origold_energia_capacitor",
                    () -> new OGEMachineBlockItem(ORIGOLD_ENERGIA_CAPACITOR.get(), new Item.Properties().rarity(Rarity.UNCOMMON), 60000, "capacitor"));
    public static final DeferredItem<BlockItem> ORIGOLD_LOGGER_ITEM =
            ITEMS.register("origold_logger",
                    () -> new OGELoggerBlockItem(ORIGOLD_LOGGER.get(), new Item.Properties()));
    public static final DeferredItem<BlockItem> ORIGOLD_DRILLER_ITEM =
            ITEMS.register("origold_driller",
                    () -> new OGEDrillerBlockItem(ORIGOLD_DRILLER.get(), new Item.Properties()));
    public static final DeferredItem<BlockItem> GRAVEL_GENERATOR_ITEM =
            ITEMS.register("gravel_generator",
                    () -> new OGEResourceGeneratorBlockItem(GRAVEL_GENERATOR.get(), new Item.Properties().rarity(Rarity.RARE),
                            "tooltip.origolds_ia.useful.gravel", null));
    public static final DeferredItem<BlockItem> CLAY_GENERATOR_ITEM =
            ITEMS.register("clay_generator",
                    () -> new OGEResourceGeneratorBlockItem(CLAY_GENERATOR.get(), new Item.Properties().rarity(Rarity.RARE),
                            "tooltip.origolds_ia.useful.clay", null));
    public static final DeferredItem<BlockItem> OBSIDIAN_GENERATOR_ITEM =
            ITEMS.register("obsidian_generator",
                    () -> new OGEResourceGeneratorBlockItem(OBSIDIAN_GENERATOR.get(), new Item.Properties().rarity(Rarity.RARE),
                            "tooltip.origolds_ia.useful.obsidian", null));
    public static final DeferredItem<BlockItem> ORIGOLD_PULVERIZER_ITEM =
            ITEMS.register("origold_pulverizer",
                    () -> new OGEResourceGeneratorBlockItem(ORIGOLD_PULVERIZER.get(), new Item.Properties().rarity(Rarity.UNCOMMON),
                            "tooltip.origolds_ia.useful.pulverizer", "tooltip.origolds_ia.bs.pulverizer"));
    public static final DeferredItem<BlockItem> ORIGOLD_SIFTER_ITEM =
            ITEMS.register("origold_sifter",
                    () -> new OGEResourceGeneratorBlockItem(ORIGOLD_SIFTER.get(), new Item.Properties().rarity(Rarity.UNCOMMON),
                            "tooltip.origolds_ia.useful.sifter", "tooltip.origolds_ia.bs.sifter"));
    public static final DeferredItem<BlockItem> ORIGOLD_VOID_DRAWER_ITEM =
            ITEMS.register("origold_void_drawer",
                    () -> new OGEResourceGeneratorBlockItem(ORIGOLD_VOID_DRAWER.get(), new Item.Properties().rarity(Rarity.UNCOMMON),
                            "tooltip.origolds_ia.useful.drawer", "tooltip.origolds_ia.bs.drawer"));
    // 引力匣：提示写法为“实用 + 废话”。
    public static final DeferredItem<BlockItem> ORIGOLD_GRAVITY_CASSETTE_ITEM =
            ITEMS.register("origold_gravity_cassette",
                    () -> new OGEResourceGeneratorBlockItem(ORIGOLD_GRAVITY_CASSETTE.get(), new Item.Properties().rarity(Rarity.UNCOMMON),
                            "tooltip.origolds_ia.useful.gravity_cassette", "tooltip.origolds_ia.bs.gravity_cassette"));
    public static final DeferredItem<BlockItem> ORIGOLD_VOIDING_STATION_ITEM =
            ITEMS.register("origold_voiding_station",
                    () -> new OGEResourceGeneratorBlockItem(ORIGOLD_VOIDING_STATION.get(), new Item.Properties().rarity(Rarity.UNCOMMON),
                            "tooltip.origolds_ia.useful.voiding_station",
                            "tooltip.origolds_ia.ctrl.voiding_station",
                            "tooltip.origolds_ia.bs.voiding_station"));
    // 鼓风机：提示写法为“实用 + 废话”。
    public static final DeferredItem<BlockItem> ORIGOLD_BLOWER_ITEM =
            ITEMS.register("origold_blower",
                    () -> new OGEResourceGeneratorBlockItem(ORIGOLD_BLOWER.get(), new Item.Properties(),
                            "tooltip.origolds_ia.useful.blower", "tooltip.origolds_ia.bs.blower"));
    // 自动附魔机：提示写法为“实用 + 按住Ctrl详情 + 废话”。
    public static final DeferredItem<BlockItem> ORIGOLD_MECH_ENCHANTER_ITEM =
            ITEMS.register("origold_mech_enchanter",
                    () -> new OGEResourceGeneratorBlockItem(ORIGOLD_MECH_ENCHANTER.get(), new Item.Properties().rarity(Rarity.EPIC),
                            "tooltip.origolds_ia.useful.mech_enchanter",
                            "tooltip.origolds_ia.ctrl.mech_enchanter",
                            "tooltip.origolds_ia.bs.mech_enchanter"));
    // 坷垃机：提示写法为“实用 + 废话”。
    public static final DeferredItem<BlockItem> ORIGOLD_KELA_MACHINE_ITEM =
            ITEMS.register("origold_kela_machine",
                    () -> new OGEResourceGeneratorBlockItem(ORIGOLD_KELA_MACHINE.get(), new Item.Properties(),
                            "tooltip.origolds_ia.useful.kela", "tooltip.origolds_ia.bs.kela"));
    // 金源放置器：实用提示 + 废话提示。
    public static final DeferredItem<BlockItem> ORIGOLD_PLACER_ITEM =
            ITEMS.register("origold_placer",
                    () -> new OGEResourceGeneratorBlockItem(ORIGOLD_PLACER.get(), new Item.Properties(),
                            "tooltip.origolds_ia.useful.placer", "tooltip.origolds_ia.bs.placer"));
    // 金源红石脉冲钟：提示写法为“实用 + 废话”。
    public static final DeferredItem<BlockItem> ORIGOLD_REDSTONE_PULSE_CLOCK_ITEM =
            ITEMS.register("origold_redstone_pulse_clock",
                    () -> new OGEResourceGeneratorBlockItem(ORIGOLD_REDSTONE_PULSE_CLOCK.get(), new Item.Properties(),
                            "tooltip.origolds_ia.useful.redstone_pulse_clock",
                            "tooltip.origolds_ia.bs.redstone_pulse_clock"));
    // 金源超级漏斗：提示写法为“实用 + 废话”。
    public static final DeferredItem<BlockItem> ORIGOLD_HYPERHOPPER_ITEM =
            ITEMS.register("origold_hyperhopper",
                    () -> new OGEResourceGeneratorBlockItem(ORIGOLD_HYPERHOPPER.get(), new Item.Properties(),
                            "tooltip.origolds_ia.useful.hyperhopper",
                            "tooltip.origolds_ia.bs.hyperhopper"));
    // 金源超级熔炉：提示写法为“实用 + 废话”。
    public static final DeferredItem<BlockItem> ORIGOLD_HYPERFURNACE_ITEM =
            ITEMS.register("origold_hyperfurnace",
                    () -> new OGEResourceGeneratorBlockItem(ORIGOLD_HYPERFURNACE.get(), new Item.Properties(),
                            "tooltip.origolds_ia.useful.hyperfurnace",
                            "tooltip.origolds_ia.bs.hyperfurnace"));
    // 金源炼金站：提示写法为“实用 + 废话”。
    public static final DeferredItem<BlockItem> ORIGOLD_CHRYSOPOEIA_STATION_ITEM =
            ITEMS.register("origold_chrysopoeia_station",
                    () -> new OGEResourceGeneratorBlockItem(ORIGOLD_CHRYSOPOEIA_STATION.get(),
                            new Item.Properties().rarity(Rarity.COMMON),
                            "tooltip.origolds_ia.useful.chrysopoeia_station",
                            "tooltip.origolds_ia.bs.chrysopoeia_station"));
    // 金源自动合成机：提示写法为“实用 + 废话”。
    public static final DeferredItem<BlockItem> ORIGOLD_MECH_CRAFTER_ITEM =
            ITEMS.register("origold_mech_crafter",
                    () -> new OGEResourceGeneratorBlockItem(ORIGOLD_MECH_CRAFTER.get(),
                            new Item.Properties().rarity(Rarity.COMMON),
                            "tooltip.origolds_ia.useful.mech_crafter",
                            "tooltip.origolds_ia.bs.mech_crafter"));
    // 金源圆锯刃槽：提示写法为“实用 + 按住Ctrl详情 + 废话”。刀刃方块没有物品形态。
    public static final DeferredItem<BlockItem> ORIGOLD_SAWBLADE_MOUNT_ITEM =
            ITEMS.register("origold_sawblade_mount",
                    () -> new OGEResourceGeneratorBlockItem(ORIGOLD_SAWBLADE_MOUNT.get(),
                            new Item.Properties().rarity(Rarity.COMMON),
                            "tooltip.origolds_ia.useful.sawblade_mount",
                            "tooltip.origolds_ia.ctrl.sawblade_mount",
                            "tooltip.origolds_ia.bs.sawblade_mount"));

    // 闪亮系列：筛洗沙砾/黏土、粉碎黑曜石的产物，也可再进筛洗机换随机矿物。
    // 稀有度：碎块=稀有（附魔闪光）、碎片=罕见、碎粒=普通。提示写法为“实用 + 废话”。
    public static final DeferredItem<Item> SHINY_CHUNK =
            ITEMS.register("shiny_chunk",
                    () -> new OGEFlavoredItem(new Item.Properties().rarity(Rarity.RARE),
                            "tooltip.origolds_ia.useful.shiny_chunk", "tooltip.origolds_ia.bs.shiny_chunk"));
    public static final DeferredItem<Item> SHINY_SHARD =
            ITEMS.register("shiny_shard",
                    () -> new OGEFlavoredItem(new Item.Properties().rarity(Rarity.UNCOMMON),
                            "tooltip.origolds_ia.useful.shiny_shard", "tooltip.origolds_ia.bs.shiny_shard"));
    public static final DeferredItem<Item> SHINY_CRUMB =
            ITEMS.register("shiny_crumb",
                    () -> new OGEFlavoredItem(new Item.Properties().rarity(Rarity.COMMON),
                            "tooltip.origolds_ia.useful.shiny_crumb", "tooltip.origolds_ia.bs.shiny_crumb"));

    // 资源发生器核心：各类资源生成器的共同合成材料（罕见，堆叠上限沿用默认 64）。
    public static final DeferredItem<Item> RESOURCE_GENERATOR_CORE =
            ITEMS.register("resource_generator_core",
                    () -> new OGEFlavoredItem(new Item.Properties().rarity(Rarity.UNCOMMON),
                            "tooltip.origolds_ia.useful.core", "tooltip.origolds_ia.bs.core"));

    // 挂机志愿者：放下后可用 Shift+左键切换玩家模式/仇恨目标模式，Shift+右键收回。
    public static final DeferredItem<Item> VOLUNTEER_WATCHER =
            ITEMS.register("volunteer_watcher",
                    () -> new OGEVolunteerWatcherItem(new Item.Properties()
                            .stacksTo(16)
                            .rarity(Rarity.UNCOMMON)));

    // 虹彩印记：贴在资源发生器 / 火力发电机 / 魔力发电机上按 5x / 20x / 100x 超频。
    // 耐久上限 64，合成出来只有 1 点；正常玩法扣不掉耐久，只能靠工作台配方增减。
    public static final DeferredItem<Item> RAINBOW_MARK =
            ITEMS.register("rainbow_mark",
                    () -> new OGERainbowMarkItem(new Item.Properties()
                            .stacksTo(1)
                            .durability(OGERainbowMarkItem.MAX_DURABILITY)
                            .rarity(Rarity.EPIC)));

    // 圆锯刃片：耐久机制同虹彩印记（上限 64、合成出来只剩 1 点），但在工作台里用铁块补耐久
    // （1 块铁 = 1 点）。面板是 6 点攻击伤害 / 1.2 攻速，打人挖方块都不掉耐久，也不能附魔；
    // 唯一扣耐久的用途是往金源圆锯刃槽上装刀刃。
    public static final DeferredItem<Item> CIRCULAR_SAWBLADE =
            ITEMS.register("circular_sawblade",
                    () -> new OGECircularSawbladeItem(new Item.Properties()
                            .stacksTo(1)
                            .durability(OGECircularSawbladeItem.MAX_DURABILITY)
                            .attributes(OGECircularSawbladeItem.createAttributes())
                            .rarity(Rarity.UNCOMMON)));

    /** 父模组（origolds）“金源工艺”创造页签。 */
    private static final ResourceKey<CreativeModeTab> ORIGOLDS_TAB =
            ResourceKey.create(Registries.CREATIVE_MODE_TAB,
                    ResourceLocation.fromNamespaceAndPath("origolds", "origolds"));

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
