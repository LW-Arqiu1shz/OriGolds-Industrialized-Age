package cn.origolds.ia;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 金源红石脉冲钟的容器菜单：没有物品槽，只负责把方块实体和打开界面的玩家牵起来。
 *
 * <p>倒计时是每刻都在变的 {@code int}，超过菜单 {@code DataSlot}（线上按 short 传输）
 * 的量程，所以不走数据槽，而是由 {@link #broadcastChanges()} 在数值变化时给打开界面的
 * 玩家单发一个 {@link OGEClockSyncPayload}。菜单刚打开时 {@code syncedOnce} 还是
 * false，第一次 tick 会无条件下发一次初始状态。</p>
 */
public final class OGEClockMenu extends AbstractContainerMenu {

    /** 界面宽度（刚好放下三个输入框与“ : ”分隔符）。 */
    public static final int PANEL_WIDTH = 176;

    /** 界面高度（顶部按钮 + 一行输入框 + 一行倒计时）。 */
    public static final int PANEL_HEIGHT = 76;

    private final Player owner;
    private final OGERedstonePulseClockBlockEntity clock;

    private boolean syncedOnce;
    private int lastTotal = Integer.MIN_VALUE;
    private int lastRemaining = Integer.MIN_VALUE;
    private boolean lastRunning;

    public OGEClockMenu(int containerId, Inventory playerInventory, BlockPos pos) {
        super(ModMenus.REDSTONE_PULSE_CLOCK.get(), containerId);
        this.owner = playerInventory.player;
        this.clock = owner.level().getBlockEntity(pos) instanceof OGERedstonePulseClockBlockEntity be ? be : null;
    }

    public OGEClockMenu(int containerId, Inventory playerInventory, RegistryFriendlyByteBuf buffer) {
        this(containerId, playerInventory, buffer.readBlockPos());
    }

    /** 界面按钮按下后由服务端报文调用：按动作分发到方块实体。 */
    public void applyCommand(int action, int ticks) {
        if (clock == null) {
            return;
        }
        switch (action) {
            case OGEClockCommandPayload.ACTION_SET -> clock.setTimer(ticks);
            case OGEClockCommandPayload.ACTION_PAUSE -> clock.pauseTimer();
            case OGEClockCommandPayload.ACTION_RESUME -> clock.resumeTimer();
            case OGEClockCommandPayload.ACTION_RESET -> clock.resetTimer(ticks);
            case OGEClockCommandPayload.ACTION_SAVE -> clock.saveTimer(ticks);
            default -> {
            }
        }
    }

    @Override
    public void broadcastChanges() {
        super.broadcastChanges();
        if (!(owner instanceof ServerPlayer player) || clock == null) {
            return;
        }
        int total = clock.getTotalTicks();
        int remaining = clock.getRemainingTicks();
        boolean running = clock.isRunning();
        if (!syncedOnce || total != lastTotal || remaining != lastRemaining || running != lastRunning) {
            PacketDistributor.sendToPlayer(player, new OGEClockSyncPayload(total, remaining, running));
            syncedOnce = true;
            lastTotal = total;
            lastRemaining = remaining;
            lastRunning = running;
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        return clock != null && clock.stillValid(player);
    }
}
