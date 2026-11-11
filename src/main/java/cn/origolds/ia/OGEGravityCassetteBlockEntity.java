package cn.origolds.ia;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.Containers;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
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
 *   <li><b>每刻吸取</b>：默认以自身为中心、边长（2×档位+1）的立方体内所有掉落物；
 *       最低一档（{@link OGEGravityCassetteBlock#TIER_NEAR}，0 档）不是立方体，只吸
 *       与自身 6 个面直接相邻的方块里的掉落物。只要箱子还有空位就会被吸进存储，
 *       吸不下的留在原地。档位由方块状态 {@link OGEGravityCassetteBlock#TIER} 决定：
 *       0 档相邻方块、1 档 3×3×3、2 档 5×5×5、3 档 7×7×7、4 档（默认）9×9×9；</li>
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

    /**
     * 绑定过滤物品的共享状态组件（只存 1 个样本）；未绑定时为空栈。
     *
     * <p>玩家手持物品 Alt+中键把该物品设成唯一接受物品（见 {@link OGEItemBind}），
     * 空手 Alt+中键清空。绑定后只有这一种物品能再进来（吸取、漏斗、物流管道、界面点击
     * 一律先过 {@link #canPlaceItem(int, ItemStack)}），匣里原有的其它物品原样保留、
     * 随时可以取出。</p>
     *
     * <p>存取、比对与读档写档都在 {@link OGEBoundFilter} 里，与虚空抽屉、超级漏斗共用同一份实现；
     * 本类只负责“什么时候标脏、什么时候发同步包”。</p>
     */
    private final OGEBoundFilter boundFilter = new OGEBoundFilter();

    /** 对外暴露的标准物品处理视图（漏斗、无线物流管道等）。 */
    private final IItemHandler itemHandler = new InvWrapper(this);

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
     * 同步包：玩家重复按清空时能把偶然漏同步的客户端纠正回来，虚空抽屉那边同理。</p>
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

    /** 该物品能否进匣：空绑定一律放行，否则必须与绑定样本（含数据组件）完全一致。 */
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
     * 槽位写入兜底：绑定的过滤物品不符时掉回世界，绝不静默吞掉。
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
    // 每刻吸取：相邻档只收 6 个面的邻居，其余档收半径内的立方体
    // ------------------------------------------------------------------

    /** 服务端每 tick 调用（方块 ticker 驱动）。 */
    public void tick() {
        if (level == null || level.isClientSide) {
            return;
        }
        // 已经全满时一个都吸不下：直接跳过整次范围查询，别去翻 9×9×9 的掉落物。
        // 这是农场里引力匣的常态，省掉的正是最贵的一步。
        if (isFull()) {
            return;
        }
        boolean changed = false;
        int processed = 0;
        // 相邻档会给出 6 个各自独立的方块盒子，同一个掉落物可能同时落在两个盒子的
        // 交界上而被看两次；第二次进来时它要么已经被 discard、要么已经少了数量，
        // 下面的 isAlive / isEmpty 判断会自然跳过或只补差额，不会重复吸。
        suction:
        for (AABB area : suctionAreas()) {
            List<ItemEntity> entities = level.getEntitiesOfClass(ItemEntity.class, area);
            for (ItemEntity entity : entities) {
                if (processed >= MAX_SUCTION_PER_TICK) {
                    break suction; // 本刻到此为止，剩下的下一刻继续
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
        }
        if (changed) {
            setChanged();
        }
    }

    /**
     * 本刻要扫描的范围。
     *
     * <p>立方体档只有一个大盒子：以自身为中心、边长 2r+1 格（含自己所在的这一格，
     * 掉落物几乎不可能停在实心方块内部，多算一格没有影响）。相邻档则换成 6 个面各自
     * 的方块盒子，斜角与更远的位置完全不在其中——这正是“只吸取与 6 个面相邻方块中的
     * 物品”的字面含义。</p>
     */
    private List<AABB> suctionAreas() {
        if (isNearTier()) {
            Direction[] directions = Direction.values();
            List<AABB> areas = new ArrayList<>(directions.length);
            for (Direction direction : directions) {
                areas.add(new AABB(worldPosition.relative(direction)));
            }
            return areas;
        }
        return List.of(new AABB(worldPosition).inflate(suctionRadius()));
    }

    /** 当前档位是否是“相邻方块”档（0 档）。 */
    private boolean isNearTier() {
        BlockState state = getBlockState();
        return state.getBlock() instanceof OGEGravityCassetteBlock && OGEGravityCassetteBlock.isNearTier(state);
    }

    /** 当前档位对应的吸取半径（格）：1 档 3×3×3 …… 4 档 9×9×9；相邻档为 0。 */
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
        // 绑定了过滤物品时不接受别的东西：原地不动，让掉落物继续留在地面。
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

    // ------------------------------------------------------------------
    // 持久化与客户端同步：匣内 27 格 + 绑定物品
    // ------------------------------------------------------------------

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        ContainerHelper.saveAllItems(tag, items, registries);
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
        boundFilter.load(tag, registries);
    }

    /**
     * 发往客户端的方块实体数据：格子内容 + 绑定物品。
     * 客户端界面据此判断槽位准入，避免“客户端预测能放、服务端却拒绝”的闪烁。
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

    @Override
    protected Component getDefaultName() {
        return Component.translatable(getBlockState().getBlock().getDescriptionId());
    }

    @Override
    protected AbstractContainerMenu createMenu(int containerId, Inventory playerInventory) {
        return new OGEGravityCassetteMenu(containerId, playerInventory, worldPosition);
    }
}
