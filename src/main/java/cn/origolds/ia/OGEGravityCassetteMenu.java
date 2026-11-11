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
 * 金源引力匣的容器菜单。
 *
 * <p>它就是一个 3 行 27 格的普通箱子界面：所有格子一次全部可用，没有“逐格解锁”，
 * 也没有滚动与 ▲▼ 箭头，更没有自动整理。外观（底色、描边、容量数字）与虚空抽屉
 * 保持一致，仅展示逻辑更简单。</p>
 */
public final class OGEGravityCassetteMenu extends AbstractContainerMenu {

    /** 引力匣格数（与方块实体一致）。 */
    public static final int SLOT_COUNT = OGEGravityCassetteBlockEntity.SLOT_COUNT;

    /** 界面每行列数。 */
    public static final int COLUMNS = 9;

    /** 界面固定行数（27 格 = 3 行）。 */
    public static final int ROWS = 3;

    private final OGEGravityCassetteBlockEntity cassette;
    private final DataSlot usedStacksSlot;
    private final DataSlot totalItemsSlot;

    public OGEGravityCassetteMenu(int containerId, Inventory playerInventory, BlockPos pos) {
        super(ModMenus.GRAVITY_CASSETTE.get(), containerId);
        BlockEntity be = playerInventory.player.level().getBlockEntity(pos);
        this.cassette = be instanceof OGEGravityCassetteBlockEntity gravityCassette ? gravityCassette : null;

        if (cassette != null) {
            for (int i = 0; i < SLOT_COUNT; i++) {
                addSlot(new CassetteSlot(cassette, i, 8 + (i % COLUMNS) * 18, 18 + (i / COLUMNS) * 18));
            }
            addDataSlot(usedStacksSlot = DataSlot.forContainer(cassette, 0));
            addDataSlot(totalItemsSlot = DataSlot.forContainer(cassette, 1));
        } else {
            usedStacksSlot = DataSlot.standalone();
            totalItemsSlot = DataSlot.standalone();
        }

        // 玩家背包：紧贴 3 行引力匣区下方。
        int mainY = 18 + ROWS * 18 + 16;
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

    public OGEGravityCassetteMenu(int containerId, Inventory playerInventory, RegistryFriendlyByteBuf buffer) {
        this(containerId, playerInventory, buffer.readBlockPos());
    }

    public int getCassetteSlotCount() {
        return SLOT_COUNT;
    }

    /**
     * 当前绑定的过滤物品（1 个样本），未绑定时为空栈。
     *
     * <p>与虚空抽屉同义：界面据此在空槽里画虚影。客户端那份由方块实体数据包同步
     * （见 {@link OGEGravityCassetteBlockEntity#getUpdateTag()}）。</p>
     */
    public ItemStack getBound() {
        return cassette != null ? cassette.getBound() : ItemStack.EMPTY;
    }

    public int getUsedStacks() {
        return usedStacksSlot.get();
    }

    public int getTotalItems() {
        return totalItemsSlot.get();
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        ItemStack result = ItemStack.EMPTY;
        Slot slot = this.slots.get(index);
        if (slot != null && slot.hasItem()) {
            ItemStack stack = slot.getItem();
            result = stack.copy();
            if (cassette == null) {
                return ItemStack.EMPTY;
            }
            if (index < SLOT_COUNT) {
                // 引力匣 → 玩家背包 + 快捷栏
                if (!moveItemStackTo(stack, SLOT_COUNT, SLOT_COUNT + 36, true)) {
                    return ItemStack.EMPTY;
                }
            } else if (!moveItemStackTo(stack, 0, SLOT_COUNT, false)) {
                // 玩家背包 → 引力匣
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
        return cassette != null && cassette.stillValid(player);
    }

    /**
     * 引力匣槽位：把方块实体的绑定过滤（{@link OGEGravityCassetteBlockEntity#canPlaceItem(int, ItemStack)}）
     * 显式接进原版点击管线。
     *
     * <p>1.21.1 的 {@code Slot#mayPlace} 默认恒为 true，原版箱子不需要限制所以从不重写；
     * 绑定过滤若不接线，doClick 的换堆、数字键交换等分支就会绕过服务端的
     * {@code canPlaceItem}，把不符合绑定的物品塞进匣里。</p>
     */
    static final class CassetteSlot extends Slot {
        CassetteSlot(OGEGravityCassetteBlockEntity cassette, int index, int x, int y) {
            super(cassette, index, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return container.canPlaceItem(getContainerSlot(), stack);
        }
    }
}
