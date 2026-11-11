package cn.origolds.ia;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.Containers;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * 金源虚空抽屉方块实体——存储逻辑的唯一实现处。
 *
 * <p>整套存储只有一条不变式：<b>抽屉里只允许存在一种物品（含数据组件完全一致），
 * 并且始终按“先放满整组、末尾放余数”的顺序紧凑码放</b>。所有写入最终都会被
 * {@link #sortIfNeeded()} 这一个入口核对并纠正，不存在多处事件互相补位、
 * 互相拦截的情况。</p>
 *
 * <h2>容量</h2>
 * <p>{@code drawerCapacityNum} = 首个存储物品的单组上限 × {@value #SLOT_COUNT}。
 * 它在物品种类确定后由 {@link #refreshCapacity()} 存入字段，空抽屉为 0。</p>
 *
 * <h2>排序循环</h2>
 * <p>三个修饰键（Shift / Ctrl / Alt）是否按下的判定由客户端每 tick 完成并通过
 * {@link OGEVoidDrawerPausePayload} 同步到服务端（服务端读不到键盘）。
 * 抽屉打开期间，服务端每 tick 跑一次 {@link #tick()}：</p>
 * <ul>
 *   <li>三个键<b>任一被按住</b>时整个循环直接跳过，绝不整理；</li>
 *   <li>未被按住时，读取抽屉物品总数，用总数除以单组上限：
 *       商 = 完整物品组数，余数 = 末尾残缺组数量；</li>
 *   <li>物品总数与上次排序时不同 → 立刻核对并整理一次；</li>
 *   <li>连续 {@value #SORT_IDLE_TICKS} tick（1 秒）没有执行过排序 → 核对并整理一次；</li>
 *   <li>核对时若实际布局已与商/余数一致，则什么都不做；不一致才写入正确布局。</li>
 * </ul>
 * <p>此外，打开抽屉、关闭抽屉时各强制核对整理一次。</p>
 *
 * <h2>UI 只读接口</h2>
 * <p>{@link #usedStackCount()} / {@link #totalItemCount()} / {@link #getUnlockedSlotCount()}
 * 只给界面读数，不参与存储决策。</p>
 *
 * <h2>掉落/取色</h2>
 * <p>内容物经由 {@code origolds_ia:void_drawer_contents} 数据组件随掉落物保留
 * （原版 container 组件编码上限 256 项，装不下 729 组），放置时还原。</p>
 */
public final class OGEVoidDrawerBlockEntity extends BaseContainerBlockEntity implements ContainerData, OGEItemBindable {

    /** 抽屉容量（组 / 格子数）。 */
    public static final int SLOT_COUNT = 729;

    /** 连续多少 tick 没有执行排序就强制核对一次（1 秒）。 */
    private static final int SORT_IDLE_TICKS = 20;

    /** 按住按键的心跳有效期：松开包最多丢失这么久后自动恢复整理（避免永久暂停）。 */
    private static final long MODIFIER_EXPIRE_TICKS = 60;

    /** {@link ContainerData} 同步索引：0=已用格数，1=物品总数。 */
    private static final int DATA_USED_STACKS = 0;
    private static final int DATA_TOTAL_ITEMS = 1;

    /**
     * drawerCapacityNum：首个存储物品的单组上限 × {@value #SLOT_COUNT}。
     * 空抽屉为 0；每次物品种类变化或整理后由 {@link #refreshCapacity()} 刷新。
     */
    private int drawerCapacityNum;

    /** 物理槽位列表；只允许“前若干格满组 + 一格余数 + 后面全空”这一种形态。 */
    private NonNullList<ItemStack> items = NonNullList.withSize(SLOT_COUNT, ItemStack.EMPTY);

    /** Shift / Ctrl / Alt 任一是否被按住（由客户端同步；按住时排序循环整体跳过）。 */
    private boolean modifierHeld;

    /** modifierHeld 的到期游戏刻，防止松开包丢失后永久暂停。 */
    private long modifierExpireAt = Long.MIN_VALUE;

    /** 上次排序核对时的物品总数，用于“总数变化”触发。 */
    private int lastSortedTotal = -1;

    /** 距上次排序核对的 tick 数，用于“连续 1 秒未排序”触发。 */
    private int ticksSinceSort;

    /** 当前打开本抽屉菜单的玩家数；只有 >0 时才跑每 tick 排序循环。 */
    private int openMenuCount;

    /** 有未整理的写入（setItem / removeItem 之后置位），下一个服务端 tick 必须核对一次。 */
    private boolean layoutDirty;

    /**
     * 已存物品总数的读缓存（{@code -1} = 需重算）。
     *
     * <p><b>只允许用于“是否放行”的准入判断</b>（{@link #canPlaceItem(int, ItemStack)} 与
     * {@link #HANDLER} 的 isItemValid），绝不可用于任何会改变存储数量的决策。原因是
     * 外部自动化（漏斗、无线物流管道、物品栏管理模组）会按 729 个槽位逐个询问准入，
     * 每问一次都做一遍 729 格全量求和就退化成 O(729²)，一次转移即可产生数十万次运算，
     * 多个管道同时工作时足以拖垮服务端刻。</p>
     *
     * <p>真实数量（整理、存档、抽取、溢出兜底）一律继续用 {@link #totalItemCount()}
     * 精确重算；缓存即使短暂失真，也只会让某次准入判断偶发偏差，而
     * {@link #setItem(int, ItemStack)} / 插入路径仍会精确裁剪并掉落溢出，不会吞物品。
     * 任何改动 {@code items} 的路径都必须让缓存失效。</p>
     */
    private int cachedTotalCount = -1;

    /**
     * 绑定过滤物品的共享状态组件（只存 1 个样本）；未绑定时为空栈。
     *
     * <p>玩家手持物品 Alt+中键把该物品设成唯一接受物品（见 {@link OGEItemBind}），
     * 空手 Alt+中键清空。绑定后任何插入路径（点击、漏斗、物流管道）都只放行这一种物品；
     * 空槽位的虚影也按它绘制。绑定与已有内容必须相容，否则
     * {@link #isBindingCompatible(ItemStack)} 会把绑定挡下。</p>
     *
     * <p>存取、比对与读档写档都在 {@link OGEBoundFilter} 里，与引力匣、超级漏斗共用同一份实现；
     * 本类只负责“什么时候标脏、什么时候发同步包”。</p>
     */
    private final OGEBoundFilter boundFilter = new OGEBoundFilter();

    public OGEVoidDrawerBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.OGE_VOID_DRAWER.get(), pos, state);
    }

    // ------------------------------------------------------------------
    // Container 基础：固定 729 格
    // ------------------------------------------------------------------

    @Override
    public int getContainerSize() {
        return SLOT_COUNT;
    }

    @Override
    protected NonNullList<ItemStack> getItems() {
        return items;
    }

    @Override
    protected void setItems(NonNullList<ItemStack> newItems) {
        NonNullList<ItemStack> target = NonNullList.withSize(SLOT_COUNT, ItemStack.EMPTY);
        for (int i = 0; i < Math.min(newItems.size(), SLOT_COUNT); i++) {
            target.set(i, newItems.get(i));
        }
        this.items = target;
        invalidateCountCache();
        refreshCapacity();
    }

    @Override
    public ItemStack getItem(int index) {
        return index >= 0 && index < SLOT_COUNT ? items.get(index) : ItemStack.EMPTY;
    }

    /**
     * 槽位写入。这里只做三件事：类型拦截、单格上限裁剪、总数溢出兜底。
     * 真正的“码满排序”不在这里做，统一交给排序循环，避免写入与整理互相打架。
     */
    @Override
    public void setItem(int index, ItemStack stack) {
        if (index < 0 || index >= SLOT_COUNT) {
            return;
        }
        // 客户端：镜像槽位只做直写，重排全由服务端同步包驱动。
        if (level != null && level.isClientSide) {
            items.set(index, stack);
            invalidateCountCache();
            return;
        }
        if (!stack.isEmpty() && !accepts(stack)) {
            // 类型不符的写入本应被菜单槽位的 mayPlace 挡下；走到这里说明外部代码直写，
            // 一律掉回地上，绝不静默吞掉。
            dropOutside(stack);
            return;
        }
        ItemStack placed = stack;
        if (!placed.isEmpty()) {
            int max = maxStackSize(placed);
            if (placed.getCount() > max) {
                dropOutside(placed.copyWithCount(placed.getCount() - max));
                placed = placed.copyWithCount(max);
            }
        }
        items.set(index, placed);
        invalidateCountCache();
        dropOverflow();
        refreshCapacity();
        layoutDirty = true;
        markDirtyServer();
    }

    @Override
    public ItemStack removeItem(int index, int amount) {
        ItemStack removed = super.removeItem(index, amount);
        if (!removed.isEmpty() && !isClientSide()) {
            invalidateCountCache();
            refreshCapacity();
            layoutDirty = true;
            markDirtyServer();
        }
        return removed;
    }

    @Override
    public ItemStack removeItemNoUpdate(int index) {
        ItemStack removed = super.removeItemNoUpdate(index);
        if (!removed.isEmpty() && !isClientSide()) {
            invalidateCountCache();
            refreshCapacity();
            layoutDirty = true;
            markDirtyServer();
        }
        return removed;
    }

    @Override
    public boolean canPlaceItem(int index, ItemStack stack) {
        if (index < 0 || index >= SLOT_COUNT || stack.isEmpty() || !accepts(stack)) {
            return false;
        }
        // 准入判断用缓存读数即可（每次点击会被询问 729 次，精确求和会放大成 O(729²)）。
        int space = SLOT_COUNT * maxStackSize(stack) - totalItemCountFast();
        if (space <= 0) {
            return false;
        }
        // 只校验本次写入的净增量是否有位置；逐格解锁纯属 UI 展示，不参与服务端判定。
        ItemStack current = items.get(index);
        return stack.getCount() - current.getCount() <= space;
    }

    // ------------------------------------------------------------------
    // 排序循环：每 tick 检查按键 → 总数变化 / 1 秒空闲时核对整理
    // ------------------------------------------------------------------

    /**
     * 服务端每 tick 调用。按住 Shift / Ctrl / Alt 时整体跳过；
     * 有未整理写入时优先核对一次（关着抽屉也能兜住直写路径）；
     * 抽屉打开期间再按“总数变化 / 连续 1 秒未排序”两个条件触发。
     */
    public void tick() {
        if (isClientSide()) {
            return;
        }
        boolean canSort = !isModifierHeld();
        if (layoutDirty && canSort) {
            sortIfNeeded();
            return;
        }
        if (openMenuCount <= 0 || !canSort) {
            return;
        }
        int total = totalItemCount();
        if (total != lastSortedTotal) {
            sortIfNeeded();
            return;
        }
        if (++ticksSinceSort >= SORT_IDLE_TICKS) {
            sortIfNeeded();
        }
    }

    /** 打开抽屉时调用：记录打开数并强制核对整理一次。 */
    public void onMenuOpened() {
        if (isClientSide()) {
            return;
        }
        openMenuCount++;
        sortIfNeeded();
    }

    /** 关闭抽屉时调用：清掉按键暂停标记并强制核对整理一次。 */
    public void onMenuClosed() {
        if (isClientSide()) {
            return;
        }
        if (openMenuCount > 0) {
            openMenuCount--;
        }
        modifierHeld = false;
        modifierExpireAt = Long.MIN_VALUE;
        sortIfNeeded();
    }

    /** 客户端同步过来的按键状态：true = Shift/Ctrl/Alt 任一被按住。 */
    public void setModifierHeld(boolean held) {
        if (isClientSide()) {
            return;
        }
        if (held) {
            modifierHeld = true;
            modifierExpireAt = (level == null ? 0L : level.getGameTime()) + MODIFIER_EXPIRE_TICKS;
        } else {
            modifierHeld = false;
            modifierExpireAt = Long.MIN_VALUE;
        }
    }

    /** 三个修饰键当前是否处于按住状态（带心跳超时自愈）。 */
    public boolean isModifierHeld() {
        return modifierHeld && level != null && level.getGameTime() < modifierExpireAt;
    }

    /**
     * 存储逻辑唯一入口：把抽屉整理成应有的样子。
     *
     * <p>先用“总数 / 单组上限”算出商（完整组数）与余数（残缺组数量），
     * 再与当前实际布局逐格比对；一致就什么都不做，不一致才写回。</p>
     */
    public void sortIfNeeded() {
        if (isClientSide()) {
            return;
        }
        layoutDirty = false;
        ticksSinceSort = 0;
        lastSortedTotal = totalItemCount();

        ItemStack proto = prototype();
        int total = lastSortedTotal;
        if (proto.isEmpty() || total <= 0) {
            drawerCapacityNum = 0;
            if (usedStackCount() > 0) {
                clearAllInternal();
                markDirtyServer();
            }
            return;
        }

        int max = maxStackSize(proto);
        drawerCapacityNum = SLOT_COUNT * max;
        if (total > drawerCapacityNum) {
            // 理论上点击管线不会写超；绕过校验的直写只在此兜底，多的掉回地上。
            dropExcess(proto, total - drawerCapacityNum);
            total = totalItemCount();
            lastSortedTotal = total;
        }
        int full = total / max;
        int rem = total % max;
        if (isSorted(proto, max, full, rem)) {
            return;
        }
        rebuild(proto, max, full, rem);
        markDirtyServer();
    }

    /** 实际布局是否已经是“full 个满组 + 末尾 rem 余数 + 其余全空”。 */
    private boolean isSorted(ItemStack proto, int max, int full, int rem) {
        for (int i = 0; i < SLOT_COUNT; i++) {
            ItemStack stack = items.get(i);
            int want = i < full ? max : (i == full ? rem : 0);
            if (want == 0) {
                if (!stack.isEmpty()) {
                    return false;
                }
            } else if (stack.isEmpty() || stack.getCount() != want
                    || !ItemStack.isSameItemSameComponents(stack, proto)) {
                return false;
            }
        }
        return true;
    }

    /** 按“先满组、后余数”写回槽位；只改真正不一致的格，避免无谓分配。 */
    private void rebuild(ItemStack proto, int max, int full, int rem) {
        invalidateCountCache();
        for (int i = 0; i < SLOT_COUNT; i++) {
            int want = i < full ? max : (i == full ? rem : 0);
            ItemStack current = items.get(i);
            if (want == 0) {
                if (!current.isEmpty()) {
                    items.set(i, ItemStack.EMPTY);
                }
            } else if (current.isEmpty() || current.getCount() != want
                    || !ItemStack.isSameItemSameComponents(current, proto)) {
                items.set(i, proto.copyWithCount(want));
            }
        }
    }

    /** 按“种类 + 总数”直接铺好（用于物流插入等已知总量的路径）。 */
    private void rebuildFromTotal(ItemStack proto, int total) {
        int max = maxStackSize(proto);
        rebuild(proto, max, total / max, total % max);
    }

    /** 把超出总容量的物品从后往前摘下并丢回世界。 */
    private void dropExcess(ItemStack proto, int excess) {
        invalidateCountCache(); // 下面是原地 shrink，必须让总数缓存失效
        int dropped = 0;
        for (int i = SLOT_COUNT - 1; i >= 0 && dropped < excess; i--) {
            ItemStack stack = items.get(i);
            if (stack.isEmpty()) {
                continue;
            }
            int take = Math.min(excess - dropped, stack.getCount());
            stack.shrink(take);
            if (stack.isEmpty()) {
                items.set(i, ItemStack.EMPTY);
            }
            dropped += take;
        }
        dropOutside(proto.copyWithCount(dropped));
    }

    /** 直接写入路径的总数溢出兜底（写完后调用）。 */
    private void dropOverflow() {
        ItemStack proto = prototype();
        if (proto.isEmpty()) {
            return;
        }
        int cap = SLOT_COUNT * maxStackSize(proto);
        int total = totalItemCount();
        if (total > cap) {
            dropExcess(proto, total - cap);
        }
    }

    private void clearAllInternal() {
        for (int i = 0; i < SLOT_COUNT; i++) {
            if (!items.get(i).isEmpty()) {
                items.set(i, ItemStack.EMPTY);
            }
        }
        invalidateCountCache();
        drawerCapacityNum = 0;
    }

    /** 把物品丢到抽屉脚边（仅服务端）。 */
    private void dropOutside(ItemStack stack) {
        if (level != null && !level.isClientSide && !stack.isEmpty()) {
            Containers.dropItemStack(level,
                    worldPosition.getX() + 0.5, worldPosition.getY() + 1.5, worldPosition.getZ() + 0.5, stack);
        }
    }

    private void markDirtyServer() {
        if (level != null && !level.isClientSide) {
            setChanged();
            level.updateNeighbourForOutputSignal(worldPosition, getBlockState().getBlock());
        }
    }

    /** 把方块实体数据（含绑定物品）推给周围客户端，供抽屉界面的空槽虚影读取。 */
    private void syncToClients() {
        if (level != null && !level.isClientSide) {
            BlockState state = getBlockState();
            level.sendBlockUpdated(worldPosition, state, state, Block.UPDATE_ALL);
        }
    }

    /**
     * 原版 {@code Slot#setChanged()} 在“同容器内合并堆叠”时会直接原地改写
     * {@code items} 里的 {@link ItemStack}（只调用容器的 {@code setChanged()}，
     * 不经过 {@link #setItem(int, ItemStack)}）。覆写这里让所有这类隐藏写入都会
     * 让总数缓存失效，保证 {@link #totalItemCountFast()} 不会读到过期值。
     */
    @Override
    public void setChanged() {
        super.setChanged();
        invalidateCountCache();
    }

    private void invalidateCountCache() {
        cachedTotalCount = -1;
    }

    private boolean isClientSide() {
        return level != null && level.isClientSide;
    }

    // ------------------------------------------------------------------
    // 抽屉规则：单类型 / 容量 / 只读读数
    // ------------------------------------------------------------------

    /** 已存物品的种类原型（含全部数据组件），空抽屉返回空栈。 */
    public ItemStack prototype() {
        for (ItemStack stack : items) {
            if (!stack.isEmpty()) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }

    /** 当前绑定的过滤物品（1 个样本），未绑定时为空栈。界面虚影与准入判断都用它。 */
    @Override
    public ItemStack getBound() {
        return boundFilter.get();
    }

    /**
     * 写入绑定过滤物品；数量强制为 1（只当样本用）。传空栈表示清空绑定。
     *
     * <p>绑定只在服务端决定，客户端那份由方块实体数据包同步（见 {@link #getUpdateTag()}）。
     * 绑定没有变化时不标脏，但仍补发一次同步包：玩家重复按清空时，能把偶然漏掉上一次同步的
     * 客户端纠正回来，虚影不会一直挂着。</p>
     */
    @Override
    public void setBound(ItemStack stack) {
        if (isClientSide()) {
            return;
        }
        if (boundFilter.set(stack)) {
            markDirtyServer();
        }
        syncToClients();
    }

    /**
     * 新绑定是否与抽屉里已有的物品相容。
     *
     * <p>抽屉本身限死“只存一种物品”，若绑定一个与现有内容不同的物品，就会出现旧物品放不回、
     * 新物品也进不来的死结。所以空抽屉、或绑定物品与现有原型一致时才允许绑定。</p>
     */
    @Override
    public boolean isBindingCompatible(ItemStack stack) {
        ItemStack proto = prototype();
        return proto.isEmpty() || ItemStack.isSameItemSameComponents(proto, stack);
    }

    /** 该物品能否放进本抽屉：绑定的过滤物品（若有）与已存种类都必须一致。 */
    private boolean accepts(ItemStack stack) {
        if (!boundFilter.accepts(stack)) {
            return false;
        }
        ItemStack proto = prototype();
        return proto.isEmpty() || ItemStack.isSameItemSameComponents(proto, stack);
    }

    /**
     * 外部物流用的准入判断（与 {@link #HANDLER} 的 {@code isItemValid} 同规则）：
     * 物品与本抽屉已存种类一致且还有余量。
     *
     * <p>无线物流管道据此在起点筛选要输送的物品：不合规的物品直接跳过，
     * 不会永久占住管口把后面合规的物品一起堵死。</p>
     */
    public boolean canInsert(ItemStack stack) {
        return !stack.isEmpty() && accepts(stack)
                && SLOT_COUNT * maxStackSize(stack) - totalItemCountFast() > 0;
    }

    private static int maxStackSize(ItemStack stack) {
        return Math.max(1, stack.getMaxStackSize());
    }

    /** 刷新 {@link #drawerCapacityNum}：首个存储物品的单组上限 × 729。 */
    private void refreshCapacity() {
        ItemStack proto = prototype();
        drawerCapacityNum = proto.isEmpty() ? 0 : SLOT_COUNT * maxStackSize(proto);
    }

    /** drawerCapacityNum：当前存放物品的容量（组数上限 × 单组上限），空抽屉为 0。 */
    public int getDrawerCapacityNum() {
        return drawerCapacityNum;
    }

    public int usedStackCount() {
        int used = 0;
        for (ItemStack stack : items) {
            if (!stack.isEmpty()) {
                used++;
            }
        }
        return used;
    }

    /**
     * 精确的物品总数（729 格全量求和）。所有会改变存储数量的决策都必须用这个：
     * 整理、存档、抽取、溢出兜底。它同时会把准入缓存刷新为精确值。
     */
    public int totalItemCount() {
        int total = 0;
        for (ItemStack stack : items) {
            total += stack.getCount();
        }
        cachedTotalCount = total;
        return total;
    }

    /**
     * 准入判断专用的快速读数：命中缓存就直接返回，未命中才精确重算。
     *
     * <p>只可回答“还有没有放得下的空间”“是否已经满了”这类放行问题。
     * 缓存由所有写入路径（含 {@link #setChanged()}）失效，因此最坏也只在
     * 极端时序下让某次准入判断偶发偏差；真正的写入仍由
     * {@link #setItem(int, ItemStack)} / {@link #HANDLER} 精确裁剪并掉落溢出，
     * 不会造成物品被吞或凭空增殖。</p>
     */
    private int totalItemCountFast() {
        return cachedTotalCount >= 0 ? cachedTotalCount : totalItemCount();
    }

    /**
     * 可见（已解锁）槽位数：最后一个非空格的位置 + 2（至少 1 格、至多 729）。
     *
     * <p>用“最后一个非空格”而不是“第一个空格”计数：按住修饰键暂停整理期间，
     * 前面的格子可能被搬空留下空洞，若以第一个空格为界会把后面仍有物品的格子藏起来。</p>
     */
    public int getUnlockedSlotCount() {
        for (int i = SLOT_COUNT - 1; i >= 0; i--) {
            if (!items.get(i).isEmpty()) {
                return Math.min(SLOT_COUNT, i + 2);
            }
        }
        return 1;
    }

    /** 当前存放内容（种类+总数）的组件值；空抽屉返回 null。供掉落复制与创造取色使用。 */
    public OGEVoidDrawerContents getStoredContents() {
        return OGEVoidDrawerContents.of(prototype(), totalItemCount());
    }

    /** 比较器信号：按物品总数占容量的比例输出 0~15。 */
    public int getComparatorSignal() {
        ItemStack proto = prototype();
        int total = totalItemCount();
        if (proto.isEmpty() || total <= 0) {
            return 0;
        }
        int cap = SLOT_COUNT * maxStackSize(proto);
        double fraction = (double) total / cap;
        return Math.min(15, 1 + (int) Math.floor(fraction * 14.0));
    }

    // ------------------------------------------------------------------
    // 漏斗 / 物流管道等外部交互（IItemHandler）
    // ------------------------------------------------------------------

    /** 供能力系统暴露的物品处理视图：插入忽略具体槽位、抽取允许重排。 */
    public IItemHandler getItemHandler() {
        return HANDLER;
    }

    private final IItemHandler HANDLER = new IItemHandler() {
        @Override
        public int getSlots() {
            return SLOT_COUNT;
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            return getItem(slot);
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            if (stack.isEmpty() || !accepts(stack)) {
                return stack;
            }
            // 真正写入前只做一次精确求和：外部自动化会按 729 个槽位逐个调用本方法，
            // 每次再求一遍 729 格会放大成 O(729²)。
            int have = totalItemCount();
            int space = SLOT_COUNT * maxStackSize(stack) - have;
            if (space <= 0) {
                return stack;
            }
            int add = Math.min(stack.getCount(), space);
            if (!simulate) {
                ItemStack proto = prototype();
                rebuildFromTotal(proto.isEmpty() ? stack : proto, have + add);
                refreshCapacity();
                lastSortedTotal = totalItemCount();
                ticksSinceSort = 0;
                markDirtyServer();
            }
            int rest = stack.getCount() - add;
            return rest <= 0 ? ItemStack.EMPTY : stack.copyWithCount(rest);
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            if (amount <= 0) {
                return ItemStack.EMPTY;
            }
            int from = slot >= 0 && slot < SLOT_COUNT && !items.get(slot).isEmpty() ? slot : firstUsedSlot();
            if (from < 0) {
                return ItemStack.EMPTY;
            }
            ItemStack source = items.get(from);
            int take = Math.min(amount, source.getCount());
            ItemStack out = source.copyWithCount(take);
            if (!simulate) {
                int total = totalItemCount() - take;
                if (total <= 0) {
                    clearAllInternal();
                } else {
                    rebuildFromTotal(prototype(), total);
                }
                refreshCapacity();
                lastSortedTotal = Math.max(0, total);
                ticksSinceSort = 0;
                markDirtyServer();
            }
            return out;
        }

        @Override
        public int getSlotLimit(int slot) {
            ItemStack proto = prototype();
            return proto.isEmpty() ? 64 : maxStackSize(proto);
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            // 准入判断：外部会按全部 729 个槽位逐个询问，这里走缓存读数。
            return accepts(stack) && SLOT_COUNT * maxStackSize(stack) - totalItemCountFast() > 0;
        }
    };

    private int firstUsedSlot() {
        for (int i = 0; i < SLOT_COUNT; i++) {
            if (!items.get(i).isEmpty()) {
                return i;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------
    // 持久化：只写“种类 + 总数”，与 729 槽列表等价
    // ------------------------------------------------------------------

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        ItemStack proto = prototype();
        int total = totalItemCount();
        if (proto.isEmpty() || total <= 0) {
            tag.remove("DrawerItem");
            tag.remove("DrawerCount");
        } else {
            tag.put("DrawerItem", proto.copyWithCount(1).save(registries));
            tag.putInt("DrawerCount", total);
        }
        boundFilter.save(tag, registries);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        clearAllInternal();
        lastSortedTotal = -1;
        ticksSinceSort = 0;
        if (tag.contains("DrawerItem") && tag.contains("DrawerCount")) {
            ItemStack proto = ItemStack.parseOptional(registries, tag.getCompound("DrawerItem"));
            fillFrom(proto, tag.getInt("DrawerCount"));
        }
        boundFilter.load(tag, registries);
    }

    /**
     * 发往客户端的方块实体数据：内容 + 绑定物品。
     *
     * <p>内容只写“种类 + 总数”这一对（见 {@link #saveAdditional}），体积极小；
     * 客户端界面靠它拿到绑定物品来画空槽虚影。绑定变化时由
     * {@link #syncToClients()} 触发一次推送。</p>
     *
     * <p>末尾那个恒存在的标记不能省：NeoForge 的
     * {@code IBlockEntityExtension#onDataPacket} 只在标签非空时才调用
     * {@code loadWithComponents}。空抽屉 + 清空绑定正好会写出一份一个键都没有的标签，
     * 客户端于是收不到“绑定已清空”，空槽上的虚影会一直留着——这正是之前
     * “清空绑定时虚影有时不消失”的来源（抽屉里有东西时标签非空，反而正常）。
     * 补上标记后空抽屉的同步标签也不再为空，清空一定能传到客户端。</p>
     */
    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        saveAdditional(tag, registries);
        tag.putBoolean("OgeSync", true);
        return tag;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    /** 放置还原入口：按“种类 + 总数”直接铺满前格。 */
    private void fillFrom(ItemStack proto, int total) {
        if (proto.isEmpty() || total <= 0) {
            clearAllInternal();
            return;
        }
        int max = maxStackSize(proto);
        int clamped = Math.min(total, SLOT_COUNT * max);
        rebuild(proto, max, clamped / max, clamped % max);
        refreshCapacity();
        lastSortedTotal = clamped;
    }

    // ------------------------------------------------------------------
    // 掉落/取色保留内容：自定义组件（原版 container 组件上限 256 项）
    // ------------------------------------------------------------------

    @Override
    protected void collectImplicitComponents(DataComponentMap.Builder builder) {
        // 故意不调用父类实现：父类会把 729 格全量写进原版 container 组件（编解码上限 256 项会炸包）。
        if (getCustomName() != null) {
            builder.set(DataComponents.CUSTOM_NAME, getCustomName());
        }
        OGEVoidDrawerContents contents = OGEVoidDrawerContents.of(prototype(), totalItemCount());
        if (contents != null) {
            builder.set(ModDataComponents.VOID_DRAWER_CONTENTS.get(), contents);
        }
    }

    @Override
    protected void applyImplicitComponents(BlockEntity.DataComponentInput components) {
        super.applyImplicitComponents(components);
        OGEVoidDrawerContents contents = components.get(ModDataComponents.VOID_DRAWER_CONTENTS.get());
        if (contents != null && !contents.item().isEmpty()) {
            clearAllInternal();
            fillFrom(contents.item(), contents.count());
            markDirtyServer();
        }
    }

    // ------------------------------------------------------------------
    // ContainerData（UI 信息行）与 MenuProvider
    // ------------------------------------------------------------------

    @Override
    public int getCount() {
        return 2;
    }

    @Override
    public int get(int index) {
        return switch (index) {
            case DATA_USED_STACKS -> usedStackCount();
            case DATA_TOTAL_ITEMS -> totalItemCount();
            default -> 0;
        };
    }

    @Override
    public void set(int index, int value) {
        // 单向同步（服务端 → 客户端），客户端无需回写。
    }

    @Override
    protected Component getDefaultName() {
        return Component.translatable(getBlockState().getBlock().getDescriptionId());
    }

    @Override
    protected AbstractContainerMenu createMenu(int containerId, Inventory playerInventory) {
        return new OGEVoidDrawerMenu(containerId, playerInventory, worldPosition);
    }
}
