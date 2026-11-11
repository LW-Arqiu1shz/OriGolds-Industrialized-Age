package cn.origolds.ia;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * 金源自动合成机的容器菜单。
 *
 * <p>布局分四块：最上面是 9 个只做显示的合成格（不是真实槽位，点击时由界面发报文给
 * 服务端去锁定/清除），右边是配方预览；中间是 27 格存储，拆成 9 个竖着的 3 格，
 * 第 {@code c} 列只接受合成格 {@code c} 锁定的物品；最下面是玩家背包。</p>
 *
 * <p>合成格本身不放进 {@code slots}，因此鼠标拿着物品点它不会消耗物品、也不会被原版
 * 槽位逻辑搬走——这正是“拿着物品左键锁定、物品还在鼠标上”的实现基础。</p>
 */
public final class OGEMechCrafterMenu extends AbstractContainerMenu {

    /** 存储格数（9 列 × 3 行）。 */
    public static final int SLOT_COUNT = OGEMechCrafterBlockEntity.SLOT_COUNT;

    /** 每列的行数。 */
    public static final int ROWS_PER_COLUMN = OGEMechCrafterBlockEntity.ROWS_PER_COLUMN;

    /** 合成格边长。 */
    public static final int GRID_SIZE = OGEMechCrafterBlockEntity.GRID_SIZE;

    /** 合成格数量。 */
    public static final int CELL_COUNT = OGEMechCrafterBlockEntity.CELL_COUNT;

    /** 玩家背包每行列数。 */
    public static final int COLUMNS = 9;

    // ------------------------------------------------------------------
    // 面板布局（面板局部坐标，菜单与界面共用）
    // ------------------------------------------------------------------

    public static final int PANEL_WIDTH = 176;

    /** 合成格（不含边框）的边长。 */
    public static final int CELL = 18;

    /** 结果预览格的边长：普通格的 1.5 倍（和原版工作台/合成器的结果格一致）。 */
    public static final int RESULT_CELL = CELL * 3 / 2;

    /**
     * 3×3 合成格左上角。
     *
     * <p>这个值和 {@link #RESULT_X} 一起保证【合成格最左侧那条线 → 结果格最右侧那条线】
     * 这整块在 {@link #PANEL_WIDTH} 宽的面板里水平居中：整块左右各留 36 像素。</p>
     */
    public static final int GRID_X = 37;
    public static final int GRID_Y = 18;

    /** 配方预览格（1.5 倍结果格）左上角；纵向与 3×3 合成格对齐居中。 */
    public static final int RESULT_X = 114;
    public static final int RESULT_Y = 32;

    /** 操作提示文字的纵坐标：夹在合成格与存储栏之间的空隙里，不再压到存储边框。 */
    public static final int HINT_Y = GRID_Y + GRID_SIZE * CELL + 3;

    /** 27 格存储第一行（每列最上面那格）的纵坐标。 */
    public static final int STORAGE_Y = 86;

    /** 玩家背包主区第一行的纵坐标。 */
    public static final int PLAYER_Y = 156;

    /** 快捷栏的纵坐标。 */
    public static final int HOTBAR_Y = 214;

    /** 面板总高度。 */
    public static final int PANEL_HEIGHT = HOTBAR_Y + 18 + 8;

    private final Player owner;
    private final BlockPos pos;

    @Nullable
    private final OGEMechCrafterBlockEntity crafter;

    public OGEMechCrafterMenu(int containerId, Inventory playerInventory, BlockPos pos) {
        super(ModMenus.MECH_CRAFTER.get(), containerId);
        this.owner = playerInventory.player;
        this.pos = pos;
        BlockEntity be = playerInventory.player.level().getBlockEntity(pos);
        this.crafter = be instanceof OGEMechCrafterBlockEntity mechCrafter ? mechCrafter : null;

        if (crafter != null) {
            // 9 个竖着的 3 格：槽位下标 i 的列 = i / 3（也就是它对应的合成格），
            // 行 = i % 3。
            for (int i = 0; i < SLOT_COUNT; i++) {
                addSlot(new MechCrafterSlot(crafter, i,
                        8 + (i / ROWS_PER_COLUMN) * 18,
                        STORAGE_Y + (i % ROWS_PER_COLUMN) * 18));
            }
        }

        // 玩家背包：紧贴存储栏下方。
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < COLUMNS; col++) {
                addSlot(new Slot(playerInventory, col + row * COLUMNS + COLUMNS,
                        8 + col * 18, PLAYER_Y + row * 18));
            }
        }
        for (int col = 0; col < COLUMNS; col++) {
            addSlot(new Slot(playerInventory, col, 8 + col * 18, HOTBAR_Y));
        }
    }

    public OGEMechCrafterMenu(int containerId, Inventory playerInventory, RegistryFriendlyByteBuf buffer) {
        this(containerId, playerInventory, buffer.readBlockPos());
    }

    public BlockPos getPos() {
        return pos;
    }

    @Nullable
    public OGEMechCrafterBlockEntity getCrafter() {
        return crafter;
    }

    /** 界面发来的锁定/清除请求：服务端会再校验一次方块是否还在、玩家是否还在范围内。 */
    public void setLock(int cell, ItemStack stack) {
        if (crafter != null && crafter.stillValid(owner)) {
            crafter.setLock(cell, stack);
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        ItemStack result = ItemStack.EMPTY;
        Slot slot = this.slots.get(index);
        if (slot == null || !slot.hasItem() || crafter == null) {
            return result;
        }
        ItemStack stack = slot.getItem();
        result = stack.copy();
        if (index < SLOT_COUNT) {
            // 存储栏 → 玩家背包 + 快捷栏
            if (!moveItemStackTo(stack, SLOT_COUNT, SLOT_COUNT + 36, true)) {
                return ItemStack.EMPTY;
            }
        } else if (!moveItemStackTo(stack, 0, SLOT_COUNT, false)) {
            // 玩家背包 → 存储栏
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
        return result;
    }

    @Override
    public boolean stillValid(Player player) {
        return crafter != null && crafter.stillValid(player);
    }

    /**
     * 存储槽位：把方块实体的准入规则显式接进原版点击管线，避免换堆、数字键交换等分支
     * 绕过服务端校验塞进没被锁定的物品。
     */
    static final class MechCrafterSlot extends Slot {
        MechCrafterSlot(OGEMechCrafterBlockEntity crafter, int index, int x, int y) {
            super(crafter, index, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return container.canPlaceItem(getContainerSlot(), stack);
        }
    }
}
