package cn.origolds.ia;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup.Provider;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.Containers;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CrafterBlock;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.wrapper.InvWrapper;

/**
 * 金源机械合成台的方块实体：3×3 自动合成机器。
 *
 * <h2>锁定与合成</h2>
 * <p>机器有两份状态：27 格<b>储料仓</b>（标准 {@code Container}）和 9 格
 * <b>锁定矩阵</b>。玩家在界面上把合成格的每一格锁定成一种物品（只记种类，
 * 数量恒为 1）；储料仓里每个格子按"所属矩阵格"（{@code slot / 3} 一行三格
 * 共用同一锁定）校验放行——没锁定或种类不符一律拒收。</p>
 *
 * <p>服务端每 tick 先给每个锁定格挑一件实际材料（不要求待在对应那一列），凑齐后用这些
 * <b>实际材料</b>查 3×3 合成配方（与原版工作台 / 合成器一致，特殊配方也能合）；查到就从
 * 无线电网扣 {@value #OGE_PER_CRAFT} 点 OGE，吃掉这些材料，把产物和配方剩余物（空桶等）
 * 从顶面输出（{@link OGEFaceOutput}）。扣不到电本轮什么都不发生。</p>
 *
 * <h2>产物预览的同步</h2>
 * <p>26.x 客户端没有全量配方管理器，界面"结果格"和方块顶上的浮空展品
 * （{@link OGEMechCrafterRenderer}）拿不到本模组内部的配方查询，
 * 因此产物预览改由服务端在锁定变化时算好存进 {@link #previewResult}，
 * 随方块实体的常规同步包（{@link #getUpdateTag}）发给客户端。</p>
 *
 * <h2>破坏散料</h2>
 * <p>26.x 删掉了 {@code Block#onRemove}，"破坏时把储料仓散落到地面"的老行为
 * 挪到 {@link #preRemoveSideEffects}：方块被替换、实体移除前把 27 格内容
 * 原样掉落（本方块 loot table 只掉方块本身，不会带着 container 组件复制一份）。</p>
 */
public final class OGEMechCrafterBlockEntity extends BaseContainerBlockEntity {

    /** 储料仓总格数（9 个矩阵格 × 每格 3 个纵向仓格）。 */
    public static final int SLOT_COUNT = 27;
    /** 每个矩阵格对应的储料仓格数（同一格的三格放同一种物品）。 */
    public static final int ROWS_PER_COLUMN = 3;
    /** 合成矩阵边长。 */
    public static final int GRID_SIZE = 3;
    /** 合成矩阵总格数。 */
    public static final int CELL_COUNT = 9;
    /** 每次自动合成消耗的 OGE。 */
    public static final int OGE_PER_CRAFT = 1;

    /** 锁定矩阵的存档键（嵌套子标签，内部仍是标准 Items 列表）。 */
    private static final String KEY_LOCKS = "Locks";
    /** 产物预览的存档键（服务端算好的当前锁定矩阵合成结果）。 */
    private static final String KEY_PREVIEW = "Preview";

    private NonNullList<ItemStack> items = NonNullList.withSize(SLOT_COUNT, ItemStack.EMPTY);
    private NonNullList<ItemStack> locks = NonNullList.withSize(CELL_COUNT, ItemStack.EMPTY);
    /** 锁定矩阵当前能合成的产物（数量已按配方取）；空栈表示没锁全/没配方。仅服务端重算，客户端靠同步包。 */
    private ItemStack previewResult = ItemStack.EMPTY;
    /** 读档后预览需要在服务端重算一次（不存档）。 */
    private boolean previewStale = true;
    /** 供能力/管道按传统接口读写的物品视图。 */
    private final IItemHandler itemHandler = new InvWrapper(this);

