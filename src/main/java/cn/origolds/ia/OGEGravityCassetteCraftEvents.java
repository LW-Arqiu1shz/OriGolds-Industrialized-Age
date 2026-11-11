package cn.origolds.ia;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * 虚空抽屉与金源引力匣互相合成时，把容器里的东西吐在玩家脚下。
 *
 * <p>两者都能装东西，直接合成会让内容物随被消耗的那件物品一起消失。这里在
 * {@link PlayerEvent.ItemCraftedEvent}（原版在消耗合成材料之前触发）里检查合成
 * 矩阵：</p>
 * <ul>
 *   <li>合成出引力匣时，矩阵里的虚空抽屉若有 {@code origolds_ia:void_drawer_contents}
 *       组件，就把其中的“种类 + 总数”全部丢在玩家脚下，并清掉组件防止二次触发；</li>
 *   <li>合成回虚空抽屉时，矩阵里的引力匣若有原版 {@code minecraft:container}
 *       组件，同样把里面的物品逐堆丢在玩家脚下。</li>
 * </ul>
 * <p>只处理这两条互换配方，其它用到这两种物品的合成不受影响。</p>
 */
@EventBusSubscriber(modid = OriGoldsIndustrializedAge.MOD_ID, bus = EventBusSubscriber.Bus.GAME)
public final class OGEGravityCassetteCraftEvents {

    private OGEGravityCassetteCraftEvents() {
    }

    @SubscribeEvent
    public static void onItemCrafted(PlayerEvent.ItemCraftedEvent event) {
        Player player = event.getEntity();
        // 客户端也会走一遍结果槽逻辑，掉落只在服务端发生，避免出现幽灵掉落物。
        if (player.level().isClientSide()) {
            return;
        }
        ItemStack crafted = event.getCrafting();
        boolean madeCassette = crafted.is(ModBlocks.ORIGOLD_GRAVITY_CASSETTE_ITEM.get());
        boolean madeDrawer = crafted.is(ModBlocks.ORIGOLD_VOID_DRAWER_ITEM.get());
        if (!madeCassette && !madeDrawer) {
            return;
        }
        Container matrix = event.getInventory();
        for (int i = 0; i < matrix.getContainerSize(); i++) {
            ItemStack stack = matrix.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            if (madeCassette && stack.is(ModBlocks.ORIGOLD_VOID_DRAWER_ITEM.get())) {
                dropDrawerContents(player, stack);
            } else if (madeDrawer && stack.is(ModBlocks.ORIGOLD_GRAVITY_CASSETTE_ITEM.get())) {
                dropCassetteContents(player, stack);
            }
        }
    }

    /** 虚空抽屉：单类型“种类 + 总数”，按单组上限拆成多堆丢出。 */
    private static void dropDrawerContents(Player player, ItemStack drawerStack) {
        OGEVoidDrawerContents contents = drawerStack.get(ModDataComponents.VOID_DRAWER_CONTENTS.get());
        if (contents == null || contents.item().isEmpty() || contents.count() <= 0) {
            return;
        }
        int max = Math.max(1, contents.item().getMaxStackSize());
        // 组件里的 count 是持久化 + 联网同步的整数，可能被损坏的存档、命令或别的模组
        // 写成天文数字。原样丢会变成一个“数据驱动的死循环”：每轮只丢一组，
        // 几十亿次循环 + 几十亿个掉落物实体，直接把游戏卡死并撑爆内存。
        // 抽屉真实容量就是 729 × 单组上限，超过的部分本来也存不进去，直接截断；
        // 再加一道迭代护栏，保证最多只循环“抽屉格数 + 1”次。
        int capacity = OGEVoidDrawerBlockEntity.SLOT_COUNT * max;
        int remaining = Math.min(contents.count(), capacity);
        for (int guard = 0; remaining > 0 && guard <= OGEVoidDrawerBlockEntity.SLOT_COUNT; guard++) {
            int move = Math.min(max, remaining);
            player.drop(contents.item().copyWithCount(move), false);
            remaining -= move;
        }
        // 清掉组件：同一 tick 内结果槽逻辑可能重复触发，防止内容物被丢两次。
        drawerStack.remove(ModDataComponents.VOID_DRAWER_CONTENTS.get());
    }

    /** 引力匣：原版 container 组件，里面的每一堆原样丢出。 */
    private static void dropCassetteContents(Player player, ItemStack cassetteStack) {
        ItemContainerContents contents = cassetteStack.get(DataComponents.CONTAINER);
        if (contents == null) {
            return;
        }
        for (ItemStack inside : contents.nonEmptyItems()) {
            if (!inside.isEmpty()) {
                player.drop(inside.copy(), false);
            }
        }
        cassetteStack.remove(DataComponents.CONTAINER);
    }
}
