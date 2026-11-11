package cn.origolds.ia;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * 金源能量发生器（火驱/魔驱）的容器菜单：一个原料槽 + 玩家背包。
 *
 * <p>火驱只接受普通燃料（熔炉燃料），魔驱只接受“魔金之核”。原料放入后，
 * 方块的产电逻辑会在每个刻消耗它并产出 OGE。</p>
 *
 * <p>通过 {@link DataSlot} 把方块实体的实时 OGE 储量、容量、燃烧进度与工作状态
 * 同步给客户端屏幕，保证 UI 每 tick 自动刷新。</p>
 */
public final class OGEGeneratorMenu extends AbstractContainerMenu {

    private final OGEMachineBlockEntity blockEntity;

    private final DataSlot storedSlot;
    private final DataSlot capacitySlot;
    private final DataSlot burnTimeSlot;
    private final DataSlot burnDurationSlot;
    private final DataSlot workingSlot;
    private final DataSlot produceSlot;

    public OGEGeneratorMenu(int containerId, Inventory playerInventory, BlockPos pos) {
        super(ModMenus.GENERATOR.get(), containerId);
        BlockEntity be = playerInventory.player.level().getBlockEntity(pos);
        this.blockEntity = be instanceof OGEMachineBlockEntity machine ? machine : null;

        if (this.blockEntity != null) {
            addSlot(new Slot(this.blockEntity, 0, 8, 20));

            // 实时数据槽：服务器端每 tick 广播，客户端屏幕每 tick 读取刷新。
            addDataSlot(storedSlot = DataSlot.forContainer(this.blockEntity, 0));
            addDataSlot(capacitySlot = DataSlot.forContainer(this.blockEntity, 1));
            addDataSlot(burnTimeSlot = DataSlot.forContainer(this.blockEntity, 2));
            addDataSlot(burnDurationSlot = DataSlot.forContainer(this.blockEntity, 3));
            addDataSlot(workingSlot = DataSlot.forContainer(this.blockEntity, 4));
            addDataSlot(produceSlot = DataSlot.forContainer(this.blockEntity, 5));
        } else {
            storedSlot = DataSlot.standalone();
            capacitySlot = DataSlot.standalone();
            burnTimeSlot = DataSlot.standalone();
            burnDurationSlot = DataSlot.standalone();
            workingSlot = DataSlot.standalone();
            produceSlot = DataSlot.standalone();
        }

        // 玩家背包（3 行 × 9 列）：面板宽 228，左右各留 30 居中。
        int mainY = 84;
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(playerInventory, col + row * 9 + 9, 30 + col * 18, mainY + row * 18));
            }
        }
        // 玩家快捷栏
        int hotbarY = 142;
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(playerInventory, col, 30 + col * 18, hotbarY));
        }
    }

    public OGEGeneratorMenu(int containerId, Inventory playerInventory, RegistryFriendlyByteBuf buffer) {
        this(containerId, playerInventory, buffer.readBlockPos());
    }

    public OGEMachineBlockEntity getBlockEntity() {
        return blockEntity;
    }

    // ------------------------------------------------------------------
    // 供客户端屏幕读取的实时数值（来自同步后的 DataSlot）
    // ------------------------------------------------------------------

    public int getStoredOGE() {
        return storedSlot.get();
    }

    public int getCapacity() {
        return capacitySlot.get();
    }

    public int getProduceRate() {
        return produceSlot.get();
    }

    /** 当前燃料/魔金之核燃烧进度（0~1）。 */
    public float getBurnProgress() {
        int time = burnTimeSlot.get();
        int duration = burnDurationSlot.get();
        return duration > 0 ? (float) time / (float) duration : 0.0f;
    }

    public boolean isWorking() {
        return workingSlot.get() == 1;
    }

    /** 该机器是否为需要燃料的机型（火驱/魔驱），用于决定是否显示燃烧进度条。 */
    public boolean isFueled() {
        return blockEntity != null && blockEntity.getKind().isFueled();
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        ItemStack result = ItemStack.EMPTY;
        Slot slot = this.slots.get(index);
        if (slot != null && slot.hasItem()) {
            ItemStack stack = slot.getItem();
            result = stack.copy();
            if (index == 0) {
                // 从原料槽移回背包
                if (!moveItemStackTo(stack, 1, 37, true)) {
                    return ItemStack.EMPTY;
                }
            } else if (blockEntity != null && !moveItemStackTo(stack, 0, 1, false)) {
                // 从背包移入原料槽
                return ItemStack.EMPTY;
            }
            if (stack.isEmpty()) {
                slot.setByPlayer(ItemStack.EMPTY);
            } else {
                slot.setChanged();
            }
            if (stack.getCount() == result.getCount()) {
                return ItemStack.EMPTY;
            }
            slot.onTake(player, stack);
        }
        return result;
    }

    @Override
    public boolean stillValid(Player player) {
        return blockEntity != null && blockEntity.stillValid(player);
    }
}
