package cn.origolds.ia;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent.ItemCraftedEvent;

/**
 * 圆锯片充能配方的"材料余量回吐"兜底事件。
 *
 * <p>充能配方按每点耐久吃 1 块铁块，但合成格里的铁块是不可拆单的整数堆：
 * 当所需块数 {@code materialToConsume} 少于在场堆数 {@code materialStacks} 时，
 * 原版会把每一整堆都当作"有剩余"返还到结果槽，可能反而变多。这里在合成完成后
 * 直接在合成格上把每堆收缩到总量恰好等于"应保留块数 + 在场堆数"，与配方
 * {@code getRemainingItems} 的返料口径一致。</p>
 */
@EventBusSubscriber(modid = OriGoldsIndustrializedAge.MOD_ID)
public final class OGECircularSawbladeEvents {

    private OGECircularSawbladeEvents() {
    }

    @SubscribeEvent
    public static void onCrafted(ItemCraftedEvent event) {
        if (!event.getEntity().level().isClientSide()) {
            if (event.getCrafting().getItem() instanceof OGECircularSawbladeItem) {
                Container grid = event.getInventory();
                if (grid != null) {
                    List<ItemStack> items = new ArrayList<>(grid.getContainerSize());
                    for (int i = 0; i < grid.getContainerSize(); i++) {
                        items.add(grid.getItem(i));
                    }

                    if (CircularSawbladeRechargeRecipe.isRechargeGrid(items)) {
                        int consume = CircularSawbladeRechargeRecipe.materialToConsume(items);
                        int stacks = CircularSawbladeRechargeRecipe.materialStacks(items);
                        if (stacks < consume) {
                            int keep = CircularSawbladeRechargeRecipe.materialCount(items) - consume;
                            int extra = keep;
                            for (int i = 0; i < grid.getContainerSize(); i++) {
                                ItemStack stack = grid.getItem(i);
                                if (!stack.isEmpty() && CircularSawbladeRechargeRecipe.isMaterial(stack)) {
                                    int count = 1 + Math.min(extra, 63);
                                    extra -= count - 1;
                                    stack.setCount(count);
                                }
                            }
                            grid.setChanged();
                        }
                    }
                }
            }
        }
    }
}
