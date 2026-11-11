package cn.origolds.ia;

import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * 金源机械合成台的容器菜单：27 格储料仓 + 玩家背包与快捷栏。
 *
 * <p>3×3 的合成矩阵本身<b>不是</b>槽位——它由方块实体的 9 个"锁定格"充当，
 * 界面上直接绘制并靠点锁报文修改（见 {@link OGEMechCrafterScreen}）。
 * 本菜单只承载储料仓：每个仓格按 {@link OGEMechCrafterBlockEntity#cellOfSlot(int)}
 * 归属一个矩阵格，放入的物品必须与所属格的锁定种类一致，否则拒绝放入。</p>
 *
 * <p>槽位编号约定：0~26 储料仓（3 列 × 9 行，行内先按矩阵格走），
 * 27~53 玩家背包，54~62 快捷栏；快速转移在两个区之间整叠搬运。</p>
 */
public final class OGEMechCrafterMenu extends AbstractContainerMenu {

    /** 储料仓格数。 */
    public static final int SLOT_COUNT = OGEMechCrafterBlockEntity.SLOT_COUNT;
    /** 每个矩阵格对应的储料仓格数。 */
    public static final int ROWS_PER_COLUMN = OGEMechCrafterBlockEntity.ROWS_PER_COLUMN;
    /** 合成矩阵边长。 */
    public static final int GRID_SIZE = OGEMechCrafterBlockEntity.GRID_SIZE;
    /** 合成矩阵总格数。 */
    public static final int CELL_COUNT = OGEMechCrafterBlockEntity.CELL_COUNT;
    /** 玩家背包每行槽位数。 */
    public static final int COLUMNS = 9;
    /** 面板宽。 */
    public static final int PANEL_WIDTH = 176;
    /** 单个槽位格宽。 */
    public static final int CELL = 18;
    /** 结果展示格（非槽位）对应的矩阵格号常量，与方块实体口径一致。 */
    public static final int RESULT_CELL = 27;
    /** 合成矩阵原点（面板坐标）。 */
    public static final int GRID_X = 37;
    /** 合成矩阵原点纵坐标。 */
    public static final int GRID_Y = 18;
    /** 结果展示框原点横坐标。 */
    public static final int RESULT_X = 114;
    /** 结果展示框原点纵坐标。 */
    public static final int RESULT_Y = 32;
    /** 提示文字行纵坐标。 */
    public static final int HINT_Y = 75;
    /** 储料仓区原点纵坐标。 */
    public static final int STORAGE_Y = 86;
    /** 玩家背包区原点纵坐标。 */
    public static final int PLAYER_Y = 156;
    /** 快捷栏行纵坐标。 */
    public static final int HOTBAR_Y = 214;
    /** 面板高。 */
    public static final int PANEL_HEIGHT = 240;

    private final Player owner;
    private final BlockPos pos;
    /** 服务端与客户端都按坐标取本机的方块实体；取不到时为 null（所有槽位退化为只读）。 */
    @Nullable
    private final OGEMechCrafterBlockEntity crafter;

    public OGEMechCrafterMenu(int containerId, Inventory playerInventory, BlockPos pos) {
        super(ModMenus.MECH_CRAFTER.get(), containerId);
        this.owner = playerInventory.player;
        this.pos = pos;
        this.crafter = playerInventory.player.level().getBlockEntity(pos)
                instanceof OGEMechCrafterBlockEntity mechCrafter ? mechCrafter : null;
        if (this.crafter != null) {
            // 27 格储料仓：3 列 × 9 行；同一矩阵格的三格连续排在一列里。
            for (int i = 0; i < SLOT_COUNT; i++) {
                this.addSlot(new MechCrafterSlot(this.crafter, i,
                        8 + i / ROWS_PER_COLUMN * CELL, STORAGE_Y + i % ROWS_PER_COLUMN * CELL));
            }
        }

        // 玩家背包（3 行 × 9 列）与快捷栏。
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < COLUMNS; col++) {
                this.addSlot(new Slot(playerInventory, col + row * COLUMNS + 9,
                        8 + col * CELL, PLAYER_Y + row * CELL));
            }
        }
        for (int col = 0; col < COLUMNS; col++) {
            this.addSlot(new Slot(playerInventory, col, 8 + col * CELL, HOTBAR_Y));
        }
    }

    /** 网络构造：容器打开时服务端把方块坐标写进缓冲区，客户端按原样读回。 */
    public OGEMechCrafterMenu(int containerId, Inventory playerInventory, RegistryFriendlyByteBuf buffer) {
        this(containerId, playerInventory, buffer.readBlockPos());
    }

    public BlockPos getPos() {
        return this.pos;
    }

    @Nullable
    public OGEMechCrafterBlockEntity getCrafter() {
        return this.crafter;
    }

    /** 界面点锁报文的服务端入口：只有本人还能用这台机器时才生效。 */
    public void setLock(int cell, ItemStack stack) {
        if (this.crafter != null && this.crafter.stillValid(this.owner)) {
            this.crafter.setLock(cell, stack);
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        ItemStack result = ItemStack.EMPTY;
        Slot slot = this.slots.get(index);
        if (slot != null && slot.hasItem() && this.crafter != null) {
            ItemStack stack = slot.getItem();
            result = stack.copy();
            if (index < SLOT_COUNT) {
                // 储料仓 → 玩家背包（含快捷栏）。
                if (!this.moveItemStackTo(stack, SLOT_COUNT, SLOT_COUNT + 36, true)) {
                    return ItemStack.EMPTY;
                }
            } else if (!this.moveItemStackTo(stack, 0, SLOT_COUNT, false)) {
                // 背包 → 储料仓：目标格没锁定或种类不符会被 mayPlace 挡下。
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
            return result;
        }
        return result;
    }

    @Override
    public boolean stillValid(Player player) {
        return this.crafter != null && this.crafter.stillValid(player);
    }

    /**
     * 储料仓槽位：放行与否交给方块实体的锁定校验（一格三仓、每仓认所属矩阵格的锁定种类）。
     */
    static final class MechCrafterSlot extends Slot {

        MechCrafterSlot(OGEMechCrafterBlockEntity crafter, int index, int x, int y) {
            super(crafter, index, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return this.container.canPlaceItem(this.getContainerSlot(), stack);
        }
    }
}