    public OGEMechCrafterBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.OGE_MECH_CRAFTER.get(), pos, state);
    }

    /** 传统物品处理器视图（漏斗/物流管道能力接线用）。 */
    public IItemHandler getItemHandler() {
        return this.itemHandler;
    }

    /** 储料仓格号 → 所属矩阵格号（每 3 个连续仓格服务一个矩阵格）。 */
    public static int cellOfSlot(int slot) {
        return slot / ROWS_PER_COLUMN;
    }

    /** 矩阵格号 → 该格第一个储料仓格号。 */
    public static int firstSlotOfCell(int cell) {
        return cell * ROWS_PER_COLUMN;
    }

    /** 某个矩阵格的锁定物品（种类原型）；越界返回空栈。 */
    public ItemStack getLock(int cell) {
        return cell >= 0 && cell < CELL_COUNT ? this.locks.get(cell) : ItemStack.EMPTY;
    }

    /** 九个矩阵格锁定的快照。 */
    public List<ItemStack> getLocks() {
        return List.copyOf(this.locks);
    }

    /** 当前锁定矩阵的合成产物预览；空栈表示没锁全或没有配方。 */
    public ItemStack getPreviewResult() {
        return this.previewResult;
    }

    /** 这件物品能否放进某个储料仓格：所属矩阵格已锁定且种类一致。 */
    public boolean acceptsInto(int slot, ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        ItemStack lock = this.locks.get(cellOfSlot(slot));
        return !lock.isEmpty() && ItemStack.isSameItem(lock, stack);
    }

    @Override
    public boolean canPlaceItem(int index, ItemStack stack) {
        return this.acceptsInto(index, stack);
    }

    /**
     * 设置某个矩阵格的锁定（仅服务端，由界面点锁报文经菜单调用）。
     * 锁定变化后把仓里已不符锁定的物品原样弹出到顶面，并刷新产物预览、同步客户端。
     */
    public void setLock(int cell, ItemStack stack) {
        if (cell < 0 || cell >= CELL_COUNT) {
            return;
        }
        ItemStack next = stack.isEmpty() ? ItemStack.EMPTY : stack.copyWithCount(1);
        if (!ItemStack.isSameItemSameComponents(next, this.locks.get(cell))) {
            this.locks.set(cell, next);
            this.ejectInvalidItems();
            this.refreshPreview();
            this.setChanged();
            this.syncToClients();
        }
    }

    /** 把与当前锁定不符的仓内物品逐件弹出到机器顶面。 */
    private void ejectInvalidItems() {
        if (this.level instanceof ServerLevel serverLevel) {
            for (int slot = 0; slot < SLOT_COUNT; slot++) {
                ItemStack stack = this.items.get(slot);
                if (stack.isEmpty()) {
                    continue;
                }
                ItemStack lock = this.locks.get(cellOfSlot(slot));
                if (lock.isEmpty() || !ItemStack.isSameItem(lock, stack)) {
                    OGEFaceOutput.output(serverLevel, this.worldPosition.above(), stack.copy());
                    this.items.set(slot, ItemStack.EMPTY);
                }
            }
        }
    }

    /** 通知周围与客户端本方块实体数据已变化（锁定、预览随更新包下发）。 */
    private void syncToClients() {
        if (this.level != null && !this.level.isClientSide()) {
            this.level.sendBlockUpdated(this.worldPosition, this.getBlockState(), this.getBlockState(),
                    Block.UPDATE_CLIENTS);
        }
    }

    // ------------------------------------------------------------------
    // 合成循环
    // ------------------------------------------------------------------

    /** 服务端每 tick 调用：按锁定矩阵尝试合成一次。 */
    public void tick() {
        if (this.level == null || this.level.isClientSide() || !(this.level instanceof ServerLevel serverLevel)) {
            return;
        }
        // 读档后（含没存预览的旧存档）补算一次产物预览，顶上的浮空展品才有东西可显示。
        if (this.previewStale) {
            this.previewStale = false;
            ItemStack before = this.previewResult;
            this.refreshPreview();
            if (!ItemStack.isSameItemSameComponents(before, this.previewResult)
                    || before.getCount() != this.previewResult.getCount()) {
                this.setChanged();
                this.syncToClients();
            }
        }
        if (this.tryCraft(serverLevel)) {
            this.setChanged();
        }
    }

    /**
     * 尝试合成一次：材料凑齐、查到配方、电扣得到，三关全过才吃料产出。
     * 返回是否真的合成了一次。
     *
     * <p>和原版工作台 / 合成器一致：配方按<b>实际要吃掉的那几件材料</b>（带它们自己的组件，
     * 例如染料、受损耐久、地图编号）去匹配和组装，而不是按锁定的样品。这样染色皮甲、
     * 烟花火箭、复制地图、旗帜图案、修复工具这类“产物取决于输入”的特殊配方也能合成，
     * 而且产物与真正消耗的材料一一对应。合成后剩下的容器类物品（蛋糕的空桶、
     * 蜂蜜瓶的空瓶等）同样从顶面送出，不会凭空消失。</p>
     */
    private boolean tryCraft(ServerLevel serverLevel) {
        if (!this.hasAnyLock()) {
            return false;
        }
        // 先看材料（便宜），凑不齐就不必查配方。
        int[] sources = this.pickSources();
        if (sources == null) {
            return false;
        }
        List<ItemStack> grid = new ArrayList<>(CELL_COUNT);
        for (int cell = 0; cell < CELL_COUNT; cell++) {
            grid.add(sources[cell] < 0 ? ItemStack.EMPTY : this.items.get(sources[cell]).copyWithCount(1));
        }
        CraftingInput input = CraftingInput.of(GRID_SIZE, GRID_SIZE, grid);
        Optional<RecipeHolder<CraftingRecipe>> recipe = findRecipe(serverLevel, input);
        if (recipe.isEmpty()) {
            return false;
        }
        ItemStack result = recipe.get().value().assemble(input);
        if (result.isEmpty()) {
            return false;
        }
        MinecraftServer server = serverLevel.getServer();
        if (server == null) {
            return false;
        }
        if (OGEWirelessManager.consumeOGEIfAvailable(server, serverLevel.dimension(), this.worldPosition,
                OGE_PER_CRAFT) < OGE_PER_CRAFT) {
            return false;
        }
        NonNullList<ItemStack> remaining = recipe.get().value().getRemainingItems(input);
        for (int cell = 0; cell < CELL_COUNT; cell++) {
            if (sources[cell] >= 0) {
                ItemStack stack = this.items.get(sources[cell]);
                stack.shrink(1);
                if (stack.isEmpty()) {
                    this.items.set(sources[cell], ItemStack.EMPTY);
                }
            }
        }
        result.onCraftedBySystem(serverLevel);
        BlockPos out = this.worldPosition.above();
        OGEFaceOutput.output(serverLevel, out, result);
        for (ItemStack rest : remaining) {
            if (!rest.isEmpty()) {
                OGEFaceOutput.output(serverLevel, out, rest.copy());
            }
        }
        return true;
    }

    /**
     * 给每个锁定格挑一件实际要吃掉的材料：先在本列的 3 格里找，找不到再跨列找
     * （沿用“材料不要求待在对应那一列”的规则）。同一格被多个锁定格共用时按数量预留，
     * 不会重复计数。任何一个锁定格凑不到材料就返回 null。
     */
    private int[] pickSources() {
        int[] reserved = new int[SLOT_COUNT];
        int[] sources = new int[CELL_COUNT];
        for (int cell = 0; cell < CELL_COUNT; cell++) {
            ItemStack lock = this.locks.get(cell);
            if (lock.isEmpty()) {
                sources[cell] = -1;
                continue;
            }
            int slot = -1;
            int first = firstSlotOfCell(cell);
            for (int s = first; s < first + ROWS_PER_COLUMN && slot < 0; s++) {
                if (this.hasSpare(s, lock, reserved)) {
                    slot = s;
                }
            }
            for (int s = 0; s < SLOT_COUNT && slot < 0; s++) {
                if (this.hasSpare(s, lock, reserved)) {
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
        ItemStack stack = this.items.get(slot);
        return !stack.isEmpty() && ItemStack.isSameItem(stack, lock) && stack.getCount() > reserved[slot];
    }

    /** 锁定矩阵拼成 3×3 合成输入（未锁的格为空）。 */
    private CraftingInput lockedCraftingInput() {
        List<ItemStack> grid = new ArrayList<>(CELL_COUNT);
        for (int cell = 0; cell < CELL_COUNT; cell++) {
            ItemStack lock = this.locks.get(cell);
            grid.add(lock.isEmpty() ? ItemStack.EMPTY : lock);
        }
        return CraftingInput.of(GRID_SIZE, GRID_SIZE, grid);
    }

    /** 九个矩阵格里是否至少有一个已锁定。 */
    private boolean hasAnyLock() {
        for (int cell = 0; cell < CELL_COUNT; cell++) {
            if (!this.locks.get(cell).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** 用锁定矩阵查 3×3 合成配方；一格都没锁直接返回空。 */
    public Optional<RecipeHolder<CraftingRecipe>> findRecipe(ServerLevel serverLevel) {
        if (!this.hasAnyLock()) {
            return Optional.empty();
        }
        return findRecipe(serverLevel, this.lockedCraftingInput());
    }

    /**
     * 配方查询入口：走原版合成器（{@link CrafterBlock}）自带的配方缓存，
     * 同样的输入不会每刻都去全表匹配；数据包重载后缓存自动失效。
     */
    private static Optional<RecipeHolder<CraftingRecipe>> findRecipe(ServerLevel serverLevel, CraftingInput input) {
        return CrafterBlock.getPotentialResults(serverLevel, input);
    }

    /** 服务端：锁定矩阵变了就重算产物预览，随更新包同步给界面与浮空展品。 */
    private void refreshPreview() {
        ItemStack result = ItemStack.EMPTY;
        if (this.level instanceof ServerLevel serverLevel) {
            Optional<RecipeHolder<CraftingRecipe>> recipe = this.findRecipe(serverLevel);
            if (recipe.isPresent()) {
                result = recipe.get().value().assemble(this.lockedCraftingInput());
            }
        }
        this.previewResult = result.isEmpty() ? ItemStack.EMPTY : result.copy();
    }

    // ------------------------------------------------------------------
    // 读数与容器实现
    // ------------------------------------------------------------------

    /** 比较器信号：按已用格数占 27 格的比例输出 0~15。 */
    public int getComparatorSignal() {
        int nonEmpty = 0;
        for (int i = 0; i < SLOT_COUNT; i++) {
            if (!this.items.get(i).isEmpty()) {
                nonEmpty++;
            }
        }
        return (int) Math.floor(nonEmpty / (double) SLOT_COUNT * 15.0);
    }

    @Override
    public int getContainerSize() {
        return SLOT_COUNT;
    }

    @Override
    protected NonNullList<ItemStack> getItems() {
        return this.items;
    }

    @Override
    protected void setItems(NonNullList<ItemStack> newItems) {
        NonNullList<ItemStack> target = NonNullList.withSize(SLOT_COUNT, ItemStack.EMPTY);
        for (int i = 0; i < Math.min(newItems.size(), SLOT_COUNT); i++) {
            target.set(i, newItems.get(i));
        }
        this.items = target;
    }

    @Override
    protected Component getDefaultName() {
        return Component.translatable(this.getBlockState().getBlock().getDescriptionId());
    }

    @Override
    protected AbstractContainerMenu createMenu(int containerId, Inventory playerInventory) {
        return new OGEMechCrafterMenu(containerId, playerInventory, this.worldPosition);
    }

    /** 菜单存活判定：方块还在原处、玩家距离不超过 8 格（沿老版本的平方距离 64）。 */
    @Override
    public boolean stillValid(Player player) {
        if (this.level == null || this.level.getBlockEntity(this.worldPosition) != this) {
            return false;
        }
        return player.distanceToSqr(this.worldPosition.getX() + 0.5, this.worldPosition.getY() + 0.5,
                this.worldPosition.getZ() + 0.5) <= 64.0;
    }

    /**
     * 方块被替换/破坏、实体即将移除时把储料仓 27 格散落到地面。
     * 26.x 的 {@code Block#onRemove} 已删除，这是官方指定的后继挂点。
     */
    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        super.preRemoveSideEffects(pos, state);
        if (this.level != null && !this.level.isClientSide()) {
            Containers.dropContents(this.level, pos, this);
        }
    }

    // ------------------------------------------------------------------
    // 持久化
    // ------------------------------------------------------------------

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        ContainerHelper.saveAllItems(output, this.items);
        ContainerHelper.saveAllItems(output.child(KEY_LOCKS), this.locks);
        if (this.previewResult.isEmpty()) {
            output.discard(KEY_PREVIEW);
        } else {
            output.store(KEY_PREVIEW, ItemStack.OPTIONAL_CODEC, this.previewResult);
        }
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        this.items = NonNullList.withSize(SLOT_COUNT, ItemStack.EMPTY);
        ContainerHelper.loadAllItems(input, this.items);
        this.locks = NonNullList.withSize(CELL_COUNT, ItemStack.EMPTY);
        ContainerHelper.loadAllItems(input.childOrEmpty(KEY_LOCKS), this.locks);
        this.previewResult = input.read(KEY_PREVIEW, ItemStack.OPTIONAL_CODEC).orElse(ItemStack.EMPTY);
        this.previewStale = true;
    }

    /** 更新包带上全部实体数据：客户端界面（锁定、预览）与浮空展品都靠它刷新。 */
    @Override
    public CompoundTag getUpdateTag(Provider registries) {
        return this.saveWithoutMetadata(registries);
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
