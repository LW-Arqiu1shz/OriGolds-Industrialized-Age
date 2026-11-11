package cn.origolds.ia;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 金源炼成台的容器菜单。
 *
 * <p>上半部是 3 行 27 格的原料工作区（只放行有 CRV 价值的原料），下半部是玩家
 * 背包与快捷栏。CRV 充能池是每刻都可能变的 int，超出 {@code DataSlot} 的同步
 * 量程，因此服务端在 {@link #broadcastChanges()} 里检测到变化时用
 * {@link OGEChrysopoeiaSyncPayload} 单独推给打开界面的玩家。</p>
 */
public final class OGEChrysopoeiaStationMenu extends AbstractContainerMenu {

    /** 原料工作区格数（与方块实体一致）。 */
    public static final int SLOT_COUNT = OGEChrysopoeiaStationBlockEntity.SLOT_COUNT;

    /** 工作区每行列数。 */
    public static final int COLUMNS = 9;

    /** 工作区行数。 */
    public static final int ROWS = 3;

    /** 面板尺寸。 */
    public static final int PANEL_WIDTH = 176;
    public static final int PANEL_HEIGHT = 186;

    /** 工作区、玩家背包、快捷栏的起始纵坐标。 */
    public static final int SLOTS_Y = 32;
    public static final int PLAYER_Y = 102;
    public static final int HOTBAR_Y = 160;

    private final Player owner;
    private final OGEChrysopoeiaStationBlockEntity station;

    /** 是否已经向 {@link #owner} 推过至少一次 CRV 同步。 */
    private boolean syncedOnce;

    /** 上一次推给玩家的 CRV 值。 */
    private int lastCrv = Integer.MIN_VALUE;

    public OGEChrysopoeiaStationMenu(int containerId, Inventory playerInventory, BlockPos pos) {
        super(ModMenus.CHRYSOPOEIA_STATION.get(), containerId);
        this.owner = playerInventory.player;
        this.station = playerInventory.player.level().getBlockEntity(pos)
                instanceof OGEChrysopoeiaStationBlockEntity chrysopoeia ? chrysopoeia : null;

        if (station != null) {
            for (int i = 0; i < SLOT_COUNT; i++) {
                addSlot(new StationSlot(station, i,
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
    public OGEChrysopoeiaStationMenu(int containerId, Inventory playerInventory,
                                     RegistryFriendlyByteBuf buffer) {
        this(containerId, playerInventory, buffer.readBlockPos());
    }

    @Override
    public void broadcastChanges() {
        super.broadcastChanges();
        if (owner instanceof ServerPlayer player && station != null) {
            int crv = station.getCrv();
            if (!syncedOnce || crv != lastCrv) {
                PacketDistributor.sendToPlayer(player, new OGEChrysopoeiaSyncPayload(crv));
                syncedOnce = true;
                lastCrv = crv;
            }
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        ItemStack result = ItemStack.EMPTY;
        Slot slot = this.slots.get(index);
        if (slot != null && slot.hasItem() && station != null) {
            ItemStack stack = slot.getItem();
            result = stack.copy();
            if (index < SLOT_COUNT) {
                // 工作区 → 玩家背包 + 快捷栏
                if (!moveItemStackTo(stack, SLOT_COUNT, SLOT_COUNT + 36, true)) {
                    return ItemStack.EMPTY;
                }
            } else if (!moveItemStackTo(stack, 0, SLOT_COUNT, false)) {
                // 玩家背包 → 工作区
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
        return station != null && station.stillValid(player);
    }

    /** 工作区槽位：只放行有 CRV 价值的原料。 */
    static final class StationSlot extends Slot {

        StationSlot(OGEChrysopoeiaStationBlockEntity station, int index, int x, int y) {
            super(station, index, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return container.canPlaceItem(getContainerSlot(), stack);
        }
    }
}
