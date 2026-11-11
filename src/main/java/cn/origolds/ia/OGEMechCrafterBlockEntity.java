package cn.origolds.ia;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CrafterBlock;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.wrapper.InvWrapper;

/**
 * 金源自动合成机（Origold Mech-Crafter）的方块实体。
 *
 * <h2>它的配方不在合成格子里，而是“锁”出来的</h2>
 * <p>界面上方是 9 个<b>只做显示</b>的合成格：玩家拿着物品左键一个格子，就把那个格子
 * 锁定成这件物品（物品不会被消耗，仍留在鼠标上）；右键已锁定的格子则清除锁定。
 * 下方 27 格存储被拆成 <b>9 个竖着的 3 格</b>，从左到右第 1~9 列依次对应合成格里
 * 从左到右、从上到下的 9 个格位（第 7 列就是合成格最左下角那格）。</p>
 *
 * <h2>准入与弹出</h2>
 * <p>存储只接受“本列锁定的物品”（按物品 ID 比较，见 {@link #canPlaceItem(int, ItemStack)}）。
 * 只要锁定发生变化，27 格里所有 ID 或摆放位置不再符合的库存都会立刻从顶部（输出端）
 * 弹出去——比如把钻石镐配方换成钻石剑，原来摆钻石、木棍的列就会被清空，空出来的列
 * 则按新配方接受新物品。</p>
 *
 * <h2>自动合成</h2>
 * <p>每刻检查一次：先给每个锁定格从<b>整个 27 格库存</b>里挑一件材料（不要求材料待在它
 * 对应的那一列），再用这些<b>实际材料</b>去查原版合成配方（与工作台 / 原版合成器一致，
 * 特殊配方也能合）；查到时原子扣除 {@value #OGE_PER_CRAFT}u OGE，消耗这些材料，并把
 * 合成产物和配方剩余物（空桶等）从顶部（输出端）送出。电不够就什么都不做，等下一拍再试；每刻最多合成 1 个，不会一口气刷出
 * 成百上千个。</p>
 *
 * <p>材料判定的粒度是"物品种类"：例如钻石镐锁定后需要 3 件钻石 + 2 件木棍，那么只要
 * 27 格里任意位置合计有 3 件钻石、2 件木棍就能合成——3 件钻石都堆在第 1 列的格子里也行，
 * 两根木棍分散在第 5、第 8 列也行。消耗时同样跨列取料：每个锁定格消耗 1 件同种材料，
 * 从库存里从前往后取。</p>
 */
public final class OGEMechCrafterBlockEntity extends BaseContainerBlockEntity {

    /** 存储格数（9 列 × 3 行）。 */
    public static final int SLOT_COUNT = 27;

    /** 每列的行数：27 格分成 9 个竖着的 3 格。 */
    public static final int ROWS_PER_COLUMN = 3;

    /** 合成格边长：3×3。 */
    public static final int GRID_SIZE = 3;

    /** 合成格数量：9 个。 */
    public static final int CELL_COUNT = GRID_SIZE * GRID_SIZE;

    /** 合成 1 次消耗的 OGE。 */
    public static final int OGE_PER_CRAFT = 1;

    private NonNullList<ItemStack> items = NonNullList.withSize(SLOT_COUNT, ItemStack.EMPTY);

    /** 9 个合成格锁定的物品；空堆表示该格没锁。数量恒为 1。 */
    private NonNullList<ItemStack> locks = NonNullList.withSize(CELL_COUNT, ItemStack.EMPTY);

    /** 锁定矩阵当前能合成的产物（顶部浮空展品）；空堆表示没锁或没配方。仅服务端重算，客户端靠同步包。 */
    private ItemStack previewResult = ItemStack.EMPTY;

    /** 读档后预览需要在服务端重算一次（不存档）。 */
    private boolean previewStale = true;

    /** 对外暴露的标准物品处理视图（原版漏斗、无线物流管道等）。 */
    private final IItemHandler itemHandler = new InvWrapper(this);

