package cn.origolds.ia;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * 金源自动附魔机的容器菜单：三个槽位（左、右输入 + 输出）加玩家背包，
 * 面板尺寸与铁砧一致（176 高的标准容器界面，宽度略加宽以容下需求文字）。
 *
 * <p>槽位 0、1 是两个输入，槽位 2 是产物输出。合并进行中两个输入槽会被锁住，
 * 既不能取出也不能放入，避免材料在计时途中被换走。所有价格、耗时、进度通过
 * {@link DataSlot} 每刻由服务端同步给客户端屏幕。</p>
 */
public final class OGEMechEnchanterMenu extends AbstractContainerMenu {

    /** 面板宽度（刚好容纳“需求OGE / 预计用时”一行，同时把左右空隙收到最小）。 */
    public static final int PANEL_WIDTH = 200;
    /** 面板高度（机器区抬高、背包紧随其下，去掉上下多余的空白）。 */
    public static final int PANEL_HEIGHT = 148;

    /** 机器槽位数（0、1 输入，2 输出）。 */
    public static final int SLOT_COUNT = OGEMechEnchanterBlockEntity.SLOT_COUNT;

    private final OGEMechEnchanterBlockEntity blockEntity;

    private final DataSlot tierSlot;
    private final DataSlot costSlot;
    private final DataSlot ticksSlot;
    private final DataSlot remainingSlot;
    private final DataSlot workingSlot;

    public OGEMechEnchanterMenu(int containerId, Inventory playerInventory, BlockPos pos) {
        super(ModMenus.MECH_ENCHANTER.get(), containerId);
        BlockEntity be = playerInventory.player.level().getBlockEntity(pos);
        this.blockEntity = be instanceof OGEMechEnchanterBlockEntity enchanter ? enchanter : null;

        if (this.blockEntity != null) {
            addSlot(new InputSlot(this.blockEntity, 0, 16, 24));
            addSlot(new InputSlot(this.blockEntity, 1, 62, 24));
            addSlot(new OutputSlot(this.blockEntity, 2, 164, 24));
            addDataSlot(tierSlot = DataSlot.forContainer(this.blockEntity, OGEMechEnchanterBlockEntity.DATA_TIER));
            addDataSlot(costSlot = DataSlot.forContainer(this.blockEntity, OGEMechEnchanterBlockEntity.DATA_COST));
            addDataSlot(ticksSlot = DataSlot.forContainer(this.blockEntity, OGEMechEnchanterBlockEntity.DATA_TICKS));
            addDataSlot(remainingSlot = DataSlot.forContainer(this.blockEntity, OGEMechEnchanterBlockEntity.DATA_REMAINING));
            addDataSlot(workingSlot = DataSlot.forContainer(this.blockEntity, OGEMechEnchanterBlockEntity.DATA_WORKING));
        } else {
            tierSlot = DataSlot.standalone();
            costSlot = DataSlot.standalone();
            ticksSlot = DataSlot.standalone();
            remainingSlot = DataSlot.standalone();
            workingSlot = DataSlot.standalone();
        }

        // 玩家背包（3 行 × 9 列）：直接跟在需求文字下方，整块水平居中。
        int mainX = (PANEL_WIDTH - 9 * 18) / 2;
        int mainY = 66;
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(playerInventory, col + row * 9 + 9, mainX + col * 18, mainY + row * 18));
            }
        }
        int hotbarY = 124;
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(playerInventory, col, mainX + col * 18, hotbarY));
        }
    }

    public OGEMechEnchanterMenu(int containerId, Inventory playerInventory, RegistryFriendlyByteBuf buffer) {
        this(containerId, playerInventory, buffer.readBlockPos());
    }

    public OGEMechEnchanterBlockEntity getBlockEntity() {
        return blockEntity;
    }

    // ------------------------------------------------------------------
    // 供客户端屏幕读取的实时数值（来自同步后的 DataSlot）
    // ------------------------------------------------------------------

    /** 当前档位下标（0/1/2）。 */
    public int getTier() {
        return tierSlot.get();
    }

    /** 本次合并预计消耗的 OGE（无效组合为 0）。 */
    public int getPlanCost() {
        return costSlot.get();
    }

    /** 本次合并预计耗时（刻，无效组合为 0）。 */
    public int getPlanTicks() {
        return ticksSlot.get();
    }

    /** 剩余刻数（未工作时为 0）。 */
    public int getRemainingTicks() {
        return remainingSlot.get();
    }

    public boolean isWorking() {
        // 服务端直接读方块实体的权威状态（点击与开始发生在两次 tick 之间，
        // 显示值要到下一 tick 才更新，直接用实体状态可以立刻锁住输入槽）；
        // 客户端方块实体的 working 字段不参与同步，因此回落到菜单数据槽。
        if (blockEntity != null && blockEntity.getLevel() != null
                && !blockEntity.getLevel().isClientSide) {
            return blockEntity.isWorking();
        }
        return workingSlot.get() == 1;
    }

    /** 箭头按钮是否可用：有合法计划、未在工作、输出槽为空。 */
    public boolean canStart() {
        return !isWorking() && getPlanCost() > 0 && getSlot(2).getItem().isEmpty();
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        // 合并进行中锁住所有机器槽位，禁止整叠搬运。
        if (isWorking() && index < SLOT_COUNT) {
            return ItemStack.EMPTY;
        }
        ItemStack result = ItemStack.EMPTY;
        Slot slot = this.slots.get(index);
        if (slot != null && slot.hasItem()) {
            ItemStack stack = slot.getItem();
            result = stack.copy();
            if (index < SLOT_COUNT) {
                if (!moveItemStackTo(stack, SLOT_COUNT, SLOT_COUNT + 36, true)) {
                    return ItemStack.EMPTY;
                }
            } else if (!moveItemStackTo(stack, 0, SLOT_COUNT, false)) {
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

    /** 输入槽：合并进行中不可放入、不可取出。 */
    private final class InputSlot extends Slot {

        InputSlot(Container container, int index, int x, int y) {
            super(container, index, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return !isWorking();
        }

        @Override
        public boolean mayPickup(Player player) {
            return !isWorking();
        }
    }

    /** 输出槽：只出不进。 */
    private static final class OutputSlot extends Slot {

        OutputSlot(Container container, int index, int x, int y) {
            super(container, index, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return false;
        }
    }
}
