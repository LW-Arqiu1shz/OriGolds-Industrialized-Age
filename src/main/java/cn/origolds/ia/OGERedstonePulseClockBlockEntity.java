package cn.origolds.ia;

import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 金源红石脉冲钟（Origold Redstone-Pulse Clock）的方块实体。
 *
 * <h2>计时与脉冲</h2>
 * <p>界面点“▷”后，服务端把目标时长写进 {@link #setTimer(int)}：清零剩余刻数、
 * 进度归位到 clock0、开始倒计时。每刻递减一次剩余刻数；归零的那一瞬发出一次
 * 持续 {@link #PULSE_TICKS} 刻的 15 级红石脉冲，<b>同时立刻把剩余刻数重置回目标
 * 时长</b>，于是脉冲按周期不断重复。倒计时每走完八分之一，方块的
 * {@link OGERedstonePulseClockBlock#PROGRESS} 前进一格，四个侧面随之换表盘。</p>
 *
 * <p>脉冲的“开”与“关”都要主动通知四周一次（{@code updateNeighborsAt}），
 * 否则红石粉、红石灯、活塞这些邻居不会重新求值。信号强度由方块读这个实体
 * 的 {@link #isPulsing()} 给出，所以实体只需要在状态翻转时通知一次。</p>
 *
 * <p>界面除了设定周期，还能暂停（{@link #pauseTimer()}）、继续（{@link #resumeTimer()}）
 * 和重置（{@link #resetTimer(int)}）。暂停只让 {@link #running} 停下，剩余刻数与表盘
 * 档位留在原地，侧面的贴图同时切到“已停下”的那一套；重置把剩余刻数拉回上限但
 * 不改动 {@link #running}。玩家填了时长没点按钮就退出界面时，{@link #saveTimer(int)}
 * 只把周期记下来，同样不动 {@link #running}。</p>
 *
 * <h2>界面同步</h2>
 * <p>倒计时是每刻都在变的 {@code int}，超出了菜单 {@code DataSlot}（short）的量程，
 * 所以打开界面的玩家由 {@link OGEClockMenu#broadcastChanges()} 通过
 * {@link OGEClockSyncPayload} 单独同步，不占用数据槽。</p>
 */
public final class OGERedstonePulseClockBlockEntity extends BlockEntity implements MenuProvider {

    /** 一次脉冲持续的游戏刻数。 */
    public static final int PULSE_TICKS = 2;

    /** 目标周期（刻），0 表示未计时。 */
    private int totalTicks;

    /** 距离下次脉冲还剩多少刻。 */
    private int remainingTicks;

    /** 是否正在计时。 */
    private boolean running;

    /** 当前脉冲还剩多少刻没走完，0 表示没在发脉冲。 */
    private int pulseTicks;

    public OGERedstonePulseClockBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.OGE_REDSTONE_PULSE_CLOCK.get(), pos, state);
    }

    public int getTotalTicks() {
        return totalTicks;
    }

    public int getRemainingTicks() {
        return remainingTicks;
    }

    public boolean isRunning() {
        return running;
    }

    /** 是否正在输出红石脉冲。 */
    public boolean isPulsing() {
        return pulseTicks > 0;
    }

    /**
     * 设定脉冲周期：正数开始（或重新开始）计时，0 或负数停止计时并熄灭信号。
     * 服务端根据界面传来的刻数调用，越界在这里夹住。
     */
    public void setTimer(int ticks) {
        if (level == null || level.isClientSide()) {
            return;
        }
        boolean signalChanged = false;
        if (ticks <= 0) {
            if (pulseTicks > 0) {
                signalChanged = true;
            }
            running = false;
            totalTicks = 0;
            remainingTicks = 0;
            pulseTicks = 0;
        } else {
            totalTicks = Math.min(ticks, OGEClockTime.MAX_TICKS);
            remainingTicks = totalTicks;
            running = true;
        }
        if (signalChanged) {
            level.updateNeighborsAt(worldPosition, getBlockState().getBlock());
        }
        updateProgress();
        setChanged();
    }

    /**
     * 暂停计时：时间不再流逝，但剩余刻数、进度档位都留在原地，表盘换成“已停下”那套贴图。
     * 未设置周期或本来就没在走时什么都不做。
     */
    public void pauseTimer() {
        if (level == null || level.isClientSide() || !running) {
            return;
        }
        running = false;
        updateProgress();
        setChanged();
    }

    /**
     * 继续计时：从暂停处接着走。若剩余刻数已经不正常（不大于 0），则回到一个完整周期。
     * 没有设置周期时什么都不做。
     */
    public void resumeTimer() {
        if (level == null || level.isClientSide() || running || totalTicks <= 0) {
            return;
        }
        if (remainingTicks <= 0) {
            remainingTicks = totalTicks;
        }
        running = true;
        updateProgress();
        setChanged();
    }

    /**
     * 把剩余时间重置回上限：{@code ticks > 0} 时以它作为新的上限（界面上三个输入框的
     * 内容），否则沿用原上限；剩余刻数、进度都归位到起点。
     * <b>不改变时间是否流逝</b>：原本在走的继续走，原本停着的仍然停着。
     */
    public void resetTimer(int ticks) {
        if (level == null || level.isClientSide()) {
            return;
        }
        int newTotal = ticks > 0 ? Math.min(ticks, OGEClockTime.MAX_TICKS) : totalTicks;
        if (newTotal <= 0) {
            return;
        }
        totalTicks = newTotal;
        remainingTicks = newTotal;
        updateProgress();
        setChanged();
    }

    /**
     * 只把界面上填的时长记下来，不改动计时状态：玩家在界面里输了时间、没点“▷”就退出了
     * 方块时调用，下次打开界面就能看到刚填的值。
     *
     * <p>{@code ticks <= 0} 时什么都不做——清空输入框不该把已经设好的周期抹掉；正数则更新
     * 周期上限：未在计时时把剩余刻数也拉满（重开后显示为一个完整的、暂停中的周期），
     * 正在计时时保留当前倒计时不被重置，只在剩余刻数超过新上限时夹一下。</p>
     */
    public void saveTimer(int ticks) {
        if (level == null || level.isClientSide() || ticks <= 0) {
            return;
        }
        int newTotal = Math.min(ticks, OGEClockTime.MAX_TICKS);
        if (newTotal == totalTicks) {
            return;
        }
        totalTicks = newTotal;
        if (!running || remainingTicks > newTotal) {
            remainingTicks = newTotal;
        }
        updateProgress();
        setChanged();
    }

    /** 服务端每刻调用一次：倒计时、到点发脉冲并立刻重新计时、切换表盘。 */
    public void tick() {
        if (level == null || level.isClientSide()) {
            return;
        }
        boolean changed = false;
        boolean signalChanged = false;

        // 先在上一刻的脉冲里走一格；走完就熄火并通知四周。
        if (pulseTicks > 0) {
            pulseTicks--;
            changed = true;
            if (pulseTicks == 0) {
                signalChanged = true;
            }
        }

        if (running && totalTicks > 0) {
            if (remainingTicks > 0) {
                remainingTicks--;
                changed = true;
            }
            if (remainingTicks <= 0) {
                // 到点：发一次脉冲，并立刻重新开始计时。
                pulseTicks = PULSE_TICKS;
                remainingTicks = totalTicks;
                changed = true;
                signalChanged = true;
            }
        }

        if (signalChanged) {
            level.updateNeighborsAt(worldPosition, getBlockState().getBlock());
        }
        if (changed) {
            updateProgress();
            setChanged();
        }
    }

    /** 把当前进度与“是否正在流逝”写进方块状态（只在值真的变了时才写）。 */
    private void updateProgress() {
        if (level == null) {
            return;
        }
        // 档位只看“已经走了多少”，暂停时也停在原来的档位上，不会跳回 clock0。
        int index = 0;
        if (totalTicks > 0) {
            long elapsed = (long) totalTicks - remainingTicks;
            index = (int) (elapsed * OGERedstonePulseClockBlock.PROGRESS_STEPS / totalTicks);
            if (index < 0) {
                index = 0;
            }
            if (index >= OGERedstonePulseClockBlock.PROGRESS_STEPS) {
                index = OGERedstonePulseClockBlock.PROGRESS_STEPS - 1;
            }
        }
        BlockState state = getBlockState();
        BlockState updated = state;
        if (updated.hasProperty(OGERedstonePulseClockBlock.PROGRESS)
                && updated.getValue(OGERedstonePulseClockBlock.PROGRESS) != index) {
            updated = updated.setValue(OGERedstonePulseClockBlock.PROGRESS, index);
        }
        if (updated.hasProperty(OGERedstonePulseClockBlock.RUNNING)
                && updated.getValue(OGERedstonePulseClockBlock.RUNNING) != running) {
            updated = updated.setValue(OGERedstonePulseClockBlock.RUNNING, running);
        }
        if (updated != state) {
            level.setBlock(worldPosition, updated, Block.UPDATE_CLIENTS);
        }
    }

    // ------------------------------------------------------------------
    // MenuProvider
    // ------------------------------------------------------------------

    @Override
    public Component getDisplayName() {
        return Component.translatable(getBlockState().getBlock().getDescriptionId());
    }

    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player player) {
        return new OGEClockMenu(containerId, playerInventory, worldPosition);
    }

    /** 菜单存活判定：方块还在、玩家离得不太远（8 格以内）。 */
    public boolean stillValid(Player player) {
        if (level == null || level.getBlockEntity(worldPosition) != this) {
            return false;
        }
        return player.distanceToSqr(worldPosition.getX() + 0.5, worldPosition.getY() + 0.5,
                worldPosition.getZ() + 0.5) <= 64.0;
    }

    // ------------------------------------------------------------------
    // 持久化
    // ------------------------------------------------------------------

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.putInt("TotalTicks", totalTicks);
        output.putInt("RemainingTicks", remainingTicks);
        output.putBoolean("Running", running);
        output.putInt("PulseTicks", pulseTicks);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        totalTicks = input.getIntOr("TotalTicks", 0);
        remainingTicks = input.getIntOr("RemainingTicks", 0);
        running = input.getBooleanOr("Running", false);
        pulseTicks = input.getIntOr("PulseTicks", 0);
    }
}
