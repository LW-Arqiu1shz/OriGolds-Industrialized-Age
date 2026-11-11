package cn.origolds.ia;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 金源能量机器（火驱/魔驱）原料 UI 的菜单类型注册表。
 */
public final class ModMenus {

    private static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(Registries.MENU, OriGoldsIndustrializedAge.MOD_ID);

    public static final DeferredHolder<MenuType<?>, MenuType<OGEGeneratorMenu>> GENERATOR =
            MENUS.register("oge_generator", () -> IMenuTypeExtension.create(OGEGeneratorMenu::new));

    /** 金源虚空抽屉：单类型 729 组、逐格解锁的容器 UI。 */
    public static final DeferredHolder<MenuType<?>, MenuType<OGEVoidDrawerMenu>> VOID_DRAWER =
            MENUS.register("oge_void_drawer", () -> IMenuTypeExtension.create(OGEVoidDrawerMenu::new));

    /** 金源引力匣：3 行 27 格的普通箱子式容器 UI。 */
    public static final DeferredHolder<MenuType<?>, MenuType<OGEGravityCassetteMenu>> GRAVITY_CASSETTE =
            MENUS.register("oge_gravity_cassette", () -> IMenuTypeExtension.create(OGEGravityCassetteMenu::new));

    /** 金源自动附魔机：两输入一输出的铁砧式合并界面。 */
    public static final DeferredHolder<MenuType<?>, MenuType<OGEMechEnchanterMenu>> MECH_ENCHANTER =
            MENUS.register("oge_mech_enchanter", () -> IMenuTypeExtension.create(OGEMechEnchanterMenu::new));

    /** 金源红石脉冲钟：三个数字输入框 + “▷”按钮的计时界面。 */
    public static final DeferredHolder<MenuType<?>, MenuType<OGEClockMenu>> REDSTONE_PULSE_CLOCK =
            MENUS.register("oge_redstone_pulse_clock", () -> IMenuTypeExtension.create(OGEClockMenu::new));

    /** 金源超级漏斗：计时控制区 + 单次漏取上限 + 27 格容量的界面。 */
    public static final DeferredHolder<MenuType<?>, MenuType<OGEHyperhopperMenu>> HYPERHOPPER =
            MENUS.register("oge_hyperhopper", () -> IMenuTypeExtension.create(OGEHyperhopperMenu::new));

    /** 金源炼成台：杂质进、金源出的三槽位转化界面。 */
    public static final DeferredHolder<MenuType<?>, MenuType<OGEChrysopoeiaStationMenu>> CHRYSOPOEIA_STATION =
            MENUS.register("oge_chrysopoeia_station", () -> IMenuTypeExtension.create(OGEChrysopoeiaStationMenu::new));

    /** 金源超级熔炉：熔炼进度 + 档位控制的界面。 */
    public static final DeferredHolder<MenuType<?>, MenuType<OGEHyperfurnaceMenu>> HYPERFURNACE =
            MENUS.register("oge_hyperfurnace", () -> IMenuTypeExtension.create(OGEHyperfurnaceMenu::new));

    /** 金源机械合成台：3×3 合成格 + 结果/锁定槽的自动合成界面。 */
    public static final DeferredHolder<MenuType<?>, MenuType<OGEMechCrafterMenu>> MECH_CRAFTER =
            MENUS.register("oge_mech_crafter", () -> IMenuTypeExtension.create(OGEMechCrafterMenu::new));

    /** 金源锯片台：锯片槽 + 经验条 + 产物输出口的界面。 */
    public static final DeferredHolder<MenuType<?>, MenuType<OGESawbladeMountMenu>> SAWBLADE_MOUNT =
            MENUS.register("oge_sawblade_mount", () -> IMenuTypeExtension.create(OGESawbladeMountMenu::new));

    private ModMenus() {
    }

    public static void register(IEventBus modBus) {
        MENUS.register(modBus);
    }
}
