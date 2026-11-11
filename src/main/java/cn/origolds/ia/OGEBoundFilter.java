package cn.origolds.ia;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * “绑定过滤物品”的存储小盒子：只保留 1 个的副本，判定“同类同组件才收”。
 *
 * <p>被引力匣、超级漏斗等需要过滤吸取的方块实体复用。26.x 的方块实体读写走
 * {@link ValueInput}/{@link ValueOutput}，所以这里不再摸 {@code CompoundTag}。</p>
 */
public final class OGEBoundFilter {

    public static final String TAG_BOUND_ITEM = "BoundItem";

    private ItemStack value = ItemStack.EMPTY;

    public ItemStack get() {
        return this.value;
    }

    public boolean isEmpty() {
        return this.value.isEmpty();
    }

    /** 写入绑定（内部只留 1 个的副本）；与原值相同则返回 false 表示无变化。 */
    public boolean set(ItemStack stack) {
        ItemStack next = stack.isEmpty() ? ItemStack.EMPTY : stack.copyWithCount(1);
        if (ItemStack.isSameItemSameComponents(this.value, next)) {
            return false;
        }
        this.value = next;
        return true;
    }

    /** 未绑定收一切；已绑定只收同类同组件。 */
    public boolean accepts(ItemStack stack) {
        return this.value.isEmpty() || ItemStack.isSameItemSameComponents(this.value, stack);
    }

    public void save(ValueOutput output) {
        if (!this.value.isEmpty()) {
            output.store(TAG_BOUND_ITEM, ItemStack.CODEC, this.value.copyWithCount(1));
        } else {
            output.discard(TAG_BOUND_ITEM);
        }
    }

    public void load(ValueInput input) {
        ItemStack parsed = input.read(TAG_BOUND_ITEM, ItemStack.CODEC).orElse(ItemStack.EMPTY);
        this.value = parsed.isEmpty() ? ItemStack.EMPTY : parsed.copyWithCount(1);
    }
}
