package cn.origolds.ia;

import java.util.function.Supplier;

import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Containers;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.wrapper.InvWrapper;

/**
 * 金源超级漏斗（Origold Hyperhopper）的方块实体。
 *
 * <h2>容量</h2>
 * <p>自身是一个 27 格（3 行）的多类型容器，和普通小箱子一样；对外暴露标准
 * {@link IItemHandler}，原版漏斗、本模组物流管道都能直接读写。</p>
 *
 * <h2>搬运规则</h2>
 * <ul>
 *   <li><b>按间隔搬运</b>：计时器每走完一个周期（界面上的“时 : 分 : 秒”，默认 0.05 秒
 *       = 1 刻）先吸一次、再推一次；一个周期内吸/推各受“单次漏取上限”约束
 *       （默认 1）。暂停（{@link #pauseTimer()}）时整个周期停摆，缓存里的东西原地不动。</li>
 *   <li><b>吸入分流</b>：输入端那一格是容器就从容器槽位取（跳过末影箱与
 *       {@link IOGESuctionFilter} 声明不可吸的槽）；不是容器才捡该格空间里的掉落物。
 *       取料规则完全复用 {@link OGEFaceItems}。</li>
 *   <li><b>输出</b>：把体内物品塞进输出端那一格（{@link OGEFaceOutput}），
 *       目标没有容器时装不下就原地留着，下一刻再试。</li>
 *   <li><b>绑定过滤</b>：Alt+中键可把一个物品绑定为过滤条件，非空时只收同类同组件。</li>
 * </ul>
 */
public final class OGEHyperhopperBlockEntity extends BaseContainerBlockEntity implements OGEItemBindable {

    /** 格子数（与普通小箱子一致）。 */
    public static final int SLOT_COUNT = 27;

    /** 默认搬运间隔：0.05 秒 = 1 刻。 */
    public static final int DEFAULT_INTERVAL_TICKS = 1;

    /** 默认单次漏取上限。 */
    public static final int DEFAULT_MAX_PER_TRANSFER = 1;

    /** 单次漏取上限的允许最大值（远超任何容器的实际容量，只用来防溢出）。 */
    public static final int MAX_PER_TRANSFER = 1_000_000;

    private NonNullList<ItemStack> items = NonNullList.withSize(SLOT_COUNT, ItemStack.EMPTY);

    /** 对外暴露的标准物品处理视图（原版漏斗、无线物流管道等）。 */
    private final IItemHandler itemHandler = new InvWrapper(this);

    /** 目标间隔（刻），永远 >= 1。 */
    private int totalTicks = DEFAULT_INTERVAL_TICKS;

    /** 距离下次吸入还剩多少刻。 */
    private int remainingTicks = DEFAULT_INTERVAL_TICKS;

    /** 是否正在按间隔吸入。 */
    private boolean running = true;

    /** 单次漏取上限（个），永远 >= 1。 */
    private int maxPerTransfer = DEFAULT_MAX_PER_TRANSFER;

