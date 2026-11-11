package cn.origolds.ia;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 金源圆锯刃槽的容器菜单。
 *
 * <p>两个机器槽位：顶部居中的<b>武器槽</b>（只收近战武器）与底部居中的<b>废物槽</b>
 * （收武器槽的产出，玩家也能手动往里塞东西）。中间的经验值不走 {@code ContainerData}
 * （经验能涨过 short 量程），由 {@link #broadcastChanges()} 在变化时发
 * {@link OGESawbladeMountSyncPayload}，界面侧由 {@link OGESawbladeMountScreen#handleSync}
 * 落到这个菜单的客户端镜像上。</p>
 */
public final class OGESawbladeMountMenu extends AbstractContainerMenu {

    // ------------------------------------------------------------------
    // 面板布局（面板局部坐标，菜单与界面共用）
    // ------------------------------------------------------------------

    public static final int PANEL_WIDTH = 176;
    public static final int PANEL_HEIGHT = 218;

    /** 武器槽（顶部水平居中）。 */
    public static final int WEAPON_X = (PANEL_WIDTH - 18) / 2;
    public static final int WEAPON_Y = 16;

    /** 经验行（图标 + 数字）的纵坐标，夹在两个槽位之间。 */
    public static final int XP_Y = 46;

    /** 输入行：文本框、MAX、取出按钮的纵坐标与高度。 */
    public static final int INPUT_Y = 70;
    public static final int INPUT_H = 18;

    /** 废物槽（底部水平居中）。 */
    public static final int WASTE_X = (PANEL_WIDTH - 18) / 2;
    public static final int WASTE_Y = 100;

    /** 玩家背包主区与快捷栏。 */
    public static final int PLAYER_Y = 134;
    public static final int HOTBAR_Y = 192;

    private final Player owner;
    private final BlockPos pos;

    @Nullable
    private final OGESawbladeMountBlockEntity mount;

    /** 经验值的客户端镜像（由同步报文写入，界面上的 MAX 按钮读它）。 */
    private int storedExperienceClient;

    private boolean syncedOnce;
    private int lastStored = Integer.MIN_VALUE;

    public OGESawbladeMountMenu(int containerId, Inventory playerInventory, BlockPos pos) {
        super(ModMenus.SAWBLADE_MOUNT.get(), containerId);
        this.owner = playerInventory.player;
        this.pos = pos;
        this.mount = playerInventory.player.level().getBlockEntity(pos)
                instanceof OGESawbladeMountBlockEntity be ? be : null;

        if (mount != null) {
            addSlot(new WeaponSlot(mount, OGESawbladeMountBlockEntity.SLOT_WEAPON, WEAPON_X, WEAPON_Y));
            addSlot(new WasteSlot(mount, OGESawbladeMountBlockEntity.SLOT_WASTE, WASTE_X, WASTE_Y));
        }

        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(playerInventory, col + row * 9 + 9, 8 + col * 18, PLAYER_Y + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(playerInventory, col, 8 + col * 18, HOTBAR_Y));
        }
    }

    public OGESawbladeMountMenu(int containerId, Inventory playerInventory, RegistryFriendlyByteBuf buffer) {
        this(containerId, playerInventory, buffer.readBlockPos());
    }

    @Nullable
    public OGESawbladeMountBlockEntity getMount() {
        return mount;
    }

    /** 界面上显示的存储经验（客户端镜像值）。 */
    public int getStoredExperience() {
        return storedExperienceClient;
    }

    /** 同步报文到达时写入镜像（服务端玩家不会用到）。 */
    public void setStoredExperienceClient(int value) {
        this.storedExperienceClient = Math.max(0, value);
    }

    /**
     * “取出”按钮：服务器侧校验后从刃槽扣账并把经验掉在玩家脚下。
     * {@code amount <= 0} 表示按当前库存全额取出。
     */
    public void applyTakeXp(int amount) {
        if (!(owner instanceof ServerPlayer) || mount == null) {
            return;
        }
        int requested = amount > 0 ? amount : mount.getStoredExperience();
        mount.takeExperienceOut(owner, requested);
    }

    @Override
    public void broadcastChanges() {
        super.broadcastChanges();
        if (!(owner instanceof ServerPlayer player) || mount == null) {
            return;
        }
        int stored = mount.getStoredExperience();
        if (!syncedOnce || stored != lastStored) {
            PacketDistributor.sendToPlayer(player, new OGESawbladeMountSyncPayload(stored));
            syncedOnce = true;
            lastStored = stored;
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        ItemStack stack = slots.get(index).getItem();
        if (stack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        if (index < 2) {
            // 机器槽 → 玩家背包
            if (!moveItemStackTo(stack, 2, 38, true)) {
                return ItemStack.EMPTY;
            }
        } else if (OGEMeleeWeapons.isMeleeWeapon(stack)
                && moveItemStackTo(stack, 0, 1, false)) {
            // 玩家背包 → 武器槽（只有近战武器能进）
        } else if (!moveItemStackTo(stack, 2, 38, true)) {
            // 其余：原位不动
            return ItemStack.EMPTY;
        }
        return stack;
    }

    @Override
    public boolean stillValid(Player player) {
        return mount != null && mount.stillValid(player);
    }

    /** 武器槽：只让放近战武器（判定与方块实体的 {@code canPlaceItem} 一致）。 */
    private static final class WeaponSlot extends Slot {
        WeaponSlot(OGESawbladeMountBlockEntity mount, int slotId, int x, int y) {
            super(mount, slotId, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return OGEMeleeWeapons.isMeleeWeapon(stack);
        }
    }

    /** 废物槽：玩家随便放（系统的“报废安置”也走这格）， Shift 快移不往这里送。 */
    private static final class WasteSlot extends Slot {
        WasteSlot(OGESawbladeMountBlockEntity mount, int slotId, int x, int y) {
            super(mount, slotId, x, y);
        }
    }
}
