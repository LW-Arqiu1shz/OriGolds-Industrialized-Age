package cn.origolds.ia;

import java.util.function.Supplier;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.Containers;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
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
 * <h2>搬运规则（与原版漏斗一致）</h2>
 * <p>计时器每走完一个周期（界面上的“时 : 分 : 秒”，默认 0.05 秒 = 1 刻）触发一次搬运：</p>
 * <ul>
 *   <li><b>推出最多“单次漏取上限”个</b>：默认 1（与原版漏斗一致）。从体内 27 格依次找
 *       物品，往输出端那一格的容器送，一直送到填满上限、或 27 格都送不出去为止。
 *       送料复用 {@link OGEFaceOutput} 的模块化输出规则：这一格塞不下就换下一格，
 *       整轮都塞不下就留在缓存里等下一拍。</li>
 *   <li><b>容器按上限吸</b>：输入端那一格是容器时，按“单次漏取上限”（默认 1）一次取
 *       至多个，取料复用 {@link OGEFaceItems} 的模块化规则，末影箱一律跳过；不足上限就
 *       有多少取多少（例如上限 277、上游只有 254 个骨粉就一次取完）。</li>
 *   <li><b>掉落物一次全吸</b>：输入端那一格不是容器时，把该格空间里的掉落物
 *       <b>一次全部吸进物品栏</b>（和原版漏斗一样），不受“单次漏取上限”限制，
 *       只受自身剩余空间限制，装不下的留在原地。</li>
 * </ul>
 *
 * <p>计时暂停（{@link #pauseTimer()}）停掉整轮搬运：既不再吸料，也不再送料，
 * 缓存里的东西原地保留。</p>
 *
 * <h2>锁定接受物品</h2>
 * <p>玩家手持物品 Alt+中键可把该物品绑成唯一接受物品，空手 Alt+中键清空绑定
 * （见 {@link OGEItemBind}）。绑定只约束“以后进来的”，不影响已存在的内容，
 * 也不影响往外送：容器取料、掉落物吸取、漏斗与物流管道、界面点击一律先过
 * {@link #accepts(ItemStack)}。绑定物品与 27 格内容一起持久化，并随方块实体数据包
 * 同步给客户端，供界面在空槽里画虚影。</p>
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

    /**
     * 绑定过滤物品的共享状态组件（只存 1 个样本）；未绑定时为空栈。
     *
     * <p>玩家手持物品 Alt+中键把该物品设成唯一接受物品（见 {@link OGEItemBind}），
     * 空手 Alt+中键清空。绑定后只有这一种物品能再进来（容器取料、掉落物吸取、漏斗、
     * 物流管道、界面点击一律先过 {@link #canPlaceItem(int, ItemStack)} 与
     * {@link #accepts(ItemStack)}），
     * 里面原有的其它物品原样保留、随时可以取出，也照常往输出端送。</p>
     *
     * <p>存取、比对与读档写档都在 {@link OGEBoundFilter} 里，与虚空抽屉、引力匣共用同一份实现；
     * 本类只负责“什么时候标脏、什么时候发同步包”。</p>
     */
    private final OGEBoundFilter boundFilter = new OGEBoundFilter();

    /** 目标间隔（刻），永远 >= 1。 */
    private int totalTicks = DEFAULT_INTERVAL_TICKS;

    /** 距离下次搬运还剩多少刻。 */
    private int remainingTicks = DEFAULT_INTERVAL_TICKS;

    /** 是否正在按间隔搬运（吸料 + 送料）。 */
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
    // 绑定过滤：只接受玩家 Alt+中键指定的那一种物品
    // ------------------------------------------------------------------

    /** 当前绑定的过滤物品（1 个样本），未绑定时为空栈。 */
    @Override
    public ItemStack getBound() {
        return boundFilter.get();
    }

    /**
     * 写入绑定过滤物品；数量强制为 1（只当样本用）。传空栈表示清空绑定。
     *
     * <p>绑定只在服务端决定，客户端那份由方块实体数据包同步（见 {@link #getUpdateTag()}），
     * 好让界面的槽位准入预测与服务端一致。绑定没有变化时不重复标记脏数据，但会补发一次
     * 同步包：玩家重复按清空时能把偶然漏同步的客户端纠正回来，与虚空抽屉／引力匣同款。</p>
     */
    @Override
    public void setBound(ItemStack stack) {
        if (level != null && level.isClientSide) {
            return;
        }
        if (boundFilter.set(stack)) {
            setChanged();
        }
        syncBoundToClients();
    }

    /** 把绑定物品所在的方块实体数据推给周围客户端（客户端界面靠它画空槽虚影）。 */
    private void syncBoundToClients() {
        if (level != null && !level.isClientSide) {
            BlockState state = getBlockState();
            level.sendBlockUpdated(worldPosition, state, state, Block.UPDATE_ALL);
        }
    }

    /** 该物品能否进漏斗：空绑定一律放行，否则必须与绑定样本（含数据组件）完全一致。 */
    public boolean accepts(ItemStack stack) {
        return boundFilter.accepts(stack);
    }

    /**
     * 槽位准入：界面点击（{@code Slot#mayPlace}）、漏斗与物流管道
     * （{@code InvWrapper}）最终都汇到这里，绑定过滤由此统一生效。
     */
    @Override
    public boolean canPlaceItem(int index, ItemStack stack) {
        return accepts(stack);
    }

    /**
     * 槽位写入兜底：与绑定的过滤物品不符时掉回世界，绝不静默吞掉。
     *
     * <p>正常路径（点击、漏斗、物流管道）都会先经过 {@link #canPlaceItem(int, ItemStack)}，
     * 这里只防绕过校验的直写。</p>
     */
    @Override
    public void setItem(int index, ItemStack stack) {
        if (!stack.isEmpty() && !accepts(stack)) {
            if (level != null && !level.isClientSide) {
                Containers.dropItemStack(level,
                        worldPosition.getX() + 0.5, worldPosition.getY() + 1.0, worldPosition.getZ() + 0.5, stack);
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
        if (level == null || level.isClientSide) {
            return;
        }
        totalTicks = clampInterval(ticks);
        remainingTicks = totalTicks;
        running = true;
        setChanged();
    }

    /** 暂停：不再按间隔搬运（既不吸料也不送料）。 */
    public void pauseTimer() {
        if (level == null || level.isClientSide || !running) {
            return;
        }
        running = false;
        setChanged();
    }

    /** 继续：从暂停处接着走。 */
    public void resumeTimer() {
        if (level == null || level.isClientSide || running) {
            return;
        }
        if (remainingTicks <= 0) {
            remainingTicks = totalTicks;
        }
        running = true;
        setChanged();
    }

    /** 把剩余时间拉回上限；{@code ticks > 0} 时顺便把间隔改成它。不改变是否正在搬运。 */
    public void resetTimer(int ticks) {
        if (level == null || level.isClientSide) {
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
        if (level == null || level.isClientSide) {
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
        if (level == null || level.isClientSide || limit <= 0) {
            return;
        }
        int newLimit = clampMaxPerTransfer(limit);
        if (newLimit != maxPerTransfer) {
            maxPerTransfer = newLimit;
            setChanged();
        }
    }

    // ------------------------------------------------------------------
    // 每刻：到点搬运一次（吸料 + 送料）
    // ------------------------------------------------------------------

    /** 服务端每刻调用一次（方块 ticker 驱动）。 */
    public void tick() {
        if (level == null || level.isClientSide) {
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
     * 到点从输入端取一次物品，返回取到的数量。
     *
     * <p>输入端那一格是容器时走原来的“按上限取料”（规则完全不变）；不是容器时，
     * 把该格空间里的掉落物一次全部吸走，和原版漏斗一致。</p>
     */
    private int pullFromInput() {
        BlockPos pos = inputPos();
        if (!level.isLoaded(pos)) {
            return 0;
        }
        if (OGEFaceItems.containerAt(level, pos) != null) {
            return pullFromContainer(pos);
        }
        return pullDroppedItems(pos);
    }

    /**
     * 从输入端的容器里取一次物品：最多 {@link #maxPerTransfer} 个，且不超过自身剩余空间。
     * 取料规则（容器槽位顺序、末影箱跳过）完全交给 {@link OGEFaceItems#findAt}。
     */
    private int pullFromContainer(BlockPos pos) {
        if (maxPerTransfer <= 0) {
            return 0;
        }
        return absorb(() -> OGEFaceItems.findAt(level, pos,
                (sourcePos, stack) -> accepts(stack) && spaceFor(stack) > 0 ? stack : null), maxPerTransfer);
    }

    /**
     * 把输入端那一格空间里的掉落物<b>一次全部</b>吸进物品栏：不受 {@link #maxPerTransfer}
     * 限制，只受自身剩余空间限制；装不下的部分原样留在原地等下一次搬运。
     */
    private int pullDroppedItems(BlockPos pos) {
        return absorb(() -> OGEFaceItems.findDroppedAt(level, pos,
                (sourcePos, stack) -> accepts(stack) && spaceFor(stack) > 0 ? stack : null), Integer.MAX_VALUE);
    }

    /**
     * 反复从 {@code next} 给出的命中里取料并塞进物品栏，返回实际取到的数量。
     *
     * <p>每次找“还有空间装下”的第一种物品；装不下的种类由 matcher 返回 null 跳过。
     * {@code budget} 是这次的取料上限，传 {@link Integer#MAX_VALUE} 表示不限量
     * （掉落物一次全吸）。</p>
     */
    private int absorb(Supplier<OGEFaceItems.Found<ItemStack>> next, int budget) {
        int pulled = 0;
        while (pulled < budget) {
            OGEFaceItems.Found<ItemStack> found = next.get();
            if (found == null) {
                break;
            }
            int room = spaceFor(found.value());
            int take = Math.min(budget - pulled, room);
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

    /**
     * 到点往输出端送物品，返回实际送出的数量。
     *
     * <p>一次最多送 {@link #maxPerTransfer} 个（“单次漏取上限”，默认 1）。这个上限与
     * 从容器取料共用：界面里把它填成 64，输入端一次取 64、输出端也一次送 64，
     * 两头速度同步；填 1 时就退回原版漏斗“一次一个”的手感。</p>
     *
     * <p>输出端那一格是容器就塞进去；某一份物品塞不下（容器满或不是容器）就换下一种，
     * 27 格都送不出去就留在物品栏里等下一拍，不会掉出来。</p>
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
            // 反复往输出端送这一格的东西，直到送满本次上限或输出端塞不下。
            while (!stack.isEmpty() && pushed < maxPerTransfer) {
                int want = Math.min(maxPerTransfer - pushed, stack.getCount());
                // 只尝试“塞进容器”，塞不下就换下一种，余量不落在地上。
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
        // 绑定了过滤物品时不接受别的东西：原地不动，让物料继续留在地面／容器里。
        if (!accepts(stack)) {
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

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        ContainerHelper.saveAllItems(tag, items, registries);
        tag.putInt("TotalTicks", totalTicks);
        tag.putInt("RemainingTicks", remainingTicks);
        tag.putBoolean("Running", running);
        tag.putInt("MaxPerTransfer", maxPerTransfer);
        boundFilter.save(tag, registries);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        // loadAllItems 只覆盖“标签里出现过的格”，不会清空缺失的格；先整体清一遍，
        // 客户端收到“抽走了东西”的同步标签时才不会留下过期镜像。
        if (tag.contains(ContainerHelper.TAG_ITEMS)) {
            for (int i = 0; i < SLOT_COUNT; i++) {
                items.set(i, ItemStack.EMPTY);
            }
            ContainerHelper.loadAllItems(tag, items, registries);
        }
        totalTicks = tag.contains("TotalTicks") ? clampInterval(tag.getInt("TotalTicks")) : DEFAULT_INTERVAL_TICKS;
        remainingTicks = tag.contains("RemainingTicks") ? Math.max(0, tag.getInt("RemainingTicks")) : totalTicks;
        running = !tag.contains("Running") || tag.getBoolean("Running");
        maxPerTransfer = tag.contains("MaxPerTransfer")
                ? clampMaxPerTransfer(tag.getInt("MaxPerTransfer"))
                : DEFAULT_MAX_PER_TRANSFER;
        // 先装载物品、再读绑定，与引力匣保持同一顺序：万一将来改成逐格 setItem 装载，
        // “旧绑定”也不会把重新载入的既有内容当成非法物品扔到世界里。
        boundFilter.load(tag, registries);
    }

    /**
     * 发往客户端的方块实体数据：27 格内容 + 绑定物品。
     * 客户端界面据此判断槽位准入并绘制空槽虚影，避免“客户端预测能放、服务端却拒绝”的闪烁。
     */
    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        saveAdditional(tag, registries);
        return tag;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
