package cn.origolds.ia;

import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * 锯片台的物品处理器（内部/兼容入口）：只许从外面插武器/锯片槽（0），
 * 只许从外面抽废件槽（1）；两侧互不打扰。
 *
 * <p>写入一律走 {@link OGESawbladeMountBlockEntity#setItem(int, ItemStack)}，
 * 以便复用"换武器清计数、破损自动进废件槽"的判定。</p>
 */
public final class OGESawbladeMountItemHandler implements IItemHandler {

    private final OGESawbladeMountBlockEntity mount;

    public OGESawbladeMountItemHandler(OGESawbladeMountBlockEntity mount) {
        this.mount = mount;
    }

    @Override
    public int getSlots() {
        return OGESawbladeMountBlockEntity.SLOT_COUNT;
    }

    @Override
    public ItemStack getStackInSlot(int slot) {
        return this.mount.getItem(slot);
    }

    /** 只收武器/锯片槽（0），并且要过 {@code canPlaceItem} 的近战武器判定。 */
    @Override
    public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
        if (stack.isEmpty() || slot != OGESawbladeMountBlockEntity.SLOT_WEAPON) {
            return stack;
        }
        if (!this.mount.canPlaceItem(slot, stack)) {
            return stack;
        }
        ItemStack current = this.mount.getItem(slot);
        int limit = this.getSlotLimit(slot);
        if (current.isEmpty()) {
            int moved = Math.min(stack.getCount(), limit);
            if (!simulate) {
                ItemStack placed = stack.copyWithCount(moved);
                this.mount.setItem(slot, placed);
                this.mount.setChanged();
            }
            return stack.copyWithCount(stack.getCount() - moved);
        }
        if (!ItemStack.isSameItemSameComponents(current, stack)) {
            return stack;
        }
        int room = limit - current.getCount();
        if (room <= 0) {
            return stack;
        }
        int moved = Math.min(stack.getCount(), room);
        if (!simulate) {
            current.grow(moved);
            this.mount.setChanged();
        }
        return stack.copyWithCount(stack.getCount() - moved);
    }

    /** 只许抽废件槽（1）。 */
    @Override
    public ItemStack extractItem(int slot, int amount, boolean simulate) {
        if (amount <= 0 || slot != OGESawbladeMountBlockEntity.SLOT_WASTE) {
            return ItemStack.EMPTY;
        }
        ItemStack current = this.mount.getItem(slot);
        if (current.isEmpty()) {
            return ItemStack.EMPTY;
        }
        int taken = Math.min(amount, current.getCount());
        ItemStack result = current.copyWithCount(taken);
        if (!simulate) {
            current.shrink(taken);
            if (current.isEmpty()) {
                this.mount.setItem(slot, ItemStack.EMPTY);
            }
            this.mount.setChanged();
        }
        return result;
    }

    @Override
    public int getSlotLimit(int slot) {
        ItemStack current = this.mount.getItem(slot);
        return current.isEmpty() ? 1 : current.getMaxStackSize();
    }

    @Override
    public boolean isItemValid(int slot, ItemStack stack) {
        return slot == OGESawbladeMountBlockEntity.SLOT_WEAPON && this.mount.canPlaceItem(slot, stack);
    }
}