    public OGEHyperhopperBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.OGE_HYPERHOPPER.get(), pos, state);
    }

    // ------------------------------------------------------------------
    // Container 基础：固定 27 格，多类型
    // ------------------------------------------------------------------

    @Override
    public int getContainerSize() {
        return SLOT_COUNT;
    }

    @Override
    protected NonNullList<ItemStack> getItems() {
        return items;
    }

    @Override
    protected void setItems(NonNullList<ItemStack> newItems) {
        NonNullList<ItemStack> target = NonNullList.withSize(SLOT_COUNT, ItemStack.EMPTY);
        for (int i = 0; i < Math.min(newItems.size(), SLOT_COUNT); i++) {
            target.set(i, newItems.get(i));
        }
        this.items = target;
    }

    public IItemHandler getItemHandler() {
        return itemHandler;
    }

    // ------------------------------------------------------------------
    // OGEItemBindable：Alt+中键绑定过滤物品
    // ------------------------------------------------------------------

    /** Alt+中键绑定的过滤物品：非空时只吸/只收同类同组件。 */
    private final OGEBoundFilter boundFilter = new OGEBoundFilter();

    @Override
    public ItemStack getBound() {
        return this.boundFilter.get();
    }

    @Override
    public void setBound(ItemStack stack) {
        if (level != null && !level.isClientSide()) {
            if (this.boundFilter.set(stack)) {
                this.setChanged();
            }
            this.syncBoundToClients();
        }
    }

    /** 通知客户端方块实体数据有变（把绑定推给界面画幽灵）。 */
    private void syncBoundToClients() {
        if (level != null && !level.isClientSide()) {
            BlockState state = this.getBlockState();
            level.sendBlockUpdated(worldPosition, state, state, 3);
        }
    }

    /** 未绑定收一切；已绑定只收同类同组件。 */
    public boolean accepts(ItemStack stack) {
        return this.boundFilter.accepts(stack);
    }

    @Override
    public boolean canPlaceItem(int index, ItemStack stack) {
        return this.accepts(stack);
    }

    /** 绑定过滤在一切写入口兜底：被拒绝的栈直接在脚边吐出，绝不静默吞掉。 */
    @Override
    public void setItem(int index, ItemStack stack) {
        if (!stack.isEmpty() && !this.accepts(stack)) {
            if (level != null && !level.isClientSide()) {
                Containers.dropItemStack(level, worldPosition.getX() + 0.5,
                        worldPosition.getY() + 1.0, worldPosition.getZ() + 0.5, stack);
            }
            return;
        }
        super.setItem(index, stack);
    }

    // ------------------------------------------------------------------
    // 计时：与金源红石脉冲钟同款的一套接口
    // ------------------------------------------------------------------

    public int getTotalTicks() {
        return totalTicks;
    }

    public int getRemainingTicks() {
        return remainingTicks;
    }

    public boolean isRunning() {
        return running;
    }

    public int getMaxPerTransfer() {
        return maxPerTransfer;
    }

    /** 把任意刻度夹成合法间隔：至少 1 刻，最多 {@link OGEClockTime#MAX_TICKS}。 */
    public static int clampInterval(int ticks) {
        return Math.max(1, Math.min(ticks, OGEClockTime.MAX_TICKS));
    }

    /** 把任意值夹成合法的单次漏取上限：至少 1。 */
    public static int clampMaxPerTransfer(int value) {
        return Math.max(1, Math.min(value, MAX_PER_TRANSFER));
    }

    /** 设定间隔并立刻从新间隔开始计时（服务端由界面报文调用）。 */
    public void setTimer(int ticks) {
        if (level == null || level.isClientSide()) {
            return;
        }
        totalTicks = clampInterval(ticks);
        remainingTicks = totalTicks;
        running = true;
        setChanged();
    }

    /** 暂停：不再按间隔吸入，缓存里已有的东西仍会每刻往外推。 */
    public void pauseTimer() {
        if (level == null || level.isClientSide() || !running) {
            return;
        }
        running = false;
        setChanged();
    }

    /** 继续：从暂停处接着走。 */
    public void resumeTimer() {
        if (level == null || level.isClientSide() || running) {
            return;
        }
        if (remainingTicks <= 0) {
            remainingTicks = totalTicks;
        }
        running = true;
        setChanged();
    }

    /** 把剩余时间拉回上限；{@code ticks > 0} 时顺便把间隔改成它。不改变是否正在吸入。 */
    public void resetTimer(int ticks) {
        if (level == null || level.isClientSide()) {
            return;
        }
        if (ticks > 0) {
            totalTicks = clampInterval(ticks);
        }
        remainingTicks = totalTicks;
        setChanged();
    }

    /** 只把界面填的数值记下来，不改变计时状态：退出界面时调用。 */
    public void saveSettings(int ticks, int limit) {
        if (level == null || level.isClientSide()) {
            return;
        }
        boolean changed = false;
        if (ticks > 0) {
            int newTotal = clampInterval(ticks);
            if (newTotal != totalTicks) {
                totalTicks = newTotal;
                if (!running || remainingTicks > newTotal) {
                    remainingTicks = newTotal;
                }
                changed = true;
            }
        }
        if (limit > 0) {
            int newLimit = clampMaxPerTransfer(limit);
            if (newLimit != maxPerTransfer) {
                maxPerTransfer = newLimit;
                changed = true;
            }
        }
        if (changed) {
            setChanged();
        }
    }

    /** 单独修改单次漏取上限（改动立即生效，不影响计时）。 */
    public void setMaxPerTransfer(int limit) {
        if (level == null || level.isClientSide() || limit <= 0) {
            return;
        }
        int newLimit = clampMaxPerTransfer(limit);
        if (newLimit != maxPerTransfer) {
            maxPerTransfer = newLimit;
            setChanged();
        }
    }

    // ------------------------------------------------------------------
    // 每刻：推出 + 按间隔吸入
    // ------------------------------------------------------------------

    /** 服务端每刻调用一次（方块 ticker 驱动）：一个计时周期到点先吸后推。 */
    public void tick() {
        if (level == null || level.isClientSide()) {
            return;
        }
        boolean changed = false;
        if (running && totalTicks > 0) {
            if (remainingTicks > 0) {
                remainingTicks--;
            }
            if (remainingTicks <= 0) {
                changed |= pullFromInput() > 0;
                changed |= pushToOutput() > 0;
                remainingTicks = totalTicks;
            }
        }
        if (changed) {
            setChanged();
        }
    }

    /** 输出端那一格。 */
    private BlockPos outputPos() {
        return worldPosition.relative(getBlockState().getValue(OGEHyperhopperBlock.OUTPUT));
    }

    /** 输入端那一格。 */
    private BlockPos inputPos() {
        return worldPosition.relative(getBlockState().getValue(OGEHyperhopperBlock.INPUT));
    }

    /**
     * 把缓存里的物品尽可能塞进输出端那一格：一个周期内最多推
     * {@link #maxPerTransfer} 个；装不下的留在原地，下个周期再试。
     */
    private int pushToOutput() {
        BlockPos pos = outputPos();
        if (maxPerTransfer <= 0) {
            return 0;
        }
        int pushed = 0;
        for (int i = 0; i < SLOT_COUNT && pushed < maxPerTransfer; i++) {
            ItemStack stack = items.get(i);
            if (stack.isEmpty()) {
                continue;
            }
            while (!stack.isEmpty() && pushed < maxPerTransfer) {
                int want = Math.min(maxPerTransfer - pushed, stack.getCount());
                ItemStack remainder = OGEFaceOutput.insert(level, pos, stack.copyWithCount(want));
                int moved = want - remainder.getCount();
                if (moved <= 0) {
                    break;
                }
                stack.shrink(moved);
                pushed += moved;
            }
            if (stack.isEmpty()) {
                items.set(i, ItemStack.EMPTY);
            }
        }
        return pushed;
    }

    /**
     * 从输入端那一格取一次物品：该格是容器就按“单次上限”从槽位取；
     * 不是容器则把该格空间里的掉落物能捡多少捡多少（不设上限）。
     */
    private int pullFromInput() {
        BlockPos pos = inputPos();
        if (!level.isLoaded(pos)) {
            return 0;
        }
        return OGEFaceItems.containerAt(level, pos) != null
                ? pullFromContainer(pos) : pullDroppedItems(pos);
    }

    private int pullFromContainer(BlockPos pos) {
        if (maxPerTransfer <= 0) {
            return 0;
        }
        return absorb(() -> OGEFaceItems.findAt(level, pos,
                (sourcePos, stack) -> accepts(stack) && spaceFor(stack) > 0 ? stack : null),
                maxPerTransfer);
    }

    private int pullDroppedItems(BlockPos pos) {
        return absorb(() -> OGEFaceItems.findDroppedAt(level, pos,
                (sourcePos, stack) -> accepts(stack) && spaceFor(stack) > 0 ? stack : null),
                Integer.MAX_VALUE);
    }

    /** 反复调用 {@code next} 找料、取料、入仓，直到吃满预算或再也找不到。 */
    private int absorb(Supplier<OGEFaceItems.Found<ItemStack>> next, int budget) {
        int pulled = 0;
        while (pulled < budget) {
            OGEFaceItems.Found<ItemStack> found = next.get();
            if (found == null) {
                break;
            }
            int take = Math.min(budget - pulled, spaceFor(found.value()));
            if (take <= 0) {
                break;
            }
            ItemStack taken = found.take(take);
            if (taken.isEmpty()) {
                break;
            }
            int before = taken.getCount();
            insertIntoStorage(taken);
            int got = before - taken.getCount();
            if (got <= 0) {
                break; // 理论上不会发生；兜底避免死循环
            }
            pulled += got;
        }
        return pulled;
    }

    /** 本容器还能再装下多少个 {@code sample} 这种物品（含同类堆的空位与空格）。 */
    private int spaceFor(ItemStack sample) {
        int space = 0;
        int max = sample.getMaxStackSize();
        for (ItemStack slot : items) {
            if (slot.isEmpty()) {
                space += max;
            } else if (ItemStack.isSameItemSameComponents(slot, sample)) {
                space += Math.max(0, Math.min(slot.getMaxStackSize(), max) - slot.getCount());
            }
        }
        return space;
    }

    /**
     * 把 {@code stack} 尽可能塞进本容器：先补齐已有同类堆，再占用空格；
     * 方法会直接修改传入的栈，剩下的数量留在栈里由调用方处理。
     */
    private void insertIntoStorage(ItemStack stack) {
        if (!this.accepts(stack)) {
            return;
        }
        for (int i = 0; i < SLOT_COUNT && !stack.isEmpty(); i++) {
            ItemStack slot = items.get(i);
            if (slot.isEmpty() || !ItemStack.isSameItemSameComponents(slot, stack)) {
                continue;
            }
            int limit = Math.min(slot.getMaxStackSize(), stack.getMaxStackSize());
            int space = limit - slot.getCount();
            if (space <= 0) {
                continue;
            }
            int move = Math.min(space, stack.getCount());
            slot.grow(move);
            stack.shrink(move);
        }
        for (int i = 0; i < SLOT_COUNT && !stack.isEmpty(); i++) {
            if (!items.get(i).isEmpty()) {
                continue;
            }
            int move = Math.min(Math.max(1, stack.getMaxStackSize()), stack.getCount());
            items.set(i, stack.copyWithCount(move));
            stack.shrink(move);
        }
    }

    /** 比较器信号：按 27 格里占用的比例输出 0~15（与普通箱子同款）。 */
    public int getComparatorSignal() {
        int used = 0;
        for (ItemStack stack : items) {
            if (!stack.isEmpty()) {
                used++;
            }
        }
        if (used <= 0) {
            return 0;
        }
        return Math.min(15, 1 + (int) Math.floor((double) used / SLOT_COUNT * 14.0));
    }

    // ------------------------------------------------------------------
    // MenuProvider
    // ------------------------------------------------------------------

    @Override
    protected Component getDefaultName() {
        return Component.translatable(getBlockState().getBlock().getDescriptionId());
    }

    @Override
    protected AbstractContainerMenu createMenu(int containerId, Inventory playerInventory) {
        return new OGEHyperhopperMenu(containerId, playerInventory, worldPosition);
    }

    // ------------------------------------------------------------------
    // 持久化
    // ------------------------------------------------------------------

    /**
     * 方块被替换/破坏、实体即将移除时把缓存 27 格散落到地面。
     * 26.x 的 {@code Block#onRemove} 已删除，这是官方指定的后继挂点；
     * loot table 只掉方块本身，不会带着 container 组件复制一份。
     */
    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        super.preRemoveSideEffects(pos, state);
        if (this.level != null && !this.level.isClientSide()) {
            Containers.dropContents(this.level, pos, this);
        }
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.putInt("TotalTicks", totalTicks);
        output.putInt("RemainingTicks", remainingTicks);
        output.putBoolean("Running", running);
        output.putInt("MaxPerTransfer", maxPerTransfer);
        this.boundFilter.save(output);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        totalTicks = input.getInt("TotalTicks").map(v -> clampInterval(v)).orElse(DEFAULT_INTERVAL_TICKS);
        remainingTicks = input.getInt("RemainingTicks").map(v -> Math.max(0, v)).orElse(totalTicks);
        running = input.getBooleanOr("Running", true);
        maxPerTransfer = input.getInt("MaxPerTransfer").map(v -> clampMaxPerTransfer(v)).orElse(DEFAULT_MAX_PER_TRANSFER);
        this.boundFilter.load(input);
    }

    /** 同步给客户端的更新标签只带计时/设置/绑定，不搬 27 格全量。 */
    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return this.saveCustomOnly(registries);
    }

    @Override
    public net.minecraft.network.protocol.Packet<net.minecraft.network.protocol.game.ClientGamePacketListener> getUpdatePacket() {
        return net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket.create(this);
    }
}
