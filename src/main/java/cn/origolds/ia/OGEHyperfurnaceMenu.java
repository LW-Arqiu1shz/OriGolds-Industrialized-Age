package cn.origolds.ia;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * 金源超级熔炉的容器菜单。
 *
 * <p>上半部是 3 行 27 格的原料区（任何物品都放得进来，只有带熔炼配方的会被烧），
 * 下半部是玩家背包与快捷栏。档位与进度都是服务端逻辑，本菜单不同步额外数据。</p>
 */
public final class OGEHyperfurnaceMenu extends AbstractContainerMenu {

    /** 原料区格数（与方块实体一致）。 */
    public static final int SLOT_COUNT = OGEHyperfurnaceBlockEntity.SLOT_COUNT;

    /** 原料区每行列数。 */
    public static final int COLUMNS = 9;

    /** 原料区行数。 */
    public static final int ROWS = 3;

    /** 面板尺寸。 */
    public static final int PANEL_WIDTH = 176;
    public static final int PANEL_HEIGHT = 174;

    /** 原料区、玩家背包、快捷栏的起始纵坐标。 */
    public static final int SLOTS_Y = 20;
    public static final int PLAYER_Y = 90;
    public static final int HOTBAR_Y = 148;

    private final OGEHyperfurnaceBlockEntity furnace;

    public OGEHyperfurnaceMenu(int containerId, Inventory playerInventory, BlockPos pos) {
        super(ModMenus.HYPERFURNACE.get(), containerId);
        this.furnace = playerInventory.player.level().getBlockEntity(pos)
                instanceof OGEHyperfurnaceBlockEntity hyperfurnace ? hyperfurnace : null;

        if (furnace != null) {
            for (int i = 0; i < SLOT_COUNT; i++) {
                addSlot(new Slot(furnace, i,
                        8 + (i % COLUMNS) * 18, SLOTS_Y + (i / COLUMNS) * 18));
            }
        }

        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < COLUMNS; col++) {
                addSlot(new Slot(playerInventory, col + row * COLUMNS + COLUMNS, 8 + col * 18,
                        PLAYER_Y + row * 18));
            }
        }
        for (int col = 0; col < COLUMNS; col++) {
            addSlot(new Slot(playerInventory, col, 8 + col * 18, HOTBAR_Y));
        }
    }

    /** 客户端侧的菜单构造：从报文里读出方块坐标再找方块实体。 */
    public OGEHyperfurnaceMenu(int containerId, Inventory playerInventory,
                               RegistryFriendlyByteBuf buffer) {
        this(containerId, playerInventory, buffer.readBlockPos());
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        ItemStack result = ItemStack.EMPTY;
        Slot slot = this.slots.get(index);
        if (slot != null && slot.hasItem() && furnace != null) {
            ItemStack stack = slot.getItem();
            result = stack.copy();
            if (index < SLOT_COUNT) {
                // 原料区 → 玩家背包 + 快捷栏
                if (!moveItemStackTo(stack, SLOT_COUNT, SLOT_COUNT + 36, true)) {
                    return ItemStack.EMPTY;
                }
            } else if (!moveItemStackTo(stack, 0, SLOT_COUNT, false)) {
                // 玩家背包 → 原料区
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
        return furnace != null && furnace.stillValid(player);
    }
}
