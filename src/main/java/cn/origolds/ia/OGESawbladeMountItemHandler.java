package cn.origolds.ia;

import java.util.Objects;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.transaction.SnapshotJournal;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;

/**
 * 锯片台的物品处理器（内部/兼容入口）：只许从外面插武器/锯片槽（0），
 * 只许从外面抽废件槽（1）；两侧互不打扰。
 *
 * <p>26.3 删掉了 {@code net.neoforged.neoforge.items} 整包，原来的 {@code IItemHandler}
 * 由 {@link ResourceHandler}&lt;{@link ItemResource}&gt; 取代。新接口的插入/抽取都带一个
 * 事务上下文："模拟"就是开事务不提交、由快照回滚，所以本类在真正动手前先
 * {@link #updateSnapshots} 记下两格的原始内容。</p>
 *
 * <p>写入一律走 {@link OGESawbladeMountBlockEntity#setItem(int, ItemStack)}，
 * 以便复用"换武器清计数、破损自动进废件槽"的判定；{@code setChanged()} 挪到
 * {@link #onRootCommit} 里，只在事务真正提交后标一次脏，模拟阶段不会污染方块。</p>
 */
public final class OGESawbladeMountItemHandler extends SnapshotJournal<ItemStack[]>
        implements ResourceHandler<ItemResource> {

    private final OGESawbladeMountBlockEntity mount;

    public OGESawbladeMountItemHandler(OGESawbladeMountBlockEntity mount) {
        this.mount = mount;
    }

    @Override
    public int size() {
        return OGESawbladeMountBlockEntity.SLOT_COUNT;
    }

    @Override
    public ItemResource getResource(int index) {
        Objects.checkIndex(index, OGESawbladeMountBlockEntity.SLOT_COUNT);
        return ItemResource.of(this.mount.getItem(index));
    }

    @Override
    public long getAmountAsLong(int index) {
        Objects.checkIndex(index, OGESawbladeMountBlockEntity.SLOT_COUNT);
        return this.mount.getItem(index).getCount();
    }

    @Override
    public long getCapacityAsLong(int index, ItemResource resource) {
        Objects.checkIndex(index, OGESawbladeMountBlockEntity.SLOT_COUNT);
        if (!isValid(index, resource)) {
            return 0;
        }
        return getSlotLimit(index);
    }

    /** 只有武器/锯片槽对外收东西，废件槽只出不进。 */
    @Override
    public boolean isValid(int index, ItemResource resource) {
        return index == OGESawbladeMountBlockEntity.SLOT_WEAPON
                && this.mount.canPlaceItem(index, resource.toStack());
    }

    /** 旧实现的 getSlotLimit 语义：空格算 1，非空按当前堆的上限。 */
    private int getSlotLimit(int index) {
        ItemStack current = this.mount.getItem(index);
        return current.isEmpty() ? 1 : current.getMaxStackSize();
    }

    /** 只收武器/锯片槽（0），并且要过 {@code canPlaceItem} 的近战武器判定。 */
    @Override
    public int insert(int index, ItemResource resource, int amount, TransactionContext transaction) {
        Objects.checkIndex(index, OGESawbladeMountBlockEntity.SLOT_COUNT);
        ItemStack stack = resource.toStack(amount);
        if (stack.isEmpty() || index != OGESawbladeMountBlockEntity.SLOT_WEAPON
                || !this.mount.canPlaceItem(index, stack)) {
            return 0;
        }
        ItemStack current = this.mount.getItem(index);
        int limit = getSlotLimit(index);
        if (current.isEmpty()) {
            int moved = Math.min(stack.getCount(), limit);
            if (moved <= 0) {
                return 0;
            }
            updateSnapshots(transaction);
            this.mount.setItem(index, stack.copyWithCount(moved));
            return moved;
        }
        if (!ItemStack.isSameItemSameComponents(current, stack)) {
            return 0;
        }
        int room = limit - current.getCount();
        if (room <= 0) {
            return 0;
        }
        int moved = Math.min(stack.getCount(), room);
        updateSnapshots(transaction);
        current.grow(moved);
        this.mount.setItem(index, current);
        return moved;
    }

    /** 只许抽废件槽（1）。 */
    @Override
    public int extract(int index, ItemResource resource, int amount, TransactionContext transaction) {
        Objects.checkIndex(index, OGESawbladeMountBlockEntity.SLOT_COUNT);
        if (amount <= 0 || index != OGESawbladeMountBlockEntity.SLOT_WASTE) {
            return 0;
        }
        ItemStack current = this.mount.getItem(index);
        if (current.isEmpty() || !resource.matches(current)) {
            return 0;
        }
        int taken = Math.min(amount, current.getCount());
        updateSnapshots(transaction);
        current.shrink(taken);
        if (current.isEmpty()) {
            this.mount.setItem(index, ItemStack.EMPTY);
        }
        return taken;
    }

    // ---- 快照：两格内容一起记，回滚时原样写回 ----

    @Override
    protected ItemStack[] createSnapshot() {
        ItemStack[] snapshot = new ItemStack[OGESawbladeMountBlockEntity.SLOT_COUNT];
        for (int i = 0; i < snapshot.length; i++) {
            snapshot[i] = this.mount.getItem(i).copy();
        }
        return snapshot;
    }

    @Override
    protected void revertToSnapshot(ItemStack[] snapshot) {
        for (int i = 0; i < snapshot.length; i++) {
            if (!ItemStack.isSameItemSameComponents(snapshot[i], this.mount.getItem(i))) {
                this.mount.setItem(i, snapshot[i]);
            }
        }
    }

    @Override
    protected void onRootCommit(ItemStack[] originalState) {
        this.mount.setChanged();
    }
}
