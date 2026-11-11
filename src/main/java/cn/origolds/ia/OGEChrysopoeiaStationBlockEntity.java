package cn.origolds.ia;

import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.wrapper.InvWrapper;

/**
 * 金源炼金站（Origold Chrysopoeia Station）的方块实体。
 *
 * <h2>容量</h2>
 * <p>自身是一个 27 格（3 行）的物品栏，对外暴露标准 {@link IItemHandler}，
 * 原版漏斗、本模组无线物流管道都能直接往里塞料；右键打开界面也能手动放入。</p>
 *
 * <h2>炼金凝造价量（CRV）</h2>
 * <p>物品栏只接受“拥有 CRV 的物品”（见 {@link #crvOf(ItemStack)}），
 * 每刻把格子里所有可炼金的物品<b>销毁</b>，按 {@code 单个 CRV × 数量} 累加进
 * {@link #getCrv() 炼金凝造价量}。CRV 只增不减，直到用来兑换金锭。</p>
 *
 * <h2>点石成金</h2>
 * <p>只要 CRV 达到 {@value #CRV_PER_REFINEMENT}，每刻尝试一次兑换：从所在无线电网
 * 原子扣除 {@value #OGE_PER_REFINEMENT}u OGE，成功则扣掉 2048 CRV，并在方块顶部
 * 冒出 1 个金锭（顶上放箱子就自动进箱子）；电不够则这一次什么都不做，CRV 原样保留，
 * 等下一拍再试。每刻最多兑换一次，避免一口气刷出成百上千个金锭。</p>
 *
 * <h2>CRV 数值表</h2>
 * <ul>
 *   <li>铜锭 64、铁锭 256、煤炭 128、红石 512、青金石 512、石英 256；</li>
 *   <li>钻石 8192、绿宝石 8192；</li>
 *   <li>下界合金碎片 65536。</li>
 * </ul>
 */
public final class OGEChrysopoeiaStationBlockEntity extends BaseContainerBlockEntity {

    /** 物品栏格数（与普通小箱子一致）。 */
    public static final int SLOT_COUNT = 27;

    /** 兑换 1 个金锭需要的 CRV。 */
    public static final int CRV_PER_REFINEMENT = 2048;

    /** 兑换 1 个金锭消耗的 OGE。 */
    public static final int OGE_PER_REFINEMENT = 10;

    /** CRV 上限：只是防溢出，正常玩法远远到不了。 */
    public static final int CRV_MAX = 1_000_000_000;

    /** 拥有炼金凝造价量的物品与其单件 CRV。 */
    private static final Map<Item, Integer> CRV_VALUES = Map.ofEntries(
            Map.entry(Items.COPPER_INGOT, 64),
            Map.entry(Items.COAL, 128),
            Map.entry(Items.LAPIS_LAZULI, 512),
            Map.entry(Items.IRON_INGOT, 256),
            Map.entry(Items.DIAMOND, 8192),
            Map.entry(Items.EMERALD, 8192),
            Map.entry(Items.REDSTONE, 512),
            Map.entry(Items.QUARTZ, 256),
            Map.entry(Items.NETHERITE_SCRAP, 65536));

    private NonNullList<ItemStack> items = NonNullList.withSize(SLOT_COUNT, ItemStack.EMPTY);

    /** 对外暴露的标准物品处理视图（原版漏斗、无线物流管道等）。 */
    private final IItemHandler itemHandler = new InvWrapper(this);

    /** 已累积的炼金凝造价量。 */
    private int crv;

    public OGEChrysopoeiaStationBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.OGE_CHRYSOPOEIA_STATION.get(), pos, state);
    }

    /**
     * 一个物品堆能提供的炼金凝造价量（单件 CRV × 数量）；不受支持的物品返回 0。
     */
    public static int crvOf(ItemStack stack) {
        if (stack.isEmpty()) {
            return 0;
        }
        Integer unit = CRV_VALUES.get(stack.getItem());
        if (unit == null) {
            return 0;
        }
        long total = (long) unit * stack.getCount();
        return total > CRV_MAX ? CRV_MAX : (int) total;
    }

    /** 单件物品的 CRV；不支持的物品返回 0。 */
    public static int unitCrvOf(ItemStack stack) {
        return stack.isEmpty() ? 0 : CRV_VALUES.getOrDefault(stack.getItem(), 0);
    }

    /** 该物品能否进炼金站：只有拥有 CRV 的物品才接受。 */
    public boolean accepts(ItemStack stack) {
        return unitCrvOf(stack) > 0;
    }

    /** 槽位准入：界面点击、漏斗与物流管道最终都汇到这里。 */
    @Override
    public boolean canPlaceItem(int index, ItemStack stack) {
        return accepts(stack);
    }

    // ------------------------------------------------------------------
    // Container 基础：固定 27 格，只接受可炼金物品
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

    /** 当前炼金凝造价量。 */
    public int getCrv() {
        return crv;
    }

    // ------------------------------------------------------------------
    // 每刻：销毁可炼金物品 → 累加 CRV → 到量就换金锭
    // ------------------------------------------------------------------

    /** 服务端每刻调用一次（方块 ticker 驱动）。 */
    public void tick() {
        if (level == null || level.isClientSide || !(level instanceof ServerLevel serverLevel)) {
            return;
        }
        boolean changed = refineItems();
        if (crv >= CRV_PER_REFINEMENT) {
            MinecraftServer server = serverLevel.getServer();
            if (server != null && OGEWirelessManager.consumeOGEIfAvailable(
                    server, serverLevel.dimension(), worldPosition, OGE_PER_REFINEMENT) >= OGE_PER_REFINEMENT) {
                crv -= CRV_PER_REFINEMENT;
                // 产物从顶部冒出：顶上放箱子就自动进箱子，否则掉在顶部。
                OGEFaceOutput.output(serverLevel, worldPosition.above(), new ItemStack(Items.GOLD_INGOT));
                changed = true;
            }
        }
        if (changed) {
            setChanged();
        }
    }

    /** 把 27 格里所有可炼金物品销毁并折算成 CRV，返回是否有物品被消耗。 */
    private boolean refineItems() {
        boolean changed = false;
        for (int i = 0; i < SLOT_COUNT; i++) {
            ItemStack stack = items.get(i);
            int gained = crvOf(stack);
            if (gained <= 0) {
                continue;
            }
            crv = (int) Math.min(CRV_MAX, (long) crv + gained);
            items.set(i, ItemStack.EMPTY);
            changed = true;
        }
        return changed;
    }

    /** 比较器信号：按当前一轮兑换进度输出 0~15。 */
    public int getComparatorSignal() {
        int progress = Math.min(crv, CRV_PER_REFINEMENT);
        return (int) Math.floor((double) progress / CRV_PER_REFINEMENT * 15.0);
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
        return new OGEChrysopoeiaStationMenu(containerId, playerInventory, worldPosition);
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
    // 持久化
    // ------------------------------------------------------------------

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        ContainerHelper.saveAllItems(tag, items, registries);
        tag.putInt("Crv", crv);
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
        crv = Math.max(0, Math.min(CRV_MAX, tag.getInt("Crv")));
    }
}
