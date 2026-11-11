package cn.origolds.ia;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * 金源虚空抽屉的容器菜单。
 *
 * <p>菜单固定创建 729 个抽屉槽（对应方块实体里从第 0 格起码满的紧凑列表），
 * 但“开放到第几格”由 {@link OGEVoidDrawerBlockEntity#getUnlockedSlotCount()}
 * 动态决定：客户端屏幕每帧据此重摆坐标，未解锁的槽被挪到屏幕外，
 * 既看不见也点不到。“逐格解锁”纯是展示层，插入校验（单类型、总空间）
 * 对所有 729 格统一生效，以便 Inventory Profiles Next 这类
 * 物品栏管理模组能在一次操作里批量转移多堆同类物品。</p>
 *
 * <p>抽屉槽位是 {@link DrawerSlot}：1.21.1 的原版 {@code Slot#mayPlace} 默认
 * {@code return true}，并不会自动代理到 {@code Container#canPlaceItem}——
 * 裸 Slot 会让 doClick 的换堆/交换分支把类型不符的物品直写进方块实体的
 * {@code setItem}。DrawerSlot 显式接线后，类型校验才真正进入点击管线。</p>
 *
 * <p>物品栏管理模组普遍实现了"按住修饰键+左键划动快速转移"：按住期间会对悬停槽位
 * 持续重复发送 QUICK_MOVE 点击。普通箱子搬空即止没有影响，但抽屉每搬走一格就会把
 * 下一堆补进同一格，重复点击等于把整个抽屉从这一格抽干。对策：客户端在按住
 * Ctrl/Shift/Alt 任一按键期间把"按键被按住"同步给服务端（见 {@link OGEVoidDrawerAutoSortSync}），
 * 服务端排序循环整体跳过（{@link OGEVoidDrawerBlockEntity#setModifierHeld(boolean)}），
 * 按住时一格只会被搬走一次，松手立即恢复整理。这类模组的划动功能都以这三个键为
 * 基础键，因此通用地覆盖所有同类模组，不针对特定模组。</p>
 *
 * <p>菜单只负责开箱/关箱时通知抽屉核对整理（{@link OGEVoidDrawerBlockEntity#onMenuOpened()} /
 * {@link OGEVoidDrawerBlockEntity#onMenuClosed()}）；真正的逐 tick 排序循环在方块实体里，
 * 菜单不再插手，避免写入与整理互相打架。</p>
 */
public final class OGEVoidDrawerMenu extends AbstractContainerMenu {

    /** 抽屉槽位总数（与方块实体的 SLOT_COUNT 一致）。 */
    public static final int DRAWER_SLOTS = OGEVoidDrawerBlockEntity.SLOT_COUNT;

    /** 界面抽屉区每行的列数。 */
    public static final int COLUMNS = 9;

    /** 屏幕抽屉区固定可视行数，超出部分全部通过滚动查看。 */
    public static final int MAX_VISIBLE_ROWS = 3;

    private final OGEVoidDrawerBlockEntity drawer;
    private final int drawerSlotCount;
    private final DataSlot usedStacksSlot;
    private final DataSlot totalItemsSlot;

    public OGEVoidDrawerMenu(int containerId, Inventory playerInventory, BlockPos pos) {
        super(ModMenus.VOID_DRAWER.get(), containerId);
        BlockEntity be = playerInventory.player.level().getBlockEntity(pos);
        this.drawer = be instanceof OGEVoidDrawerBlockEntity voidDrawer ? voidDrawer : null;
        this.drawerSlotCount = drawer != null ? DRAWER_SLOTS : 0;

        if (drawer != null) {
            // 名义坐标仅供服务端参考；客户端屏幕每帧按解锁数与滚动位置重排。
            for (int i = 0; i < DRAWER_SLOTS; i++) {
                addSlot(new DrawerSlot(drawer, i, 8 + (i % COLUMNS) * 18, 18 + (i / COLUMNS) * 18));
            }
            addDataSlot(usedStacksSlot = DataSlot.forContainer(drawer, 0));
            addDataSlot(totalItemsSlot = DataSlot.forContainer(drawer, 1));
            // 打开抽屉：登记打开数并强制核对整理一次（客户端侧该方法自动忽略）。
            drawer.onMenuOpened();
        } else {
            usedStacksSlot = DataSlot.standalone();
            totalItemsSlot = DataSlot.standalone();
        }

        // 玩家背包：抽屉区固定按最大可视行数排（3 行），屏幕会按实际行数整体上移。
        int mainY = 18 + MAX_VISIBLE_ROWS * 18 + 16;
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < COLUMNS; col++) {
                addSlot(new Slot(playerInventory, col + row * COLUMNS + COLUMNS, 8 + col * 18, mainY + row * 18));
            }
        }
        int hotbarY = mainY + 58;
        for (int col = 0; col < COLUMNS; col++) {
            addSlot(new Slot(playerInventory, col, 8 + col * 18, hotbarY));
        }
    }

    public OGEVoidDrawerMenu(int containerId, Inventory playerInventory, RegistryFriendlyByteBuf buffer) {
        this(containerId, playerInventory, buffer.readBlockPos());
    }

    public int getDrawerSlotCount() {
        return drawerSlotCount;
    }

    /** 当前已解锁（可见）的抽屉槽位数：已用格数 + 1，封顶 729。 */
    public int getUnlockedSlotCount() {
        return drawer != null ? drawer.getUnlockedSlotCount() : 0;
    }

    public int getUsedStacks() {
        return usedStacksSlot.get();
    }

    public int getTotalItems() {
        return totalItemsSlot.get();
    }

    /** 当前绑定的过滤物品（1 个样本），未绑定时为空栈；界面据此绘制空槽虚影。 */
    public ItemStack getBound() {
        return drawer != null ? drawer.getBound() : ItemStack.EMPTY;
    }

    /**
     * 转发按键状态到抽屉方块实体（服务端收到 {@link OGEVoidDrawerPausePayload} 时调用）。
     * 按住时长只在服务端有意义；客户端镜像的重排全由服务端的槽位同步包驱动。
     */
    public void setModifierHeld(boolean held) {
        if (drawer != null) {
            drawer.setModifierHeld(held);
        }
    }

    /** 关闭界面：清除按键标记并强制核对整理一次，避免暂停状态泄漏到界面之外。 */
    @Override
    public void removed(Player player) {
        super.removed(player);
        if (drawer != null) {
            drawer.onMenuClosed();
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        ItemStack result = ItemStack.EMPTY;
        Slot slot = this.slots.get(index);
        if (slot != null && slot.hasItem()) {
            ItemStack stack = slot.getItem();
            result = stack.copy();
            if (drawer == null) {
                return ItemStack.EMPTY;
            }
            if (index < drawerSlotCount) {
                // 抽屉 → 玩家背包 + 快捷栏
                if (!moveItemStackTo(stack, drawerSlotCount, drawerSlotCount + 36, true)) {
                    return ItemStack.EMPTY;
                }
            } else if (!moveItemStackTo(stack, 0, drawerSlotCount, false)) {
                // 玩家背包 → 抽屉（未解锁/类型不符/整堆放不下的槽位由 mayPlace 拦下）
                return ItemStack.EMPTY;
            }
            if (stack.isEmpty()) {
                slot.setByPlayer(ItemStack.EMPTY);
            } else {
                slot.setChanged();
            }
            if (stack.getCount() == result.getCount()) {
                return ItemStack.EMPTY;
            }
            slot.onTake(player, stack);
        }
        return result;
    }

    @Override
    public boolean stillValid(Player player) {
        return drawer != null && drawer.stillValid(player);
    }

    /**
     * 抽屉槽位：把方块实体的插入校验（单类型 + 总空间）显式接进原版点击管线。
     *
     * <p>1.21.1 的 {@code Slot#mayPlace} 默认恒为 true，原版箱子/木桶不需要限制
     * 所以从不重写；抽屉若不重写，doClick 的“满堆换堆”“数字键交换”等分支就会
     * 把类型不符的物品直写进 {@code Container#setItem}——旧实现在那里静默丢弃，
     * 即玩家看到的“物品被吞”。接线后这些点击在客户端预测与服务端都会被拒绝。</p>
     */
    static final class DrawerSlot extends Slot {
        DrawerSlot(OGEVoidDrawerBlockEntity drawer, int index, int x, int y) {
            super(drawer, index, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return container.canPlaceItem(getContainerSlot(), stack);
        }
    }
}
