package cn.origolds.ia;

import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 金源能量机器方块实体。
 *
 * <p>每个游戏刻按“是否在工作”判断产电：风驱始终工作；水驱仅在接触水时工作；
 * 火驱燃烧燃料、魔驱消耗魔金之核才工作；电池不产电只储能。能量随存档持久化，
 * 并同步给客户端用于信息浮窗与原料 UI 的显示。</p>
 *
 * <p>火驱/魔驱可被“彩虹标记”点亮（{@link IRainbowMarked}）：上彩虹后产电速率
 * 按档位倍率（5x/20x/100x）放大，燃料燃烧时长相应缩短，机器表面叠一层彩虹覆层
 * （由 {@link OGERainbowOverlayRenderer} 绘制）。</p>
 */
public final class OGEMachineBlockEntity extends BaseContainerBlockEntity
        implements ContainerData, IOGEEnergyCacheHolder, IRainbowMarked {

    /** 每个“魔金之核”燃烧的刻数（100 秒 × 20 刻）。 */
    private static final int MAGIC_BURN_TICKS = 2000;

    /** 通过 {@link ContainerData} 同步给菜单 UI 的数据槽索引。 */
    private static final int DATA_STORED = 0;
    private static final int DATA_CAPACITY = 1;
    private static final int DATA_BURN_TIME = 2;
    private static final int DATA_BURN_DURATION = 3;
    private static final int DATA_WORKING = 4;
    private static final int DATA_PRODUCE_RATE = 5;
    private static final int DATA_COUNT = 6;

    private final OGEMachineKind kind;
    private final GoldSourceEnergyStorage storage;
    private NonNullList<ItemStack> items;

    /** 当前燃料/魔金之核剩余燃烧刻数。 */
    private int burnTime;
    /** 当前燃料的总燃烧刻数（用于进度显示）。 */
    private int burnDuration;

    /** 彩虹标记档位（0/1/2 = 5x/20x/100x），-1 表示未上标记。 */
    private int rainbowTier = -1;
    /** 上标记时彩虹标记物品的剩余耐久（仅展示与持久化用）。 */
    private int rainbowDurability;

    /** 输入/采样周期（刻）：净速率每秒更新一次。 */
    private static final int RATE_SAMPLE_INTERVAL_TICKS = 20;
    /** 统计窗口长度（秒）：净速率取过去 3 秒的平均值。 */
    private static final int RATE_WINDOW_SECONDS = 3;

    /**
     * 电池输入/输出（正值=净输入，负值=净输出）：过去 3 秒内获得（或失去）的 OGE
     * 的平均值（u/s），每秒更新一次。
     */
    private double netRate;
    /**
     * 每秒一次的容量读数环形缓冲：4 个读数恰好覆盖 3 秒窗口。加载时全部播种为
     * 当前读数，窗口随真实采样逐秒填满。
     */
    private final int[] rateSamples = new int[RATE_WINDOW_SECONDS + 1];
    /** 环形缓冲中最新读数的下标。 */
    private int rateSampleIndex;
    /** 已采到的真实秒级读数个数（封顶为缓冲长度）。 */
    private int rateSampleCount;
    /** 距下一次秒级采样的刻计数。 */
    private int rateSampleTimer;
    private boolean firstTick = true;

    public OGEMachineBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.OGE_MACHINE.get(), pos, state);
        OGEMachineBlock block = (OGEMachineBlock) state.getBlock();
        this.kind = block.getKind();
        this.storage = new GoldSourceEnergyStorage(block.getCapacity(), block.getProducePerSecond());
        this.items = NonNullList.withSize(1, ItemStack.EMPTY);
    }

    public void tick() {
        if (level == null || level.isClientSide()) {
            return;
        }
        int startStored = storage.getOGEStored();
        if (firstTick) {
            // 加载/首次放置：环形缓冲全部播种为当前读数，速率从 0 起逐秒收敛。
            java.util.Arrays.fill(rateSamples, startStored);
            rateSampleIndex = 0;
            rateSampleCount = 1;
            rateSampleTimer = 0;
            netRate = 0.0;
            firstTick = false;
        } else if (++rateSampleTimer >= RATE_SAMPLE_INTERVAL_TICKS) {
            // 每秒采样一次并覆盖最旧读数：净速率 = 窗口内总变化 ÷ 窗口覆盖秒数（≤3），
            // 即“过去 3 秒内获得（或失去）的 OGE 的平均值”，每秒才更新一次。
            rateSampleTimer = 0;
            rateSampleIndex = (rateSampleIndex + 1) % rateSamples.length;
            rateSamples[rateSampleIndex] = startStored;
            rateSampleCount = Math.min(rateSampleCount + 1, rateSamples.length);
            int oldest = rateSamples[(rateSampleIndex + 1) % rateSamples.length];
            int seconds = Math.min(rateSampleCount - 1, RATE_WINDOW_SECONDS);
            netRate = seconds >= 1 ? (double) (startStored - oldest) / seconds : 0.0;
            // 净速率每秒变化一次，随之标脏以便随存档持久化。
            setChanged();
        }

        // 缓存满 → 自动停机：所有发电机在本刻不再产电，火驱/魔驱同时暂停燃烧，
        // 既不推进已点燃原料的燃烧刻数、也不消耗新原料；电网把缓存抽走腾出空间后自动复机。
        boolean bufferFull = startStored >= storage.getOGECapacity();

        // 燃料/魔金之核燃烧管理（火驱、魔驱）
        if (kind.isFueled()) {
            boolean burningBefore = burnTime > 0;
            if (!bufferFull) {
                if (burnTime > 0) {
                    burnTime--;
                } else {
                    ItemStack fuel = items.get(0);
                    if (!fuel.isEmpty()) {
                        int dur = scaleBurnDuration(fuelBurnDuration(fuel));
                        if (dur > 0) {
                            fuel.shrink(1);
                            if (fuel.isEmpty()) {
                                items.set(0, ItemStack.EMPTY);
                            }
                            burnTime = dur;
                            burnDuration = dur;
                        }
                    }
                }
            }
            // 仅在“是否在燃烧”状态翻转时同步，避免每刻发包
            if (burningBefore != (burnTime > 0)) {
                setChanged();
            }
        }

        boolean working = isWorking();
        if (working) {
            // 真实产电：按有效产率（基础产率 × 彩虹档位倍率）把本刻产量写入缓存。
            storage.tickProduction(getEffectiveProduceRate());
            // 工作状态时每刻标记同步，让客户端的信息浮窗能每 tick 拿到最新数值。
            setChanged();
        }

        // 电池：存电量跨档时改写方块状态，驱动客户端换侧面贴图与液面高度。
        if (kind == OGEMachineKind.CAPACITOR) {
            updateCapacitorModel();
        }

        // 每 tick 把最新数值推送客户端。setChanged() 只标脏存档、不会发同步包，
        // 必须显式 sendBlockUpdated，信息浮窗显示的才是实时值而不是区块加载时的旧值。
        // 存储量（电池信息第二行等）因此保持每 tick 刷新；净速率则按上面的窗口每秒更新。
        // 性能：只在客户端能看到的数值真的变了时才发包（满电闲置的电池、缺燃料停机的
        // 发电机不再每刻发同步包）；另每秒强制补发一次兜底。
        syncClientIfChanged();
    }

    /**
     * 电池档位（stored/4000，封顶 14）变化时改写方块状态。
     * 仅用 {@link Block#UPDATE_CLIENTS}：不同步触发邻格更新，避免级联；
     * 同一方块实例的纯属性变化不会重建方块实体。
     */
    private void updateCapacitorModel() {
        int bucket = OGECapacitorBlock.levelFor(storage.getOGEStored());
        BlockState state = getBlockState();
        if (state.hasProperty(OGECapacitorBlock.ENERGY_LEVEL)
                && state.getValue(OGECapacitorBlock.ENERGY_LEVEL) != bucket) {
            level.setBlock(worldPosition, state.setValue(OGECapacitorBlock.ENERGY_LEVEL, bucket),
                    Block.UPDATE_CLIENTS);
        }
    }

    /** 上次推送给客户端的数值，用于 {@link #syncClientIfChanged()} 判断是否需要发包。 */
    private int lastSyncStored = Integer.MIN_VALUE;
    private double lastSyncNetRate = Double.NaN;
    private int lastSyncBurnTime = Integer.MIN_VALUE;
    private int lastSyncBurnDuration = Integer.MIN_VALUE;
    private int lastSyncFuelCount = Integer.MIN_VALUE;
    private int syncHeartbeat;

    private void syncClientIfChanged() {
        int stored = storage.getOGEStored();
        int fuelCount = items.get(0).getCount();
        boolean changed = stored != lastSyncStored
                || Double.compare(netRate, lastSyncNetRate) != 0
                || burnTime != lastSyncBurnTime
                || burnDuration != lastSyncBurnDuration
                || fuelCount != lastSyncFuelCount;
        if (!changed && ++syncHeartbeat < RATE_SAMPLE_INTERVAL_TICKS) {
            return;
        }
        syncHeartbeat = 0;
        lastSyncStored = stored;
        lastSyncNetRate = netRate;
        lastSyncBurnTime = burnTime;
        lastSyncBurnDuration = burnDuration;
        lastSyncFuelCount = fuelCount;
        syncClient();
    }

    /** 把当前存储、净速率、燃烧状态打包发给所有跟踪该区块的客户端。 */
    public void syncClient() {
        if (level != null && !level.isClientSide()) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    public OGEMachineKind getKind() {
        return kind;
    }

    public IGoldSourceEnergyStorage getOGEStorage() {
        return storage;
    }

    @Override
    public IGoldSourceEnergyStorage getOGECachedStorage() {
        return storage;
    }

    // ------------------------------------------------------------------
    // 彩虹标记（IRainbowMarked）
    // ------------------------------------------------------------------

    @Override
    public int getRainbowTier() {
        return rainbowTier;
    }

    @Override
    public int getRainbowDurability() {
        return rainbowDurability;
    }

    @Override
    public void applyRainbowMark(int tier, int durability) {
        rainbowTier = OGERainbowMarks.clampTier(tier);
        rainbowDurability = Math.max(1, durability);
        setChanged();
        syncClient();
    }

    @Override
    public void clearRainbowMark() {
        rainbowTier = -1;
        rainbowDurability = 0;
        setChanged();
        syncClient();
    }

    /** 有效产率：基础产率 × 彩虹档位倍率（未上标记时即基础产率）。 */
    public int getEffectiveProduceRate() {
        int base = storage.getOGEProduceRate();
        return rainbowTier >= 0 && base > 0 ? base * OGERainbowMarks.speedMultiplier(rainbowTier) : base;
    }

    /** 上彩虹后燃料燃烧时长按 速度×资源 倍率整体缩短（至少 1 刻）。 */
    private int scaleBurnDuration(int ticks) {
        if (rainbowTier < 0 || ticks <= 0) {
            return ticks;
        }
        return Math.max(1, ticks / (OGERainbowMarks.speedMultiplier(rainbowTier)
                * OGERainbowMarks.resourceMultiplier(rainbowTier)));
    }

    /**
     * 放置带电量（{@code origolds_ia:oge_stored} 组件）的机器物品时：
     * 把物品上保留的 OGE 注回新方块（按容量截断），并立刻刷新电池档位模型
     * （客户端液面高度）与存档。
     */
    public void applyPlacedCharge(int amount) {
        storage.setStored(amount);
        if (kind == OGEMachineKind.CAPACITOR) {
            updateCapacitorModel();
        }
        setChanged();
        syncClient();
    }

    /** 电池输入/输出：过去 3 秒内获得（或失去）的 OGE 平均值（u/s），每秒更新一次。 */
    public double getOGENetRate() {
        return netRate;
    }

    /**
     * 该机器此刻是否处于“能产电”的工作状态。客户端与服务器端都可调用：
     * 缓存已满一律视为停机（自动停机判定）；否则风驱恒为真，水驱判断是否接触水，
     * 火驱/魔驱判断是否有燃料在燃烧。
     */
    public boolean isWorking() {
        if (level == null) {
            return false;
        }
        if (storage.getOGEStored() >= storage.getOGECapacity()) {
            return false;
        }
        return switch (kind) {
            case WIND -> true;
            case WATER -> hasWaterNeighbor();
            case FIRE, MAGIC -> burnTime > 0;
            case CAPACITOR -> false;
        };
    }

    /** 当前燃料燃烧进度（0~1），无燃料时为 0。 */
    public float getBurnProgress() {
        if (burnDuration <= 0 || burnTime <= 0) {
            return 0.0f;
        }
        return (float) burnTime / (float) burnDuration;
    }

    // ------------------------------------------------------------------
    // ContainerData：把实时数值同步给菜单 UI（每 tick 刷新）
    // ------------------------------------------------------------------

    @Override
    public int get(int index) {
        return switch (index) {
            case DATA_STORED -> storage.getOGEStored();
            case DATA_CAPACITY -> storage.getOGECapacity();
            case DATA_BURN_TIME -> burnTime;
            case DATA_BURN_DURATION -> burnDuration;
            case DATA_WORKING -> isWorking() ? 1 : 0;
            case DATA_PRODUCE_RATE -> getEffectiveProduceRate();
            default -> 0;
        };
    }

    @Override
    public void set(int index, int value) {
        // 客户端收到同步数据时更新本地可显示的字段（容量与产率来自方块配置，只读）。
        switch (index) {
            case DATA_STORED -> storage.setStored(value);
            case DATA_BURN_TIME -> burnTime = value;
            case DATA_BURN_DURATION -> burnDuration = value;
            default -> {
            }
        }
    }

    @Override
    public int getCount() {
        return DATA_COUNT;
    }

    private boolean hasWaterNeighbor() {
        for (Direction direction : Direction.values()) {
            if (level.getFluidState(worldPosition.relative(direction)).is(FluidTags.WATER)) {
                return true;
            }
        }
        return false;
    }

    private int fuelBurnDuration(ItemStack stack) {
        if (kind == OGEMachineKind.MAGIC) {
            return OGEMagicCoreRef.isCore(stack) ? MAGIC_BURN_TICKS : 0;
        }
        return level.fuelValues().burnDuration(stack);
    }

    // ------------------------------------------------------------------
    // Container / MenuProvider（火驱、魔驱原料 UI）
    // ------------------------------------------------------------------

    @Override
    public int getContainerSize() {
        return items.size();
    }

    @Override
    protected NonNullList<ItemStack> getItems() {
        return items;
    }

    @Override
    protected void setItems(NonNullList<ItemStack> items) {
        this.items = items;
    }

    @Override
    public boolean canPlaceItem(int index, ItemStack stack) {
        if (index != 0) {
            return false;
        }
        return switch (kind) {
            case FIRE -> level.fuelValues().isFuel(stack);
            case MAGIC -> OGEMagicCoreRef.isCore(stack);
            default -> false;
        };
    }

    @Override
    protected Component getDefaultName() {
        OGEMachineBlock block = (OGEMachineBlock) getBlockState().getBlock();
        return Component.translatable(block.getDescriptionId());
    }

    @Override
    protected AbstractContainerMenu createMenu(int containerId, Inventory playerInventory) {
        return new OGEGeneratorMenu(containerId, playerInventory, worldPosition);
    }

    // ------------------------------------------------------------------
    // 持久化与客户端同步
    // ------------------------------------------------------------------

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.putInt("OGEStored", storage.getOGEStored());
        output.putDouble("OGEProductionAccumulator", storage.getProductionAccumulator());
        output.putDouble("OGENetRate", netRate);
        output.putInt("BurnTime", burnTime);
        output.putInt("BurnDuration", burnDuration);
        output.putInt("RainbowTier", rainbowTier);
        output.putInt("RainbowDurability", rainbowDurability);
        ContainerHelper.saveAllItems(output, items);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        storage.setStored(input.getIntOr("OGEStored", 0));
        storage.setProductionAccumulator(input.getDoubleOr("OGEProductionAccumulator", 0.0D));
        netRate = input.getDoubleOr("OGENetRate", 0.0D);
        burnTime = input.getIntOr("BurnTime", 0);
        burnDuration = input.getIntOr("BurnDuration", 0);
        rainbowTier = Math.max(-1, input.getIntOr("RainbowTier", -1));
        rainbowDurability = input.getIntOr("RainbowDurability", 0);
        this.items = NonNullList.withSize(1, ItemStack.EMPTY);
        ContainerHelper.loadAllItems(input, items);
        firstTick = true;
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return this.saveCustomOnly(registries);
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
