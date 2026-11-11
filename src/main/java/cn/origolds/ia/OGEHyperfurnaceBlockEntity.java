package cn.origolds.ia;

import java.util.Optional;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
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
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.wrapper.InvWrapper;

/**
 * 金源超级熔炉的方块实体：27 格原料区，无燃料、无输出槽。
 *
 * <p>服务端按档位（见 {@link OGEHyperfurnaceBlock#MODE}）的间隔刻扫描原料区，找到
 * 第一件有熔炼配方的物品即从金源无线电网扣对应 OGE：成功则消耗这件原料、把熔炼
 * 成品吐到机器正上方（按 {@link OGEFaceOutput} 的面规则，可被上方容器接收），
 * 扣费失败则本刻什么都不做、下一刻再试。空手右键打开 {@link OGEHyperfurnaceMenu}；
 * 比较器按箱内占用格数输出信号。</p>
 */
public final class OGEHyperfurnaceBlockEntity extends BaseContainerBlockEntity {

    /** 原料区格数。 */
    public static final int SLOT_COUNT = 27;

    /** 原料区每行列数。 */
    public static final int COLUMNS = 9;

    /** 原料区行数。 */
    public static final int ROWS = 3;

    /** 原料区内容。 */
    private NonNullList<ItemStack> items = NonNullList.withSize(SLOT_COUNT, ItemStack.EMPTY);

    /** 对外暴露的物品能力包装（供自动化插入/抽取）。 */
    private final IItemHandler itemHandler = new InvWrapper(this);

    public OGEHyperfurnaceBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.OGE_HYPERFURNACE.get(), pos, state);
    }

    public IItemHandler getItemHandler() {
        return this.itemHandler;
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

    /** 服务端每刻调用：到点找一件可熔原料，扣 OGE 后熔掉并吐到机器上方。 */
    public void tick() {
        if (this.level == null || this.level.isClientSide()
                || !(this.level instanceof ServerLevel serverLevel)) {
            return;
        }
        if (!(getBlockState().getBlock() instanceof OGEHyperfurnaceBlock)) {
            return;
        }
        int mode = getBlockState().getValue(OGEHyperfurnaceBlock.MODE);
        int interval = OGEHyperfurnaceBlock.ticksPerItem(mode);
        if (serverLevel.getGameTime() % interval != 0L) {
            return;
        }
        MinecraftServer server = serverLevel.getServer();
        if (server == null) {
            return;
        }
        int inputSlot = findSmeltableSlot(serverLevel);
        if (inputSlot < 0) {
            return;
        }
        int cost = OGEHyperfurnaceBlock.costPerItem(mode);
        int consumed = OGEWirelessManager.consumeOGEIfAvailable(server, serverLevel.dimension(),
                this.worldPosition, cost);
        if (consumed >= cost) {
            ItemStack input = this.items.get(inputSlot);
            ItemStack result = planSmelting(serverLevel, input);
            input.shrink(1);
            OGEFaceOutput.output(serverLevel, this.worldPosition.above(),
                    result == null ? ItemStack.EMPTY : result.copy());
            setChanged();
        }
    }

    /** 从第 0 格起找第一件有熔炼配方的原料；没有则返回 -1。 */
    private int findSmeltableSlot(ServerLevel serverLevel) {
        for (int i = 0; i < SLOT_COUNT; i++) {
            if (planSmelting(serverLevel, this.items.get(i)) != null) {
                return i;
            }
        }
        return -1;
    }

    /** 查熔炼配方给出这一件原料的成品；不可熔炼返回 null。 */
    @Nullable
    private static ItemStack planSmelting(ServerLevel level, ItemStack input) {
        if (input.isEmpty()) {
            return null;
        }
        Optional<RecipeHolder<SmeltingRecipe>> recipe =
                level.recipeAccess().getRecipeFor(RecipeType.SMELTING, new SingleRecipeInput(input), level);
        if (recipe.isEmpty()) {
            return null;
        }
        ItemStack result = recipe.get().value().assemble(new SingleRecipeInput(input));
        return result.isEmpty() ? null : result;
    }

    /** 比较器信号：按箱内占用格数比例输出 0~15。 */
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
    protected Component getDefaultName() {
        return Component.translatable(getBlockState().getBlock().getDescriptionId());
    }

    @Override
    protected AbstractContainerMenu createMenu(int containerId, Inventory playerInventory) {
        return new OGEHyperfurnaceMenu(containerId, playerInventory, this.worldPosition);
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
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        for (int i = 0; i < SLOT_COUNT; i++) {
            this.items.set(i, ItemStack.EMPTY);
        }
        ContainerHelper.loadAllItems(input, this.items);
    }
}
