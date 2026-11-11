package cn.origolds.ia;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import org.lwjgl.glfw.GLFW;

/**
 * 金源机械合成台的界面：上方 3×3 合成矩阵（绘制格，非槽位）、右侧结果展示框、
 * 下方 27 格储料仓与玩家背包。
 *
 * <h2>点锁交互</h2>
 * <p>手持物品左键点合成矩阵的某一格，就把该格锁定成手里这种物品（只记种类）；
 * 右键清除锁定。两种操作都发 {@link OGEMechCrafterLockPayload} 给服务端，
 * 由方块实体落实。储料仓每个格子按所属矩阵格叠画锁定物（没锁定则画屏障），
 * 提示"这一列只收这种东西"。</p>
 *
 * <h2>产物预览</h2>
 * <p>26.x 客户端没有全量配方管理器，结果格里的半透明产物预览改由服务端算好、
 * 随方块实体同步包下发（{@link OGEMechCrafterBlockEntity#getPreviewResult()}），
 * 本界面每 tick 从客户端镜像里刷新。</p>
 */
@EventBusSubscriber(modid = OriGoldsIndustrializedAge.MOD_ID, value = Dist.CLIENT)
public final class OGEMechCrafterScreen extends AbstractContainerScreen<OGEMechCrafterMenu> {

    private static final int COLOR_BG = 0xFF101015;
    private static final int COLOR_BORDER = 0xFFD9A91F;
    private static final int COLOR_SLOT_BORDER = 0xFF6A5A22;
    private static final int COLOR_SLOT_INNER = 0xFF1C1C24;
    private static final int COLOR_GRID_BORDER = 0xFFB07A2A;
    private static final int COLOR_GRID_INNER = 0xFF17171E;
    private static final int COLOR_TEXT = 0xFFFFFFFF;
    private static final int COLOR_DIM = 0xFF9A9AA0;

    /** 单元格边长（与槽位一致）。 */
    private static final int CELL = OGEMechCrafterMenu.CELL;

    /** 矩阵格与结果格之间的小箭头。 */
    private static final Identifier ARROW_TEXTURE =
            Identifier.fromNamespaceAndPath(OriGoldsIndustrializedAge.MOD_ID, "textures/gui/arrow_right.png");
    private static final int ARROW_SIZE = 16;

    /** 原版格位高亮精灵（画在悬停的矩阵格上）。 */
    private static final Identifier SLOT_HIGHLIGHT_FRONT =
            Identifier.withDefaultNamespace("container/slot_highlight_front");

    /** 合成矩阵格数。 */
    private static final int CELL_COUNT = OGEMechCrafterMenu.CELL_COUNT;

    /** 客户端镜像：锁定矩阵与产物预览（每 tick 从方块实体刷新，绘制时只读本地副本）。 */
    private final List<ItemStack> locks = new ArrayList<>(CELL_COUNT);
    private ItemStack preview = ItemStack.EMPTY;

    /** 屏障幽灵图（未锁定格的占位）；懒创建，避免类加载期触碰物品注册表。 */
    private ItemStack barrierGhost;

