package cn.origolds.ia;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Containers;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.wrapper.InvWrapper;

import java.util.ArrayList;
import java.util.List;

/**
 * 金源引力匣（Origold Gravity Cassette）的方块实体。
 *
 * <p>它就是一个容量 27 组、允许多种物品并存的小箱子（与普通小箱子一致），
 * 额外多出两件事：</p>
 * <ul>
 *   <li><b>每刻吸取</b>：以自身为中心、边长（2×档位+1）的立方体内所有掉落物，
 *       只要箱子还有空位就会被吸进存储，吸不下的留在原地。档位由方块状态
 *       {@link OGEGravityCassetteBlock#TIER} 决定：1 档 3×3×3、2 档 5×5×5、
 *       3 档 7×7×7、4 档（默认）9×9×9；</li>
 *   <li><b>物流显示</b>：客户端在物流显示模式下绘制与当前档位一致的紫色空心外框
 *       （见 {@code OGEWirelessConnectorClient}）。</li>
 * </ul>
 *
 * <p>与虚空抽屉不同，引力匣不做单类型限制、不做自动整理、不做逐格解锁：所有格子
 * 一开始就全部可用，界面就是一个 3 行的普通容器。</p>
 *
 * <p>掉落沿用原版箱子／木桶的做法：破坏时由方块的 {@code onRemove} 调
 * {@code Containers.dropContentsOnDestroy}，把 27 格里的物品原样散落到地面，
 * 方块本身只掉自己（不带 {@code minecraft:container} 组件，避免内容物被复制一份）。</p>
 */
public final class OGEGravityCassetteBlockEntity extends BaseContainerBlockEntity implements ContainerData, OGEItemBindable {

    /** 格子数（与普通小箱子一致）。 */
    public static final int SLOT_COUNT = 27;

    /**
     * 单个引力匣每游戏刻最多处理的掉落物数量。
     *
     * <p>吸取本身是“范围内有多少吸多少”，但一片大型农场／刷石机掉落的掉落物可能有
     * 成千上万堆；再叠加多个引力匣范围互相重叠，单刻工作量会线性膨胀到拖垮服务端刻。
     * 这里给每刻处理量加一个安全上限：吸不完的留在原地，下一刻继续吸，玩家完全无感，
     * 但最坏情况下的单刻开销被钉死。</p>
     */
    private static final int MAX_SUCTION_PER_TICK = 256;

    private static final int DATA_USED_STACKS = 0;
    private static final int DATA_TOTAL_ITEMS = 1;

    private NonNullList<ItemStack> items = NonNullList.withSize(SLOT_COUNT, ItemStack.EMPTY);

    /** 对外暴露的标准物品处理视图（漏斗、无线物流管道等）。 */
    private final IItemHandler itemHandler = new InvWrapper(this);

    /** Alt+中键绑定的过滤物品：非空时只吸/只收同类同组件。 */
    private final OGEBoundFilter boundFilter = new OGEBoundFilter();

