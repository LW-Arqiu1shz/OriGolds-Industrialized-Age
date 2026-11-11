package cn.origolds.ia;

import java.util.List;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 本模组的数据组件注册表。
 */
public final class ModDataComponents {

    private static final DeferredRegister.DataComponents COMPONENTS =
            DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, OriGoldsIndustrializedAge.MOD_ID);

    /**
     * 机器物品上保留的 OGE 电量（{@code origolds_ia:oge_stored}）。
     * 挖掘带电机器时由战利品函数写入；放置时注入回新方块实体。
     */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Integer>> OGE_STORED =
            COMPONENTS.registerComponentType("oge_stored",
                    builder -> builder
                            .persistent(com.mojang.serialization.Codec.INT)
                            .networkSynchronized(net.minecraft.network.codec.ByteBufCodecs.VAR_INT));

    /**
     * 精矿标记（{@code origolds_ia:oge_concentrated}），值为原料粗矿物品。
     *
     * <p>金源粉碎机把粗矿（如粗铁）粉碎成"精矿"时并不新建物品，而是在同一粗矿物品上
     * 写入本组件并用 {@code item_name} 组件改出"精炼粗铁 / Concentrated Raw Iron"的名字。
     * 精矿与粗矿熔炉配方完全相同（熔炼配方只认物品本身），但不可再次粉碎；
     * 原版三个粗矿压缩配方也已由本模组用 {@code neoforge:difference} 配方覆写，
     * 把带本组件的堆排除在合成之外。</p>
     */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Holder<Item>>> OGE_CONCENTRATED =
            COMPONENTS.registerComponentType("oge_concentrated",
                    builder -> builder
                            .persistent(BuiltInRegistries.ITEM.holderByNameCodec())
                            .networkSynchronized(net.minecraft.network.codec.ByteBufCodecs.holderRegistry(Registries.ITEM)));

    /**
     * 金源虚空抽屉内容（{@code origolds_ia:void_drawer_contents}）。
     *
     * <p>记录"存放种类（数量恒写 1）+ 物品总数"两项。挖掘掉落、创造取色时写入，
     * 由方块实体的隐式组件机制在放置时还原。原版 {@code minecraft:container}
     * 组件编解码上限只有 256 项，无法容纳 729 组的抽屉。</p>
     */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<OGEVoidDrawerContents>> VOID_DRAWER_CONTENTS =
            COMPONENTS.registerComponentType("void_drawer_contents",
                    builder -> builder
                            .persistent(OGEVoidDrawerContents.CODEC)
                            .networkSynchronized(OGEVoidDrawerContents.STREAM_CODEC));

    /**
     * 机器已被彩虹标记点亮的档位（{@code origolds_ia:rainbow_tier}）。
     *
     * <p>0/1/2 对应 5x/20x/100x；不写该组件表示未上彩虹（倍率 1）。</p>
     */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Integer>> RAINBOW_TIER =
            COMPONENTS.registerComponentType("rainbow_tier",
                    builder -> builder
                            .persistent(com.mojang.serialization.Codec.INT)
                            .networkSynchronized(net.minecraft.network.codec.ByteBufCodecs.VAR_INT));

    /** 志愿者观察员物品的工作模式（{@code origolds_ia:volunteer_mode}）。 */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Integer>> VOLUNTEER_MODE =
            COMPONENTS.registerComponentType("volunteer_mode",
                    builder -> builder
                            .persistent(com.mojang.serialization.Codec.INT)
                            .networkSynchronized(net.minecraft.network.codec.ByteBufCodecs.VAR_INT));

    /** 志愿者观察员物品里保存的绑定清单（{@code origolds_ia:volunteer_binds}）。 */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<List<OGEVolunteerBindEntry>>> VOLUNTEER_BINDS =
            COMPONENTS.registerComponentType("volunteer_binds",
                    builder -> builder
                            .persistent(OGEVolunteerBindEntry.CODEC.listOf())
                            .networkSynchronized(OGEVolunteerBindEntry.STREAM_CODEC.apply(
                                    net.minecraft.network.codec.ByteBufCodecs.list())));

    private ModDataComponents() {
    }

    public static void register(IEventBus modBus) {
        COMPONENTS.register(modBus);
    }
}
