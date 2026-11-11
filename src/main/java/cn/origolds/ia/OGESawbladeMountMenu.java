package cn.origolds.ia;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;

/**
 * 金源锯片台容器菜单：上面两个机器槽（0=武器/锯片、1=废件），下面 36 格玩家背包。
 *
 * <p>储经验不是 {@code DataSlot}，由 {@link #broadcastChanges()} 在服务端发现数值变化时
 * 通过 {@link OGESawbladeMountSyncPayload} 单独推给打开界面的玩家；反方向点"取出"发
 * {@link OGESawbladeTakeXpPayload}，经报文处理器回到 {@link #applyTakeXp(int)}。</p>
 */
public final class OGESawbladeMountMenu extends AbstractContainerMenu {

    /** 面板尺寸。 */
    public static final int PANEL_WIDTH = 176;
    public static final int PANEL_HEIGHT = 218;
    /** 武器/锯片槽。 */
    public static final int WEAPON_X = 79;
    public static final int WEAPON_Y = 16;
    /** 储经验读数行。 */
    public static final int XP_Y = 46;
    /** 数量输入框行（界面里输入框/MAX/取出一排）。 */
    public static final int INPUT_Y = 70;
    public static final int INPUT_H = 18;
    /** 废件槽。 */
    public static final int WASTE_X = 79;
    public static final int WASTE_Y = 100;
    /** 背包三行与快捷栏。 */
    public static final int PLAYER_Y = 134;
    public static final int HOTBAR_Y = 192;

    private final Player owner;
    private final BlockPos pos;
    @Nullable
    private final OGESawbladeMountBlockEntity mount;
    /** 服务端同步下来的储经验缓存，只给界面读数用。 */
    private int storedExperienceClient;
    private boolean syncedOnce;
    private int lastStored = Integer.MIN_VALUE;

    public OGESawbladeMountMenu(int containerId, Inventory playerInventory, BlockPos pos) {
        super(ModMenus.SAWBLADE_MOUNT.get(), containerId);
        this.owner = playerInventory.player;
        this.pos = pos;
        this.mount = playerInventory.player.level().getBlockEntity(pos) instanceof OGESawbladeMountBlockEntity be
                ? be : null;
        if (this.mount != null) {
            this.addSlot(new OGESawbladeMountMenu.WeaponSlot(this.mount, 0, WEAPON_X, WEAPON_Y));
            this.addSlot(new OGESawbladeMountMenu.WasteSlot(this.mount, 1, WASTE_X, WASTE_Y));
        }

        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new Slot(playerInventory, col + row * 9 + 9, 8 + col * 18, PLAYER_Y + row * 18));
            }
        }

        for (int col = 0; col < 9; col++) {
            this.addSlot(new Slot(playerInventory, col, 8 + col * 18, HOTBAR_Y));
        }
    }

    /** 客户端构造：从开菜单的额外数据里读锯片台坐标。 */
    public OGESawbladeMountMenu(int containerId, Inventory playerInventory, RegistryFriendlyByteBuf buffer) {
        this(containerId, playerInventory, buffer.readBlockPos());
    }

    @Nullable
    public OGESawbladeMountBlockEntity getMount() {
        return this.mount;
    }

    public int getStoredExperience() {
        return this.storedExperienceClient;
    }

    public void setStoredExperienceClient(int value) {
        this.storedExperienceClient = Math.max(0, value);
    }

    /** 服务端收到"取出"请求：amount &gt; 0 取指定量，0 表示全部。 */
    public void applyTakeXp(int amount) {
        if (this.owner instanceof ServerPlayer && this.mount != null) {
            int requested = amount > 0 ? amount : this.mount.getStoredExperience();
            this.mount.takeExperienceOut(this.owner, requested);
        }
    }

    /** 服务端每刻：储经验变了（或第一次同步）就推一次同步报文。 */
    @Override
    public void broadcastChanges() {
        super.broadcastChanges();
        if (this.owner instanceof ServerPlayer player && this.mount != null) {
            int stored = this.mount.getStoredExperience();
            if (!this.syncedOnce || stored != this.lastStored) {
                PacketDistributor.sendToPlayer(player, new OGESawbladeMountSyncPayload(stored));
                this.syncedOnce = true;
                this.lastStored = stored;
            }
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        ItemStack stack = this.slots.get(index).getItem();
        if (stack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        if (index < 2) {
            // 机器槽 → 玩家背包。
            if (!this.moveItemStackTo(stack, 2, 38, true)) {
                return ItemStack.EMPTY;
            }
        } else if ((!OGEMeleeWeapons.isMeleeWeapon(stack) || !this.moveItemStackTo(stack, 0, 1, false))
                && !this.moveItemStackTo(stack, 2, 38, true)) {
            // 玩家背包 → 优先塞武器/锯片槽，塞不下再在背包里整理。
            return ItemStack.EMPTY;
        }
        return stack;
    }

    @Override
    public boolean stillValid(Player player) {
        return this.mount != null && this.mount.stillValid(player);
    }

    /** 废件槽：随便放随便拿。 */
    private static final class WasteSlot extends Slot {
        WasteSlot(OGESawbladeMountBlockEntity mount, int slotId, int x, int y) {
            super(mount, slotId, x, y);
        }
    }

    /** 武器/锯片槽：只收带主手近战属性修改的武器。 */
    private static final class WeaponSlot extends Slot {
        WeaponSlot(OGESawbladeMountBlockEntity mount, int slotId, int x, int y) {
            super(mount, slotId, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return OGEMeleeWeapons.isMeleeWeapon(stack);
        }
    }
}
