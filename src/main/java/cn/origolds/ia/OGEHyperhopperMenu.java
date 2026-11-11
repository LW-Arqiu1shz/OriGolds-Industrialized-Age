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
 * 金源超级漏斗的容器菜单：上半部分是计时/上限控制区，下半部分是 27 格容量区，
 * 再下面接玩家背包。
 *
 * <p>倒计时是每刻都在变的 {@code int}，超出菜单 {@code DataSlot}（线上按 short 传输）
 * 的量程，所以不走数据槽，而是由 {@link #broadcastChanges()} 在数值变化时给打开界面的
 * 玩家单发一个 {@link OGEHyperhopperSyncPayload}（间隔、剩余刻数、是否正在吸入、
 * 单次漏取上限都在里面）。菜单刚打开时 {@code syncedOnce} 还是 false，第一次 tick
 * 会无条件下发一次初始状态。</p>
 */
public final class OGEHyperhopperMenu extends AbstractContainerMenu {

    /** 容量格数（与方块实体一致）。 */
    public static final int SLOT_COUNT = OGEHyperhopperBlockEntity.SLOT_COUNT;

    /** 每行列数。 */
    public static final int COLUMNS = 9;

    /** 固定行数（27 格 = 3 行）。 */
    public static final int ROWS = 3;

    // ------------------------------------------------------------------
    // 面板布局（面板局部坐标，菜单与界面共用）
    // ------------------------------------------------------------------

    public static final int PANEL_WIDTH = 176;

    /** 容量区第一行槽位的纵坐标。 */
    public static final int SLOTS_Y = 86;

    /** 玩家背包主区第一行的纵坐标。 */
    public static final int PLAYER_Y = 152;

    /** 快捷栏的纵坐标。 */
    public static final int HOTBAR_Y = 210;

    /** 面板总高度。 */
    public static final int PANEL_HEIGHT = HOTBAR_Y + 18 + 8;

    private final Player owner;
    private final OGEHyperhopperBlockEntity hopper;

    private boolean syncedOnce;
    private int lastTotal = Integer.MIN_VALUE;
    private int lastRemaining = Integer.MIN_VALUE;
    private boolean lastRunning;
    private int lastLimit = Integer.MIN_VALUE;

    public OGEHyperhopperMenu(int containerId, Inventory playerInventory, BlockPos pos) {
        super(ModMenus.HYPERHOPPER.get(), containerId);
        this.owner = playerInventory.player;
        BlockEntity be = owner.level().getBlockEntity(pos);
        this.hopper = be instanceof OGEHyperhopperBlockEntity hyperhopper ? hyperhopper : null;

        if (hopper != null) {
            for (int i = 0; i < SLOT_COUNT; i++) {
                addSlot(new HopperSlot(hopper, i, 8 + (i % COLUMNS) * 18, SLOTS_Y + (i / COLUMNS) * 18));
            }
        }

        // 玩家背包：紧贴容量区下方。
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

    public OGEHyperhopperMenu(int containerId, Inventory playerInventory, RegistryFriendlyByteBuf buffer) {
        this(containerId, playerInventory, buffer.readBlockPos());
    }

    /** 界面按钮 / 文本框按下后由服务端报文调用。 */
    public void applyCommand(int action, int ticks, int limit) {
        if (hopper == null) {
            return;
        }
        if (limit > 0) {
            hopper.setMaxPerTransfer(limit);
        }
        switch (action) {
            case OGEHyperhopperCommandPayload.ACTION_SET -> hopper.setTimer(ticks);
            case OGEHyperhopperCommandPayload.ACTION_PAUSE -> hopper.pauseTimer();
            case OGEHyperhopperCommandPayload.ACTION_RESUME -> {
                if (ticks > 0) {
                    hopper.saveSettings(ticks, 0);
                }
                hopper.resumeTimer();
            }
            case OGEHyperhopperCommandPayload.ACTION_RESET -> hopper.resetTimer(ticks);
            case OGEHyperhopperCommandPayload.ACTION_SAVE, OGEHyperhopperCommandPayload.ACTION_LIMIT ->
                    hopper.saveSettings(ticks, limit);
            default -> {
            }
        }
    }

    @Override
    public void broadcastChanges() {
        super.broadcastChanges();
        if (!(owner instanceof ServerPlayer player) || hopper == null) {
            return;
        }
        int total = hopper.getTotalTicks();
        int remaining = hopper.getRemainingTicks();
        boolean running = hopper.isRunning();
        int limit = hopper.getMaxPerTransfer();
        if (!syncedOnce || total != lastTotal || remaining != lastRemaining
                || running != lastRunning || limit != lastLimit) {
            PacketDistributor.sendToPlayer(player,
                    new OGEHyperhopperSyncPayload(total, remaining, running, limit));
            syncedOnce = true;
            lastTotal = total;
            lastRemaining = remaining;
            lastRunning = running;
            lastLimit = limit;
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        ItemStack result = ItemStack.EMPTY;
        Slot slot = this.slots.get(index);
        if (slot == null || !slot.hasItem() || hopper == null) {
            return result;
        }
        ItemStack stack = slot.getItem();
        result = stack.copy();
        if (index < SLOT_COUNT) {
            // 容量区 → 玩家背包 + 快捷栏
            if (!moveItemStackTo(stack, SLOT_COUNT, SLOT_COUNT + 36, true)) {
                return ItemStack.EMPTY;
            }
        } else if (!moveItemStackTo(stack, 0, SLOT_COUNT, false)) {
            // 玩家背包 → 容量区
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

    /** 本漏斗绑定的过滤物品（客户端值由方块实体更新包同步）。 */
    public ItemStack getBound() {
        return hopper != null ? hopper.getBound() : ItemStack.EMPTY;
    }

    /** 顶部容量区槽数：界面据此只在容量槽上画绑定幽灵。 */
    public int getHyperhopperSlotCount() {
        return hopper == null ? 0 : SLOT_COUNT;
    }

    @Override
    public boolean stillValid(Player player) {
        return hopper != null && hopper.stillValid(player);
    }

    /**
     * 容量区槽位：把方块实体的绑定过滤判定接进点击管线
     * （原版 {@code Slot#mayPlace} 默认恒真，不接线就会绕过过滤）。
     */
    static final class HopperSlot extends Slot {
        HopperSlot(OGEHyperhopperBlockEntity hopper, int index, int x, int y) {
            super(hopper, index, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return this.container.canPlaceItem(this.getContainerSlot(), stack);
        }
    }
}
