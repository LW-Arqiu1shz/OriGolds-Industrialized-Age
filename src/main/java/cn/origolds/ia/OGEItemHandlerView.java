package cn.origolds.ia;

import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.item.ItemUtil;
import net.neoforged.neoforge.transfer.transaction.Transaction;

/**
 * 把 26.x 的 {@link ResourceHandler}&lt;{@link ItemResource}&gt; 包成本模组内部沿用的
 * "按槽位 + ItemStack" 物品视图（方法名沿用早期的 {@code IItemHandler}）。
 *
 * <p>NeoForge 从 26.1.2 起把物品能力 {@code Capabilities.Item.BLOCK} 的返回类型换成
 * {@code ResourceHandler<ItemResource>}，到 26.3 原来的 {@code IItemHandler} 接口连同
 * 整个 {@code net.neoforged.neoforge.items} 包都被删除了。本模组的物流管道、超级漏斗等
 * 大量逻辑仍按槽位直觉编写，所以保留这一层薄适配，而不是把每个调用点都改成事务式写法。</p>
 *
 * <p>需要把处理器交给原版或别的模组（注册能力、给菜单用）时，一律交出 {@link #handler()}
 * 里的原生 {@link ResourceHandler}，不要把本视图交出去。</p>
 */
public final class OGEItemHandlerView {

    private final ResourceHandler<ItemResource> handler;

    private OGEItemHandlerView(ResourceHandler<ItemResource> handler) {
        this.handler = handler;
    }

    /** 把资源处理器包成本模组的槽位视图；入参为 null 时返回 null。 */
    public static OGEItemHandlerView of(ResourceHandler<ItemResource> handler) {
        return handler == null ? null : new OGEItemHandlerView(handler);
    }

    /** 被包装的原生资源处理器，对外暴露能力时用它。 */
    public ResourceHandler<ItemResource> handler() {
        return this.handler;
    }

    public int getSlots() {
        return handler.size();
    }

    public ItemStack getStackInSlot(int slot) {
        return ItemUtil.getStack(handler, slot);
    }

    public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
        return ItemUtil.insertItemReturnRemaining(handler, slot, stack, simulate, null);
    }

    public ItemStack extractItem(int slot, int amount, boolean simulate) {
        if (amount <= 0) {
            return ItemStack.EMPTY;
        }
        ItemResource resource = handler.getResource(slot);
        if (resource.isEmpty()) {
            return ItemStack.EMPTY;
        }
        int take = Math.min(amount, resource.getMaxStackSize());
        // 新 API 没有 simulate 布尔开关：模拟就是开一个事务、不提交即自动回滚。
        try (Transaction tx = Transaction.openRoot()) {
            int extracted = handler.extract(slot, resource, take, tx);
            if (!simulate) {
                tx.commit();
            }
            return resource.toStack(extracted);
        }
    }

    public int getSlotLimit(int slot) {
        ItemResource stored = handler.getResource(slot);
        return handler.getCapacityAsInt(slot, stored.isEmpty() ? ItemResource.EMPTY : stored);
    }

    public boolean isItemValid(int slot, ItemStack stack) {
        return handler.isValid(slot, ItemResource.of(stack));
    }
}