    public OGEGravityCassetteBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.OGE_GRAVITY_CASSETTE.get(), pos, state);
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
        return accepts(stack);
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
    // 每刻吸取：9×9×9 范围内的掉落物
    // ------------------------------------------------------------------

    /** 服务端每 tick 调用（方块 ticker 驱动）。 */
    public void tick() {
        if (level == null || level.isClientSide()) {
            return;
        }
        // 已经全满时一个都吸不下：直接跳过整次范围查询，别去翻 9×9×9 的掉落物。
        // 这是农场里引力匣的常态，省掉的正是最贵的一步。
        if (isFull()) {
            return;
        }
        boolean changed = false;
        int processed = 0;
        for (AABB area : suctionAreas()) {
            List<ItemEntity> entities = level.getEntitiesOfClass(ItemEntity.class, area);
            for (ItemEntity entity : entities) {
                if (processed >= MAX_SUCTION_PER_TICK) {
                    break; // 本刻到此为止，剩下的下一刻继续
                }
                if (!entity.isAlive()) {
                    continue;
                }
                ItemStack stack = entity.getItem();
                if (stack.isEmpty()) {
                    continue;
                }
                processed++;
                int before = stack.getCount();
                insertIntoStorage(stack);
                int left = stack.getCount();
                if (left == before) {
                    // 一个都放不下：留在原地，不再尝试后续格子外的逻辑。
                    continue;
                }
                changed = true;
                if (left <= 0) {
                    entity.discard();
                } else {
                    entity.setItem(stack);
                }
            }
            if (processed >= MAX_SUCTION_PER_TICK) {
                break;
            }
        }
        if (changed) {
            setChanged();
        }
    }

    /**
     * 本刻要扫描的掉落物区域：贴脸档（0 档）是六个紧邻格各自的方块框，
     * 其余档位是以自身为中心、按档位半径膨胀的一个立方体。
     */
    private List<AABB> suctionAreas() {
        BlockState state = this.getBlockState();
        if (state.getBlock() instanceof OGEGravityCassetteBlock
                && OGEGravityCassetteBlock.isNearTier(state)) {
            List<AABB> areas = new ArrayList<>(6);
            for (Direction direction : Direction.values()) {
                areas.add(new AABB(worldPosition.relative(direction)));
            }
            return areas;
        }
        return List.of(new AABB(worldPosition).inflate(suctionRadius()));
    }

    /** 当前档位对应的吸取半径（格）：1 档 3×3×3 …… 4 档 9×9×9。 */
    public int suctionRadius() {
        BlockState state = getBlockState();
        return state.getBlock() instanceof OGEGravityCassetteBlock
                ? OGEGravityCassetteBlock.suctionRadius(state)
                : OGEGravityCassetteBlock.DEFAULT_TIER;
    }

    /** 27 格是否已全部装满（满则吸取逻辑无用，可直接跳过范围查询）。 */
    private boolean isFull() {
        for (ItemStack slot : items) {
            if (slot.isEmpty() || slot.getCount() < slot.getMaxStackSize()) {
                return false;
            }
        }
        return true;
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

    // ------------------------------------------------------------------
    // 读数：供界面与比较器使用
    // ------------------------------------------------------------------

    public int usedStackCount() {
        int used = 0;
        for (ItemStack stack : items) {
            if (!stack.isEmpty()) {
                used++;
            }
        }
        return used;
    }

    public int totalItemCount() {
        int total = 0;
        for (ItemStack stack : items) {
            total += stack.getCount();
        }
        return total;
    }

    /** 比较器信号：按总格数占满的比例输出 0~15（与普通箱子同款）。 */
    public int getComparatorSignal() {
        int used = usedStackCount();
        if (used <= 0) {
            return 0;
        }
        double fraction = (double) used / SLOT_COUNT;
        return Math.min(15, 1 + (int) Math.floor(fraction * 14.0));
    }

    // ------------------------------------------------------------------
    // ContainerData（界面容量读数）与持久化
    // ------------------------------------------------------------------

    @Override
    public int getCount() {
        return 2;
    }

    @Override
    public int get(int index) {
        return switch (index) {
            case DATA_USED_STACKS -> usedStackCount();
            case DATA_TOTAL_ITEMS -> totalItemCount();
            default -> 0;
        };
    }

    @Override
    public void set(int index, int value) {
        // 单向同步（服务端 → 客户端），客户端无需回写。
    }

    @Override
    protected Component getDefaultName() {
        return Component.translatable(getBlockState().getBlock().getDescriptionId());
    }

    /**
     * 方块被替换/破坏、实体即将移除时把匣内 27 格散落到地面。
     * 26.x 的 {@code Block#onRemove} 已删除，这是官方指定的后继挂点；
     * loot table 只掉方块本身，不会带 container 组件复制一份。
     */
    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        super.preRemoveSideEffects(pos, state);
        if (this.level != null && !this.level.isClientSide()) {
            Containers.dropContents(this.level, pos, this);
        }
    }

    /** 物品走原版容器组件持久化；这里只补绑定过滤一项。 */
    @Override
    protected void saveAdditional(net.minecraft.world.level.storage.ValueOutput output) {
        super.saveAdditional(output);
        this.boundFilter.save(output);
    }

    @Override
    protected void loadAdditional(net.minecraft.world.level.storage.ValueInput input) {
        super.loadAdditional(input);
        this.boundFilter.load(input);
    }

    @Override
    public net.minecraft.network.protocol.Packet<net.minecraft.network.protocol.game.ClientGamePacketListener> getUpdatePacket() {
        return net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    protected AbstractContainerMenu createMenu(int containerId, Inventory playerInventory) {
        return new OGEGravityCassetteMenu(containerId, playerInventory, worldPosition);
    }
}
