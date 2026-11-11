package cn.origolds.ia;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

/**
 * “绑定过滤物品”的可复用状态组件：金源虚空抽屉、金源引力匣、金源超级漏斗三台机器
 * 的绑定语义完全一致——只记 1 个样本、未绑定时为空栈、比对都是“种类 + 数据组件完全一致”。
 *
 * <p>这三台方块实体原本各自抄了一份 {@code bound} 字段、{@code getBound}／{@code setBound}、
 * {@code accepts}、读档写档，改一处就得同步改三处；这里把这份状态单独抽出来由方块实体持有。
 * 用组合而不是继承，是因为三台各有自己的方块实体基类，不适合再往继承链上塞东西；
 * 方块实体只保留“什么时候标脏、什么时候发同步包”这些真正因机器而异的部分。</p>
 *
 * <p>存档键固定为 {@value #TAG_BOUND_ITEM}，与模块化之前的格式完全一致，旧存档原样读得回来。</p>
 */
public final class OGEBoundFilter {

    /** NBT 键：三台机器共用。模块化之前就是这个键名，保持旧存档兼容。 */
    public static final String TAG_BOUND_ITEM = "BoundItem";

    /** 绑定样本（只存 1 个，含数据组件）；未绑定时为空栈。 */
    private ItemStack value = ItemStack.EMPTY;

    /** 当前绑定样本；未绑定时为空栈。 */
    public ItemStack get() {
        return value;
    }

    /** 是否处于未绑定状态。 */
    public boolean isEmpty() {
        return value.isEmpty();
    }

    /**
     * 写入绑定样本；数量强制为 1（只当样本用），传空栈表示清空绑定。
     *
     * @return 与旧值相比是否真的变了。返回 {@code false} 时调用方不必标脏，
     *         但仍建议补发一次同步包：玩家反复按清空时，能把偶然漏包的客户端纠正回来。
     */
    public boolean set(ItemStack stack) {
        ItemStack next = stack.isEmpty() ? ItemStack.EMPTY : stack.copyWithCount(1);
        if (ItemStack.isSameItemSameComponents(value, next)) {
            return false;
        }
        value = next;
        return true;
    }

    /** 该物品能否通过绑定过滤：空绑定一律放行，否则必须与样本（含数据组件）完全一致。 */
    public boolean accepts(ItemStack stack) {
        return value.isEmpty() || ItemStack.isSameItemSameComponents(value, stack);
    }

    /** 写档：只在绑定时写一个键，未绑定时什么都不写（与模块化之前的输出一致）。 */
    public void save(CompoundTag tag, HolderLookup.Provider registries) {
        if (!value.isEmpty()) {
            tag.put(TAG_BOUND_ITEM, value.copyWithCount(1).save(registries));
        }
    }

    /** 读档：缺失或解析失败都归为空绑定，并把数量强制回 1。 */
    public void load(CompoundTag tag, HolderLookup.Provider registries) {
        ItemStack parsed = tag.contains(TAG_BOUND_ITEM)
                ? ItemStack.parseOptional(registries, tag.getCompound(TAG_BOUND_ITEM))
                : ItemStack.EMPTY;
        value = parsed.isEmpty() ? ItemStack.EMPTY : parsed.copyWithCount(1);
    }
}
