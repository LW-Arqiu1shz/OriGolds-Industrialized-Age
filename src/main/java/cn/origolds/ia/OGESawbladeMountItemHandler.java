package cn.origolds.ia;

import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * 金源圆锯刃槽对外的 {@link IItemHandler} 视图。
 *
 * <p>普通容器的能力视图两头都能读写，这里按需求把两个方向不对称地收窄：</p>
 * <ul>
 *   <li><b>写入只进武器槽</b>（第 {@value OGESawbladeMountBlockEntity#SLOT_WEAPON} 格），
 *       而且必须过 {@link OGEMeleeWeapons#isMeleeWeapon} 判定；废物槽不收外部投递，
 *       免得管道/漏斗把垃圾塞进本来给武器留的下方格。这样无线物流管道、原版漏斗、
 *       金源超级漏斗“往里送”时都只会尝试喂武器槽，喂不进就整件不动。</li>
 *   <li><b>读取只出废物槽</b>（第 {@value OGESawbladeMountBlockEntity#SLOT_WASTE} 格），
 *       武器槽的 {@code extractItem} 恒返回空——正在用的武器不会被抽走。</li>
 * </ul>
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
        return mount.getItem(slot);
    }

    /** 只允许往武器槽塞，且塞不进的部分原样退回。 */
    @Override
    public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
        if (stack.isEmpty() || slot != OGESawbladeMountBlockEntity.SLOT_WEAPON) {
            return stack;
        }
        if (!mount.canPlaceItem(slot, stack)) {
            return stack;
        }
        ItemStack current = mount.getItem(slot);
        int limit = getSlotLimit(slot);
        if (current.isEmpty()) {
            int moved = Math.min(stack.getCount(), limit);
            if (!simulate) {
                ItemStack placed = stack.copyWithCount(moved);
                mount.setItem(slot, placed);
                mount.setChanged();
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
            mount.setChanged();
        }
        return stack.copyWithCount(stack.getCount() - moved);
    }

    /** 只能从废物槽抽走；武器槽一律抽不动。 */
    @Override
    public ItemStack extractItem(int slot, int amount, boolean simulate) {
        if (amount <= 0 || slot != OGESawbladeMountBlockEntity.SLOT_WASTE) {
            return ItemStack.EMPTY;
        }
        ItemStack current = mount.getItem(slot);
        if (current.isEmpty()) {
            return ItemStack.EMPTY;
        }
        int taken = Math.min(amount, current.getCount());
        ItemStack result = current.copyWithCount(taken);
        if (!simulate) {
            current.shrink(taken);
            if (current.isEmpty()) {
                mount.setItem(slot, ItemStack.EMPTY);
            }
            mount.setChanged();
        }
        return result;
    }

    @Override
    public int getSlotLimit(int slot) {
        ItemStack current = mount.getItem(slot);
        return current.isEmpty() ? 1 : current.getMaxStackSize();
    }

    /** 外部投递只认武器槽：废物槽是系统输出专用，漏斗 / 管道都别想往里塞。 */
    @Override
    public boolean isItemValid(int slot, ItemStack stack) {
        return slot == OGESawbladeMountBlockEntity.SLOT_WEAPON && mount.canPlaceItem(slot, stack);
    }
}
