package cn.origolds.ia;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 金源炼金站的容器菜单：上面 3 行 27 格物品栏（只接受可炼金物品），下面接玩家背包。
 *
 * <p>炼金凝造价量（CRV）是每刻都在变的 {@code int}，超出菜单 {@code DataSlot}
 * （线上按 short 传输）的量程，所以不走数据槽，而是由 {@link #broadcastChanges()}
 * 在数值变化时给打开界面的玩家单发一个 {@link OGEChrysopoeiaSyncPayload}。
 * 菜单刚打开时 {@code syncedOnce} 还是 false，第一次 tick 会无条件下发一次初始数值。</p>
 */
public final class OGEChrysopoeiaStationMenu extends AbstractContainerMenu {

    /** 物品栏格数（与方块实体一致）。 */
    public static final int SLOT_COUNT = OGEChrysopoeiaStationBlockEntity.SLOT_COUNT;

    /** 每行列数。 */
    public static final int COLUMNS = 9;

    /** 固定行数（27 格 = 3 行）。 */
    public static final int ROWS = 3;

    // ------------------------------------------------------------------
    // 面板布局（面板局部坐标，菜单与界面共用）
    // ------------------------------------------------------------------

    public static final int PANEL_WIDTH = 176;

    /** 物品栏第一行槽位的纵坐标。 */
    public static final int SLOTS_Y = 32;

    /** 玩家背包主区第一行的纵坐标。 */
    public static final int PLAYER_Y = 102;

    /** 快捷栏的纵坐标。 */
    public static final int HOTBAR_Y = 160;

    /** 面板总高度。 */
    public static final int PANEL_HEIGHT = HOTBAR_Y + 18 + 8;

    private final Player owner;
    private final OGEChrysopoeiaStationBlockEntity station;

    private boolean syncedOnce;
    private int lastCrv = Integer.MIN_VALUE;

    public OGEChrysopoeiaStationMenu(int containerId, Inventory playerInventory, BlockPos pos) {
        super(ModMenus.CHRYSOPOEIA_STATION.get(), containerId);
        this.owner = playerInventory.player;
        BlockEntity be = playerInventory.player.level().getBlockEntity(pos);
        this.station = be instanceof OGEChrysopoeiaStationBlockEntity chrysopoeia ? chrysopoeia : null;

        if (station != null) {
            for (int i = 0; i < SLOT_COUNT; i++) {
                addSlot(new StationSlot(station, i, 8 + (i % COLUMNS) * 18, SLOTS_Y + (i / COLUMNS) * 18));
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

    public OGEChrysopoeiaStationMenu(int containerId, Inventory playerInventory, RegistryFriendlyByteBuf buffer) {
        this(containerId, playerInventory, buffer.readBlockPos());
    }

    @Override
    public void broadcastChanges() {
        super.broadcastChanges();
        if (!(owner instanceof ServerPlayer player) || station == null) {
            return;
        }
        int crv = station.getCrv();
        if (!syncedOnce || crv != lastCrv) {
            PacketDistributor.sendToPlayer(player, new OGEChrysopoeiaSyncPayload(crv));
            syncedOnce = true;
            lastCrv = crv;
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        ItemStack result = ItemStack.EMPTY;
        Slot slot = this.slots.get(index);
        if (slot == null || !slot.hasItem() || station == null) {
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
        return station != null && station.stillValid(player);
    }

    /**
     * 炼金站槽位：把方块实体的准入规则（{@link OGEChrysopoeiaStationBlockEntity#canPlaceItem(int, ItemStack)}）
     * 显式接进原版点击管线，避免换堆、数字键交换等分支绕过服务端校验塞进普通物品。
     */
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
