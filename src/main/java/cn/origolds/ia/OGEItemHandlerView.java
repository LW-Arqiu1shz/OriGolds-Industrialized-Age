package cn.origolds.ia;

import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.item.ItemUtil;
import net.neoforged.neoforge.transfer.transaction.Transaction;

/**
 * 把 26.x 的 {@link ResourceHandler}&lt;{@link ItemResource}&gt; 视图包回传统
 * {@link IItemHandler}，供本模组内部按老接口读写物品。
 *
 * <p>26.1.2 起 NeoForge 的物品能力 {@code Capabilities.Item.BLOCK} 已改为返回
 * {@code ResourceHandler<ItemResource>}；本模组的物流管道、超级漏斗等大量逻辑
 * 仍按 {@code IItemHandler} 编写，这里做一层薄适配即可。</p>
 */
public final class OGEItemHandlerView implements IItemHandler {

    private final ResourceHandler<ItemResource> handler;

    private OGEItemHandlerView(ResourceHandler<ItemResource> handler) {
        this.handler = handler;
    }

    /** 把资源处理器包成传统物品处理器；入参为 null 时返回 null。 */
    public static IItemHandler of(ResourceHandler<ItemResource> handler) {
        return handler == null ? null : new OGEItemHandlerView(handler);
    }

    @Override
    public int getSlots() {
        return handler.size();
    }

    @Override
    public ItemStack getStackInSlot(int slot) {
        return ItemUtil.getStack(handler, slot);
    }

    @Override
    public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
        return ItemUtil.insertItemReturnRemaining(handler, slot, stack, simulate, null);
    }

    @Override
    public ItemStack extractItem(int slot, int amount, boolean simulate) {
        if (amount <= 0) {
            return ItemStack.EMPTY;
        }
        ItemResource resource = handler.getResource(slot);
        if (resource.isEmpty()) {
            return ItemStack.EMPTY;
        }
        int take = Math.min(amount, resource.getMaxStackSize());
        try (Transaction tx = Transaction.openRoot()) {
            int extracted = handler.extract(slot, resource, take, tx);
            if (!simulate) {
                tx.commit();
            }
            return resource.toStack(extracted);
        }
    }

    @Override
    public int getSlotLimit(int slot) {
        return handler.getCapacityAsInt(slot, ItemResource.EMPTY);
    }

    @Override
    public boolean isItemValid(int slot, ItemStack stack) {
        return handler.isValid(slot, ItemResource.of(stack));
    }
}