    public OGEMechCrafterBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.OGE_MECH_CRAFTER.get(), pos, state);
    }

    public IItemHandler getItemHandler() {
        return itemHandler;
    }

    /** 存储槽下标 → 它所属的合成格（也就是第几列）。 */
    public static int cellOfSlot(int slot) {
        return slot / ROWS_PER_COLUMN;
    }

    /** 合成格 → 它对应列的存储槽起点。 */
    public static int firstSlotOfCell(int cell) {
        return cell * ROWS_PER_COLUMN;
    }

    /** 读取某个合成格锁定的物品；空堆表示未锁定。 */
    public ItemStack getLock(int cell) {
        return cell >= 0 && cell < CELL_COUNT ? locks.get(cell) : ItemStack.EMPTY;
    }

    /** 只读的 9 个锁定格快照（供渲染器/界面读取）。 */
    public List<ItemStack> getLocks() {
        return List.copyOf(locks);
    }

    // ------------------------------------------------------------------
    // 准入：每一列只接受它锁定的物品
    // ------------------------------------------------------------------

    /** 某个存储槽能不能放这种物品：本列必须锁定着同一种物品（按 ID 比较）。 */
    public boolean acceptsInto(int slot, ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        ItemStack lock = locks.get(cellOfSlot(slot));
        return !lock.isEmpty() && ItemStack.isSameItem(lock, stack);
    }

    @Override
    public boolean canPlaceItem(int index, ItemStack stack) {
        return acceptsInto(index, stack);
    }

    // ------------------------------------------------------------------
    // 锁定 / 清除 / 弹出
    // ------------------------------------------------------------------

    /**
     * 设置或清除一个合成格的锁定。锁定发生变化时，立刻把 27 格里不再符合的库存从顶部
     * 弹出去，并让客户端重新拿到这份方块数据（前脸上的 9 个格子要跟着变）。
     */
    public void setLock(int cell, ItemStack stack) {
        if (cell < 0 || cell >= CELL_COUNT) {
            return;
        }
        ItemStack next = stack.isEmpty() ? ItemStack.EMPTY : stack.copyWithCount(1);
        if (ItemStack.isSameItemSameComponents(next, locks.get(cell))) {
            return;
        }
        locks.set(cell, next);
        ejectInvalidItems();
        refreshPreview();
        setChanged();
        syncToClients();
    }

    /** 把 27 格里 ID 或摆放位置不再符合当前锁定的库存从顶部弹出去。 */
    private void ejectInvalidItems() {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        for (int i = 0; i < SLOT_COUNT; i++) {
            ItemStack stack = items.get(i);
            if (stack.isEmpty()) {
                continue;
            }
            ItemStack lock = locks.get(cellOfSlot(i));
            if (lock.isEmpty() || !ItemStack.isSameItem(lock, stack)) {
                OGEFaceOutput.output(serverLevel, worldPosition.above(), stack.copy());
                items.set(i, ItemStack.EMPTY);
            }
        }
    }

    private void syncToClients() {
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    // ------------------------------------------------------------------
    // 每刻：配方有效且材料齐 → 扣电 → 消耗材料 → 顶部出产物
    // ------------------------------------------------------------------

    public void tick() {
        if (level == null || level.isClientSide || !(level instanceof ServerLevel serverLevel)) {
            return;
        }
        // 读档后（含没存预览的旧存档）补算一次产物预览，顶上的浮空展品才有东西可显示。
        if (previewStale) {
            previewStale = false;
            ItemStack before = previewResult;
            refreshPreview();
            if (!ItemStack.matches(before, previewResult)) {
                setChanged();
                syncToClients();
            }
        }
        if (tryCraft(serverLevel)) {
            setChanged();
        }
    }

    /**
     * 尝试合成一次。返回是否真的合成了（用于 {@code setChanged}）。
     */
    /**
     * 尝试合成一次。返回是否真的合成了（用于 {@code setChanged}）。
     *
     * <p>和原版工作台 / 合成器一致：配方按<b>实际要吃掉的那几件材料</b>（带它们自己的组件，
     * 例如染料、受损耐久、地图编号）去匹配和组装，而不是按锁定的样品、也不是取配方的
     * “固定产物”。这样染色皮甲、烟花火箭、复制地图、旗帜图案、修复工具这类“产物取决于
     * 输入”的特殊配方也能合成。合成后剩下的容器类物品（蛋糕的空桶、蜂蜜瓶的空瓶等）
     * 同样从顶部送出，不会凭空消失。</p>
     */
    private boolean tryCraft(ServerLevel serverLevel) {
        if (!hasAnyLock()) {
            return false;
        }
        // 先看材料（便宜），凑不齐就不必查配方。材料不要求待在它对应的那一列。
        int[] sources = pickSources();
        if (sources == null) {
            return false;
        }
        List<ItemStack> grid = new ArrayList<>(CELL_COUNT);
        for (int cell = 0; cell < CELL_COUNT; cell++) {
            grid.add(sources[cell] < 0 ? ItemStack.EMPTY : items.get(sources[cell]).copyWithCount(1));
        }
        CraftingInput input = CraftingInput.of(GRID_SIZE, GRID_SIZE, grid);
        Optional<RecipeHolder<CraftingRecipe>> recipe = findRecipe(serverLevel, input);
        if (recipe.isEmpty()) {
            return false;
        }
        ItemStack result = recipe.get().value().assemble(input, serverLevel.registryAccess());
        if (result.isEmpty()) {
            return false;
        }
        MinecraftServer server = serverLevel.getServer();
        if (server == null) {
            return false;
        }
        if (OGEWirelessManager.consumeOGEIfAvailable(
                server, serverLevel.dimension(), worldPosition, OGE_PER_CRAFT) < OGE_PER_CRAFT) {
            return false;
        }
        NonNullList<ItemStack> remaining = recipe.get().value().getRemainingItems(input);
        for (int cell = 0; cell < CELL_COUNT; cell++) {
            if (sources[cell] >= 0) {
                ItemStack stack = items.get(sources[cell]);
                stack.shrink(1);
                if (stack.isEmpty()) {
                    items.set(sources[cell], ItemStack.EMPTY);
                }
            }
        }
        result.onCraftedBySystem(serverLevel);
        // 产物与剩余物从顶部输出端送出：顶上放箱子就自动进箱子，否则掉在顶部。
        BlockPos out = worldPosition.above();
        OGEFaceOutput.output(serverLevel, out, result);
        for (ItemStack rest : remaining) {
            if (!rest.isEmpty()) {
                OGEFaceOutput.output(serverLevel, out, rest.copy());
            }
        }
        return true;
    }

    /**
     * 给每个锁定格挑一件实际要吃掉的材料：先在本列的 3 格里找，找不到再跨列找。
     * 同一格被多个锁定格共用时按数量预留，不会重复计数。任何一个锁定格凑不到材料就返回 null。
     */
    private int[] pickSources() {
        int[] reserved = new int[SLOT_COUNT];
        int[] sources = new int[CELL_COUNT];
        for (int cell = 0; cell < CELL_COUNT; cell++) {
            ItemStack lock = locks.get(cell);
            if (lock.isEmpty()) {
                sources[cell] = -1;
                continue;
            }
            int slot = -1;
            int first = firstSlotOfCell(cell);
            for (int s = first; s < first + ROWS_PER_COLUMN && slot < 0; s++) {
                if (hasSpare(s, lock, reserved)) {
                    slot = s;
                }
            }
            for (int s = 0; s < SLOT_COUNT && slot < 0; s++) {
                if (hasSpare(s, lock, reserved)) {
                    slot = s;
                }
            }
            if (slot < 0) {
                return null;
            }
            reserved[slot]++;
            sources[cell] = slot;
        }
        return sources;
    }

    private boolean hasSpare(int slot, ItemStack lock, int[] reserved) {
        ItemStack stack = items.get(slot);
        return !stack.isEmpty() && ItemStack.isSameItem(stack, lock) && stack.getCount() > reserved[slot];
    }

    private boolean hasAnyLock() {
        for (int cell = 0; cell < CELL_COUNT; cell++) {
            if (!locks.get(cell).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** 锁定矩阵拼成 3×3 合成输入（未锁的格为空）。 */
    private CraftingInput lockedCraftingInput() {
        List<ItemStack> grid = new ArrayList<>(CELL_COUNT);
        for (int cell = 0; cell < CELL_COUNT; cell++) {
            ItemStack lock = locks.get(cell);
            grid.add(lock.isEmpty() ? ItemStack.EMPTY : lock);
        }
        return CraftingInput.of(GRID_SIZE, GRID_SIZE, grid);
    }

    /** 把 9 个锁定格当作合成表去查配方；查不到返回 empty。 */
    public Optional<RecipeHolder<CraftingRecipe>> findRecipe(ServerLevel serverLevel) {
        if (!hasAnyLock()) {
            return Optional.empty();
        }
        return findRecipe(serverLevel, lockedCraftingInput());
    }

    /**
     * 配方查询入口：走原版合成器（{@link CrafterBlock}）自带的配方缓存，
     * 同样的输入不会每刻都去全表匹配；数据包重载后缓存自动失效。
     */
    private static Optional<RecipeHolder<CraftingRecipe>> findRecipe(ServerLevel serverLevel, CraftingInput input) {
        return CrafterBlock.getPotentialResults(serverLevel, input);
    }

    // ------------------------------------------------------------------
    // 顶部浮空产物预览（服务端算好，随方块数据同步）
    // ------------------------------------------------------------------

    /** 当前锁定矩阵的合成产物预览；空堆表示没锁或没有配方。 */
    public ItemStack getPreviewResult() {
        return previewResult;
    }

    /** 锁定矩阵变了就重算产物预览，随同步包发给客户端的浮空展品。 */
    private void refreshPreview() {
        ItemStack result = ItemStack.EMPTY;
        if (level instanceof ServerLevel serverLevel) {
            Optional<RecipeHolder<CraftingRecipe>> recipe = findRecipe(serverLevel);
            if (recipe.isPresent()) {
                result = recipe.get().value().assemble(lockedCraftingInput(), serverLevel.registryAccess());
            }
        }
        previewResult = result.isEmpty() ? ItemStack.EMPTY : result.copy();
    }

    /** 比较器信号：按存储栏的填充比例输出 0~15，和普通箱子一致。 */
    public int getComparatorSignal() {
        int nonEmpty = 0;
        for (int i = 0; i < SLOT_COUNT; i++) {
            if (!items.get(i).isEmpty()) {
                nonEmpty++;
            }
        }
        return (int) Math.floor((double) nonEmpty / SLOT_COUNT * 15.0);
    }

    // ------------------------------------------------------------------
    // Container 基础
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

    // ------------------------------------------------------------------
    // MenuProvider
    // ------------------------------------------------------------------

    @Override
    protected Component getDefaultName() {
        return Component.translatable(getBlockState().getBlock().getDescriptionId());
    }

    @Override
    protected AbstractContainerMenu createMenu(int containerId, Inventory playerInventory) {
        return new OGEMechCrafterMenu(containerId, playerInventory, worldPosition);
    }

    @Override
    public boolean stillValid(Player player) {
        if (level == null || level.getBlockEntity(worldPosition) != this) {
            return false;
        }
        return player.distanceToSqr(worldPosition.getX() + 0.5, worldPosition.getY() + 0.5,
                worldPosition.getZ() + 0.5) <= 64.0;
    }

    // ------------------------------------------------------------------
    // 持久化与客户端同步（前脸 9 个格子需要这份数据）
    // ------------------------------------------------------------------

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        ContainerHelper.saveAllItems(tag, items, registries);
        CompoundTag lockTag = new CompoundTag();
        ContainerHelper.saveAllItems(lockTag, locks, registries);
        tag.put("Locks", lockTag);
        if (!previewResult.isEmpty()) {
            tag.put("Preview", previewResult.save(registries));
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        // 先整体清一遍，避免标签里缺失的格留着旧镜像。
        if (tag.contains(ContainerHelper.TAG_ITEMS)) {
            for (int i = 0; i < SLOT_COUNT; i++) {
                items.set(i, ItemStack.EMPTY);
            }
            ContainerHelper.loadAllItems(tag, items, registries);
        }
        for (int i = 0; i < CELL_COUNT; i++) {
            locks.set(i, ItemStack.EMPTY);
        }
        if (tag.contains("Locks")) {
            ContainerHelper.loadAllItems(tag.getCompound("Locks"), locks, registries);
        }
        previewResult = tag.contains("Preview")
                ? ItemStack.parseOptional(registries, tag.getCompound("Preview"))
                : ItemStack.EMPTY;
        previewStale = true;
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveWithoutMetadata(registries);
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