    public OGEMechCrafterScreen(OGEMechCrafterMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title, OGEMechCrafterMenu.PANEL_WIDTH, OGEMechCrafterMenu.PANEL_HEIGHT);
        this.titleLabelX = 8;
        this.titleLabelY = 6;
        this.inventoryLabelX = 8;
        this.inventoryLabelY = OGEMechCrafterMenu.PLAYER_Y - 10;
        for (int cell = 0; cell < CELL_COUNT; cell++) {
            this.locks.add(ItemStack.EMPTY);
        }
    }

    @Override
    protected void init() {
        super.init();
        this.refreshFromBlockEntity();
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        this.refreshFromBlockEntity();
    }

    /** 把客户端方块实体上的锁定与产物预览镜像到本地副本。 */
    private void refreshFromBlockEntity() {
        OGEMechCrafterBlockEntity crafter = this.menu.getCrafter();
        if (crafter == null) {
            return;
        }
        boolean changed = false;
        for (int cell = 0; cell < CELL_COUNT; cell++) {
            ItemStack lock = crafter.getLock(cell);
            if (!ItemStack.isSameItemSameComponents(lock, this.locks.get(cell))) {
                this.locks.set(cell, lock.copy());
                changed = true;
            }
        }
        // 预览可能在锁定没变时更新（读档后服务端补算），所以每次都对一下。
        if (changed || !ItemStack.matches(this.preview, crafter.getPreviewResult())) {
            this.preview = crafter.getPreviewResult().copy();
        }
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor gui, int mouseX, int mouseY, float partialTick) {
        int x = this.leftPos;
        int y = this.topPos;

        // 面板底与描边。
        gui.fill(x, y, x + this.imageWidth, y + this.imageHeight, COLOR_BG);
        gui.fill(x, y, x + this.imageWidth, y + 1, COLOR_BORDER);
        gui.fill(x, y + this.imageHeight - 1, x + this.imageWidth, y + this.imageHeight, COLOR_BORDER);
        gui.fill(x, y, x + 1, y + this.imageHeight, COLOR_BORDER);
        gui.fill(x + this.imageWidth - 1, y, x + this.imageWidth, y + this.imageHeight, COLOR_BORDER);

        // 全部槽位边框。
        for (Slot slot : this.menu.slots) {
            drawBox(gui, x + slot.x - 1, y + slot.y - 1, CELL, COLOR_SLOT_BORDER, COLOR_SLOT_INNER);
        }

        // 储料仓幽灵：所属矩阵格锁了什么就画什么；没锁定画屏障，提示"暂不收任何东西"。
        if (this.menu.getCrafter() != null) {
            for (int i = 0; i < OGEMechCrafterMenu.SLOT_COUNT; i++) {
                Slot slot = this.menu.slots.get(i);
                ItemStack lock = this.locks.get(OGEMechCrafterBlockEntity.cellOfSlot(i));
                OGESlotGhost.render(gui, x + slot.x, y + slot.y, lock.isEmpty() ? barrier() : lock);
            }
        }

        // 3×3 合成矩阵（绘制格）：锁定格用金色描边以示区别。
        for (int cell = 0; cell < CELL_COUNT; cell++) {
            int col = cell % OGEMechCrafterMenu.GRID_SIZE;
            int row = cell / OGEMechCrafterMenu.GRID_SIZE;
            int gx = x + OGEMechCrafterMenu.GRID_X + col * CELL;
            int gy = y + OGEMechCrafterMenu.GRID_Y + row * CELL;
            boolean locked = !this.locks.get(cell).isEmpty();
            drawBox(gui, gx - 1, gy - 1, CELL,
                    locked ? COLOR_BORDER : COLOR_GRID_BORDER, COLOR_GRID_INNER);
        }

        // 结果展示框与指向它的小箭头。
        int rx = x + OGEMechCrafterMenu.RESULT_X;
        int ry = y + OGEMechCrafterMenu.RESULT_Y;
        drawBox(gui, rx - 1, ry - 1, 27, COLOR_GRID_BORDER, COLOR_GRID_INNER);
        int gridRight = x + OGEMechCrafterMenu.GRID_X + OGEMechCrafterMenu.GRID_SIZE * CELL;
        int resultLeft = rx - 1;
        int arrowX = gridRight + (resultLeft - gridRight - ARROW_SIZE) / 2;
        int arrowY = y + OGEMechCrafterMenu.GRID_Y - 1 + 19;
        gui.blit(RenderPipelines.GUI_TEXTURED, ARROW_TEXTURE, arrowX, arrowY,
                0.0F, 0.0F, ARROW_SIZE, ARROW_SIZE, ARROW_SIZE, ARROW_SIZE);

        // 矩阵格上的锁定幽灵与结果格上的产物幽灵。
        for (int cell = 0; cell < CELL_COUNT; cell++) {
            ItemStack lock = this.locks.get(cell);
            if (!lock.isEmpty()) {
                int col = cell % OGEMechCrafterMenu.GRID_SIZE;
                int row = cell / OGEMechCrafterMenu.GRID_SIZE;
                OGESlotGhost.render(gui, x + OGEMechCrafterMenu.GRID_X + col * CELL,
                        y + OGEMechCrafterMenu.GRID_Y + row * CELL, lock);
            }
        }
        if (!this.preview.isEmpty()) {
            OGESlotGhost.render(gui, rx + 5, ry + 5, this.preview);
        }

        // 悬停矩阵格的原版式高亮。
        int hoveredCell = cellAt(mouseX, mouseY);
        if (hoveredCell >= 0) {
            int col = hoveredCell % OGEMechCrafterMenu.GRID_SIZE;
            int row = hoveredCell / OGEMechCrafterMenu.GRID_SIZE;
            gui.blitSprite(RenderPipelines.GUI_TEXTURED, SLOT_HIGHLIGHT_FRONT,
                    x + OGEMechCrafterMenu.GRID_X + col * CELL - 4,
                    y + OGEMechCrafterMenu.GRID_Y + row * CELL - 4, 24, 24);
        }
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor gui, int mouseX, int mouseY) {
        gui.text(this.font, this.title, this.titleLabelX, this.titleLabelY, COLOR_TEXT);
        gui.text(this.font, this.playerInventoryTitle, this.inventoryLabelX, this.inventoryLabelY, COLOR_TEXT);
        gui.text(this.font, Component.translatable("gui.origolds_ia.mech_crafter.hint"),
                8, OGEMechCrafterMenu.HINT_Y, COLOR_DIM);
    }

    /** 未锁定储料仓格上的屏障占位图；首次使用才创建。 */
    private ItemStack barrier() {
        if (this.barrierGhost == null) {
            this.barrierGhost = new ItemStack(Items.BARRIER);
        }
        return this.barrierGhost;
    }

    /** 外框 + 内底画一个格子。 */
    private static void drawBox(GuiGraphicsExtractor gui, int x, int y, int size, int border, int inner) {
        gui.fill(x, y, x + size, y + size, border);
        gui.fill(x + 1, y + 1, x + size - 1, y + size - 1, inner);
    }

    /** 点在矩阵格上：左键把手里的物品锁进该格，右键清除锁定；其余走原版槽位逻辑。 */
    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT || event.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            int cell = cellAt(event.x(), event.y());
            if (cell >= 0) {
                if (event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                    ItemStack carried = this.menu.getCarried();
                    if (!carried.isEmpty()) {
                        ClientPacketDistributor.sendToServer(
                                new OGEMechCrafterLockPayload(cell, carried.copyWithCount(1)));
                    }
                } else {
                    ClientPacketDistributor.sendToServer(new OGEMechCrafterLockPayload(cell, ItemStack.EMPTY));
                }
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    /** 屏幕坐标 → 矩阵格号；不在矩阵区域内返回 -1。 */
    private int cellAt(double mouseX, double mouseY) {
        double relX = mouseX - this.leftPos - OGEMechCrafterMenu.GRID_X;
        double relY = mouseY - this.topPos - OGEMechCrafterMenu.GRID_Y;
        if (relX < 0.0 || relY < 0.0) {
            return -1;
        }
        int col = (int) (relX / CELL);
        int row = (int) (relY / CELL);
        return col < OGEMechCrafterMenu.GRID_SIZE && row < OGEMechCrafterMenu.GRID_SIZE
                ? row * OGEMechCrafterMenu.GRID_SIZE + col
                : -1;
    }

    /** 在客户端注册本屏幕到菜单类型。 */
    @SubscribeEvent
    public static void registerScreens(RegisterMenuScreensEvent event) {
        event.register(ModMenus.MECH_CRAFTER.get(), OGEMechCrafterScreen::new);
    }
}
