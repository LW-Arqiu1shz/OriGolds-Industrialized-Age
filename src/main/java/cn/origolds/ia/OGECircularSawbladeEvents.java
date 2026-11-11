package cn.origolds.ia;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * 圆锯刃片充能配方的收尾：按“补满耐久真正需要多少铁块”来扣材料。
 *
 * <p>原版每个格子只会扣 1 个，铁块堆数不够扣时先把堆叠数压小，够扣时多扣的那点由
 * {@link CircularSawbladeRechargeRecipe#getRemainingItems} 补回。逻辑与
 * {@link OGERainbowMarkEvents#onCrafted} 相同，只是判断换成圆锯刃片。</p>
 */
@EventBusSubscriber(modid = OriGoldsIndustrializedAge.MOD_ID, bus = EventBusSubscriber.Bus.GAME)
public final class OGECircularSawbladeEvents {

    private OGECircularSawbladeEvents() {
    }

    @SubscribeEvent
    public static void onCrafted(PlayerEvent.ItemCraftedEvent event) {
        // 客户端也会走一遍结果槽逻辑，扣料只在服务端做。
        if (event.getEntity().level().isClientSide()) {
            return;
        }
        if (!(event.getCrafting().getItem() instanceof OGECircularSawbladeItem)) {
            return;
        }
        Container grid = event.getInventory();
        if (grid == null) {
            return;
        }
        List<ItemStack> items = new ArrayList<>(grid.getContainerSize());
        for (int i = 0; i < grid.getContainerSize(); i++) {
            items.add(grid.getItem(i));
        }
        if (!CircularSawbladeRechargeRecipe.isRechargeGrid(items)) {
            return;
        }
        int consume = CircularSawbladeRechargeRecipe.materialToConsume(items);
        int stacks = CircularSawbladeRechargeRecipe.materialStacks(items);
        // 铁块堆够多：多扣的部分由配方退料补齐，这里不动盘面。
        if (stacks >= consume) {
            return;
        }
        // 铁块堆不够：每堆保留至少 1 个（盘面仍匹配充能配方），原版每堆扣 1 后正好剩 keep 个。
        int keep = CircularSawbladeRechargeRecipe.materialCount(items) - consume;
        int extra = keep;
        for (int i = 0; i < grid.getContainerSize(); i++) {
            ItemStack stack = grid.getItem(i);
            if (stack.isEmpty() || !CircularSawbladeRechargeRecipe.isMaterial(stack)) {
                continue;
            }
            int count = 1 + Math.min(extra, 63);
            extra -= count - 1;
            stack.setCount(count);
        }
        grid.setChanged();
    }
}
