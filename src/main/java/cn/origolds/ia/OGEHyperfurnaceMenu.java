package cn.origolds.ia;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * 金源超级熔炉的容器菜单：上面 3 行 27 格存储，下面接玩家背包。
 *
 * <p>存储栏对外可被漏斗、物流管道插入，这里只负责界面上的点击与快速移动。熔炉本身
 * 每刻从这 27 格里取可熔炼的物料，产物照旧从顶部出料口送出。</p>
 */
public final class OGEHyperfurnaceMenu extends AbstractContainerMenu {

    /** 物品栏格数（与方块实体一致）。 */
    public static final int SLOT_COUNT = OGEHyperfurnaceBlockEntity.SLOT_COUNT;

    /** 每行列数。 */
    public static final int COLUMNS = 9;

    /** 固定行数（27 格 = 3 行）。 */
    public static final int ROWS = 3;

    // ------------------------------------------------------------------
    // 面板布局（面板局部坐标，菜单与界面共用）
    // ------------------------------------------------------------------

    public static final int PANEL_WIDTH = 176;

    /** 物品栏第一行槽位的纵坐标。 */
    public static final int SLOTS_Y = 20;

    /** 玩家背包主区第一行的纵坐标。 */
    public static final int PLAYER_Y = 90;

    /** 快捷栏的纵坐标。 */
    public static final int HOTBAR_Y = 148;

    /** 面板总高度。 */
    public static final int PANEL_HEIGHT = HOTBAR_Y + 18 + 8;

    private final OGEHyperfurnaceBlockEntity furnace;

    public OGEHyperfurnaceMenu(int containerId, Inventory playerInventory, BlockPos pos) {
        super(ModMenus.HYPERFURNACE.get(), containerId);
        BlockEntity be = playerInventory.player.level().getBlockEntity(pos);
        this.furnace = be instanceof OGEHyperfurnaceBlockEntity hyperfurnace ? hyperfurnace : null;

        if (furnace != null) {
            for (int i = 0; i < SLOT_COUNT; i++) {
                addSlot(new Slot(furnace, i, 8 + (i % COLUMNS) * 18, SLOTS_Y + (i / COLUMNS) * 18));
            }
        }

        // 玩家背包：紧贴物品栏下方。
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

    public OGEHyperfurnaceMenu(int containerId, Inventory playerInventory, RegistryFriendlyByteBuf buffer) {
        this(containerId, playerInventory, buffer.readBlockPos());
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        ItemStack result = ItemStack.EMPTY;
        Slot slot = this.slots.get(index);
        if (slot == null || !slot.hasItem() || furnace == null) {
            return result;
        }
        ItemStack stack = slot.getItem();
        result = stack.copy();
        if (index < SLOT_COUNT) {
            // 物品栏 → 玩家背包 + 快捷栏
            if (!moveItemStackTo(stack, SLOT_COUNT, SLOT_COUNT + 36, true)) {
                return ItemStack.EMPTY;
            }
        } else if (!moveItemStackTo(stack, 0, SLOT_COUNT, false)) {
            // 玩家背包 → 物品栏
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
        return furnace != null && furnace.stillValid(player);
    }
}
