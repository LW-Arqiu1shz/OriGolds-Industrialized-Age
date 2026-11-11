package cn.origolds.ia;

import java.util.Optional;

import javax.annotation.Nullable;

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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.wrapper.InvWrapper;

/**
 * 金源超级熔炉（Origold Hyperfurnace）的方块实体。
 *
 * <p>自身是一个 27 格（3 行）的物品栏，对外暴露标准 {@link IItemHandler}，原版漏斗、
 * 本模组无线物流管道都能直接往里送料；右键打开界面也能手动放入。与旧版不同，本机
 * <b>不再从四个水平侧面与底面取料</b>，只吃自己这 27 格里的东西。</p>
 *
 * <p>服务器端按档位节奏尝试烧炼：1x 档每 20 tick、5x 档每 4 tick、20x 档每刻各执行
 * 一次，每次只烧 1 个：</p>
 * <ol>
 *   <li>在 27 格里找到第一份“能被原版熔炼配方烧出东西”的物料；</li>
 *   <li>按当前档位从所在无线电网（电池优先）原子扣除每个物品的电费
 *       （1x 档 2u、5x 档 4u、20x 档 8u），电不够则本刻什么都不做、也不取料；</li>
 *   <li>取走 1 个输入，用原版熔炼配方算出产物，从炉顶的出料口送出（顶上放箱子则自动
 *       进箱子，否则掉落在炉顶）。</li>
 * </ol>
 *
 * <p>产物仍然统一从顶部出料，不会被 27 格输入栏影响。烧炼产物取的是原版熔炼结果，
 * 不额外发放经验。</p>
 */
public final class OGEHyperfurnaceBlockEntity extends BaseContainerBlockEntity {

    /** 物品栏格数（与普通小箱子一致）。 */
    public static final int SLOT_COUNT = 27;

    /** 每行列数。 */
    public static final int COLUMNS = 9;

    /** 行数（27 格 = 3 行）。 */
    public static final int ROWS = 3;

    private NonNullList<ItemStack> items = NonNullList.withSize(SLOT_COUNT, ItemStack.EMPTY);

    /** 对外暴露的标准物品处理视图（原版漏斗、无线物流管道等）。 */
    private final IItemHandler itemHandler = new InvWrapper(this);

    public OGEHyperfurnaceBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.OGE_HYPERFURNACE.get(), pos, state);
    }

    public IItemHandler getItemHandler() {
        return itemHandler;
    }

    // ------------------------------------------------------------------
    // Container 基础：固定 27 格
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
    // 每刻：从自己 27 格里取料 → 扣电 → 顶部出产物
    // ------------------------------------------------------------------

    public void tick() {
        if (level == null || level.isClientSide) {
            return;
        }
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        if (!(getBlockState().getBlock() instanceof OGEHyperfurnaceBlock)) {
            return;
        }
        // 按档位节奏节拍：20x 档每刻都跑，1x 档每 20 刻才跑一次。
        int mode = getBlockState().getValue(OGEHyperfurnaceBlock.MODE);
        int interval = OGEHyperfurnaceBlock.ticksPerItem(mode);
        if (serverLevel.getGameTime() % interval != 0) {
            return;
        }

        MinecraftServer server = serverLevel.getServer();
        if (server == null) {
            return;
        }

        // 在 27 格里找第一份可熔炼的原料。
        int inputSlot = findSmeltableSlot(serverLevel);
        if (inputSlot < 0) {
            return;
        }

        // 有料但没电：本刻什么都不做、不取料，保持停机。
        int cost = OGEHyperfurnaceBlock.costPerItem(mode);
        int consumed = OGEWirelessManager.consumeOGEIfAvailable(
                server, serverLevel.dimension(), worldPosition, cost);
        if (consumed < cost) {
            return;
        }

        ItemStack input = items.get(inputSlot);
        ItemStack result = planSmelting(serverLevel, input);
        input.shrink(1);
        // 产物送到炉顶的出料口：顶上放箱子就自动进箱子，否则掉在炉顶。
        OGEFaceOutput.output(serverLevel, worldPosition.above(),
                result == null ? ItemStack.EMPTY : result.copy());
        setChanged();
    }

    /** 在 27 格里找第一份有原版熔炼配方的物料下标；没有则返回 -1。 */
    private int findSmeltableSlot(ServerLevel serverLevel) {
        for (int i = 0; i < SLOT_COUNT; i++) {
            if (planSmelting(serverLevel, items.get(i)) != null) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 判定一个输入物品能否熔炼，并给出产物。返回 {@code null} 表示该物品没有原版
     * 熔炼配方。纯计算，不扣能量、不改动世界状态。
     *
     * <p>{@code getResultItem} 返回的是配方内部持有的物品堆，这里不交给世界，由调用方
     * {@code copy()} 一份再投放，避免改到配方本身。</p>
     */
    @Nullable
    private static ItemStack planSmelting(ServerLevel level, ItemStack input) {
        if (input.isEmpty()) {
            return null;
        }
        Optional<RecipeHolder<SmeltingRecipe>> recipe = level.getRecipeManager()
                .getRecipeFor(RecipeType.SMELTING, new SingleRecipeInput(input), level);
        if (recipe.isEmpty()) {
            return null;
        }
        ItemStack result = recipe.get().value().getResultItem(level.registryAccess());
        return result.isEmpty() ? null : result;
    }

    /** 比较器信号：按物品栏的填充比例输出 0~15，和普通箱子一致。 */
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
    // MenuProvider
    // ------------------------------------------------------------------

    @Override
    protected Component getDefaultName() {
        return Component.translatable(getBlockState().getBlock().getDescriptionId());
    }

    @Override
    protected AbstractContainerMenu createMenu(int containerId, Inventory playerInventory) {
        return new OGEHyperfurnaceMenu(containerId, playerInventory, worldPosition);
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
    }
}
