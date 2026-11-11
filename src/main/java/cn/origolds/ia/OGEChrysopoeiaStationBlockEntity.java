package cn.origolds.ia;

import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
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
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.wrapper.InvWrapper;

/**
 * 金源炼成台的方块实体：27 格原料区 + 一个 CRV（金源质）充能池。
 *
 * <p>服务端每刻把工作区里的原料整堆折算成 CRV 吸收进池子（铜锭 64、煤 128、
 * 铁锭 256、石英 256、红石/青金石 512、钻石/绿宝石 8192、下界合金碎片 65536，
 * 见 {@link #CRV_VALUES}，池上限 {@value #CRV_MAX}）；当池中 CRV 达到
 * {@value #CRV_PER_REFINEMENT}，从金源无线电网消耗 {@value #OGE_PER_REFINEMENT} OGE，
 * 扣掉一份 CRV 并向机器正上方吐出一块金锭。</p>
 *
 * <p>空手右键打开 {@link OGEChrysopoeiaStationMenu}；CRV 进度通过
 * {@link OGEChrysopoeiaSyncPayload} 单独同步给界面。比较器按“距下一次炼成
 * 是否已充满”输出 0~15 级信号。</p>
 */
public final class OGEChrysopoeiaStationBlockEntity extends BaseContainerBlockEntity {

    /** 原料工作区格数。 */
    public static final int SLOT_COUNT = 27;

    /** 每炼成一块金锭消耗的 CRV。 */
    public static final int CRV_PER_REFINEMENT = 2048;

    /** 每炼成一块金锭消耗的无线金源能量（OGE）。 */
    public static final int OGE_PER_REFINEMENT = 10;

    /** CRV 池上限。 */
    public static final int CRV_MAX = 1000000000;

    /** 每种原料的单位 CRV 值。 */
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

    /** 原料工作区内容。 */
    private NonNullList<ItemStack> items = NonNullList.withSize(SLOT_COUNT, ItemStack.EMPTY);

    /** 对外暴露的物品能力包装（供自动化插入/抽取）。 */
    private final IItemHandler itemHandler = new InvWrapper(this);

    /** 当前 CRV 充能池。 */
    private int crv;

    public OGEChrysopoeiaStationBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.OGE_CHRYSOPOEIA_STATION.get(), pos, state);
    }

    /** 一整堆原料折算出的 CRV 值（空堆 / 非原料为 0，按 {@value #CRV_MAX} 封顶）。 */
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

    /** 单个原料的 CRV 单价（空堆 / 非原料为 0）。 */
    public static int unitCrvOf(ItemStack stack) {
        return stack.isEmpty() ? 0 : CRV_VALUES.getOrDefault(stack.getItem(), 0);
    }

    /** 是否是可吸收的原料。 */
    public boolean accepts(ItemStack stack) {
        return unitCrvOf(stack) > 0;
    }

    @Override
    public boolean canPlaceItem(int index, ItemStack stack) {
        return accepts(stack);
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

    public IItemHandler getItemHandler() {
        return this.itemHandler;
    }

    public int getCrv() {
        return this.crv;
    }

    /** 服务端每刻调用：吸收原料 → 攒满 CRV 后耗 OGE 炼成金锭。 */
    public void tick() {
        if (this.level == null || this.level.isClientSide()
                || !(this.level instanceof ServerLevel serverLevel)) {
            return;
        }
        boolean changed = refineItems();
        if (this.crv >= CRV_PER_REFINEMENT) {
            MinecraftServer server = serverLevel.getServer();
            if (server != null
                    && OGEWirelessManager.consumeOGEIfAvailable(server, serverLevel.dimension(),
                            this.worldPosition, OGE_PER_REFINEMENT) >= OGE_PER_REFINEMENT) {
                this.crv -= CRV_PER_REFINEMENT;
                OGEFaceOutput.output(serverLevel, this.worldPosition.above(), new ItemStack(Items.GOLD_INGOT));
                changed = true;
            }
        }
        if (changed) {
            setChanged();
        }
    }

    /** 把工作区里每一堆可吸收的原料整堆折算进 CRV 池，有吸收则返回 true。 */
    private boolean refineItems() {
        boolean changed = false;
        for (int i = 0; i < SLOT_COUNT; i++) {
            ItemStack stack = this.items.get(i);
            int gained = crvOf(stack);
            if (gained > 0) {
                this.crv = (int) Math.min(CRV_MAX, (long) this.crv + gained);
                this.items.set(i, ItemStack.EMPTY);
                changed = true;
            }
        }
        return changed;
    }

    /** 比较器信号：按“距下一次炼成是否已充满”输出 0~15。 */
    public int getComparatorSignal() {
        int progress = Math.min(this.crv, CRV_PER_REFINEMENT);
        return (int) Math.floor(progress / (double) CRV_PER_REFINEMENT * 15.0);
    }

    @Override
    protected Component getDefaultName() {
        return Component.translatable(getBlockState().getBlock().getDescriptionId());
    }

    @Override
    protected AbstractContainerMenu createMenu(int containerId, Inventory playerInventory) {
        return new OGEChrysopoeiaStationMenu(containerId, playerInventory, this.worldPosition);
    }

    /** 菜单存活判定：方块实体还在原位，且玩家距离不超过 8 格。 */
    @Override
    public boolean stillValid(Player player) {
        if (this.level == null || this.level.getBlockEntity(this.worldPosition) != this) {
            return false;
        }
        return player.distanceToSqr(this.worldPosition.getX() + 0.5, this.worldPosition.getY() + 0.5,
                this.worldPosition.getZ() + 0.5) <= 64.0;
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        ContainerHelper.saveAllItems(output, this.items);
        output.putInt("Crv", this.crv);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        for (int i = 0; i < SLOT_COUNT; i++) {
            this.items.set(i, ItemStack.EMPTY);
        }
        ContainerHelper.loadAllItems(input, this.items);
        this.crv = Math.max(0, Math.min(CRV_MAX, input.getIntOr("Crv", 0)));
    }
}
