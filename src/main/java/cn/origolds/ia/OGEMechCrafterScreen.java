package cn.origolds.ia;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 金源自动合成机 UI。
 *
 * <p>上方 3×3 是只做显示的合成格：左键一个格子把鼠标上的物品锁进去（物品还在鼠标上），
 * 右键清除锁定；右边显示当前锁定组合能合成的产物预览。下方是 9 个竖着的 3 格存储，
 * 第 c 列只接受第 c 个合成格锁定的物品。整个界面沿用炼金站/引力匣的暗金外观。</p>
 *
 * <p>合成格与预览物品都用 {@link GuiGraphics#renderFakeItem} 画成半透明虚影，和原版
 * 合成器显示“会合成出什么”的观感一致。锁定数据由方块实体的同步报文送到客户端，
 * 这里每刻比对一次，变了才重算预览。</p>
 */
@EventBusSubscriber(modid = OriGoldsIndustrializedAge.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class OGEMechCrafterScreen extends AbstractContainerScreen<OGEMechCrafterMenu> {

    private static final int COLOR_BG = 0xFF101015;
    private static final int COLOR_BORDER = 0xFFD9A91F;
    private static final int COLOR_SLOT_BORDER = 0xFF6A5A22;
    private static final int COLOR_SLOT_INNER = 0xFF1C1C24;
    private static final int COLOR_GRID_BORDER = 0xFFB07A2A;
    private static final int COLOR_GRID_INNER = 0xFF17171E;
    private static final int COLOR_TEXT = 0xFFFFFFFF;
    private static final int COLOR_DIM = 0xFF9A9AA0;

    /** 合成格与结果框的边长。 */
    private static final int CELL = 18;

    /** 结果格边长：普通格的 1.5 倍。 */
    private static final int RESULT_CELL = OGEMechCrafterMenu.RESULT_CELL;

    /** 合成格 → 结果格之间的箭头贴图（16×16）。 */
    private static final ResourceLocation ARROW_TEXTURE = ResourceLocation.fromNamespaceAndPath(
            OriGoldsIndustrializedAge.MOD_ID, "textures/gui/arrow_right.png");

    /** 箭头贴图的绘制边长。 */
    private static final int ARROW_SIZE = 16;

    /** 存储格里“允许放入什么”的虚影透明度：25%。 */
    private static final float GHOST_ALPHA = 0.25F;

    /** 没被锁定的存储列只能收空气，用屏障虚影表示。 */
    private static final ItemStack BARRIER_GHOST = new ItemStack(Items.BARRIER);

    /** 9 个合成格当前锁定的物品（客户端镜像）。 */
    private final List<ItemStack> locks = new ArrayList<>(OGEMechCrafterMenu.CELL_COUNT);

    /** 当前锁定组合的产物预览。 */
    private ItemStack preview = ItemStack.EMPTY;

    public OGEMechCrafterScreen(OGEMechCrafterMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = OGEMechCrafterMenu.PANEL_WIDTH;
        this.imageHeight = OGEMechCrafterMenu.PANEL_HEIGHT;
        this.titleLabelX = 8;
        this.titleLabelY = 6;
        this.inventoryLabelX = 8;
        this.inventoryLabelY = OGEMechCrafterMenu.PLAYER_Y - 10;
        for (int i = 0; i < OGEMechCrafterMenu.CELL_COUNT; i++) {
            locks.add(ItemStack.EMPTY);
        }
    }

    @Override
    protected void init() {
        super.init();
        refreshLocks();
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        refreshLocks();
    }

    /** 从客户端方块实体拉一份锁定数据和服务端算好的产物预览。 */
    private void refreshLocks() {
        OGEMechCrafterBlockEntity crafter = menu.getCrafter();
        if (crafter == null) {
            return;
        }
        // 预览由服务端按“实际组装”算好随同步包下发（特殊配方也有正确的产物），
        // 与顶部浮空展品同源；不再在客户端用配方的固定产物现算。
        if (!ItemStack.matches(preview, crafter.getPreviewResult())) {
            preview = crafter.getPreviewResult().copy();
        }
        boolean changed = false;
        for (int cell = 0; cell < OGEMechCrafterMenu.CELL_COUNT; cell++) {
            if (!ItemStack.isSameItemSameComponents(crafter.getLock(cell), locks.get(cell))) {
                changed = true;
                break;
            }
        }
        if (!changed) {
            return;
        }
        for (int cell = 0; cell < OGEMechCrafterMenu.CELL_COUNT; cell++) {
            locks.set(cell, crafter.getLock(cell).copy());
        }
    }

    // ------------------------------------------------------------------
    // 绘制
    // ------------------------------------------------------------------

    @Override
    protected void renderBg(GuiGraphics gui, float partialTick, int mouseX, int mouseY) {
        int x = this.leftPos;
        int y = this.topPos;

        gui.fill(x, y, x + imageWidth, y + imageHeight, COLOR_BG);
        gui.fill(x, y, x + imageWidth, y + 1, COLOR_BORDER);
        gui.fill(x, y + imageHeight - 1, x + imageWidth, y + imageHeight, COLOR_BORDER);
        gui.fill(x, y, x + 1, y + imageHeight, COLOR_BORDER);
        gui.fill(x + imageWidth - 1, y, x + imageWidth, y + imageHeight, COLOR_BORDER);

        // 真实的存储/背包槽位边框。
        for (Slot slot : this.menu.slots) {
            drawBox(gui, x + slot.x - 1, y + slot.y - 1, CELL, COLOR_SLOT_BORDER, COLOR_SLOT_INNER);
        }

        // 存储栏每格画上“本列允许放入什么”的 25% 虚影：本列锁定了物品就画那件物品，
        // 没锁定就画屏障（该列什么都收不了）。
        if (this.menu.getCrafter() != null) {
            for (int i = 0; i < OGEMechCrafterMenu.SLOT_COUNT; i++) {
                Slot slot = this.menu.slots.get(i);
                ItemStack lock = locks.get(i / OGEMechCrafterMenu.ROWS_PER_COLUMN);
                renderGhost(gui, lock.isEmpty() ? BARRIER_GHOST : lock,
                        x + slot.x, y + slot.y, GHOST_ALPHA);
            }
        }

        // 9 个合成格边框。
        for (int cell = 0; cell < OGEMechCrafterMenu.CELL_COUNT; cell++) {
            int col = cell % OGEMechCrafterMenu.GRID_SIZE;
            int row = cell / OGEMechCrafterMenu.GRID_SIZE;
            int gx = x + OGEMechCrafterMenu.GRID_X + col * CELL;
            int gy = y + OGEMechCrafterMenu.GRID_Y + row * CELL;
            boolean locked = !locks.get(cell).isEmpty();
            drawBox(gui, gx - 1, gy - 1, CELL,
                    locked ? COLOR_BORDER : COLOR_GRID_BORDER, COLOR_GRID_INNER);
        }

        // 结果预览框（1.5 倍普通格）+ 中间的箭头贴图。
        int rx = x + OGEMechCrafterMenu.RESULT_X;
        int ry = y + OGEMechCrafterMenu.RESULT_Y;
        drawBox(gui, rx - 1, ry - 1, RESULT_CELL, COLOR_GRID_BORDER, COLOR_GRID_INNER);
        int gridRight = x + OGEMechCrafterMenu.GRID_X + OGEMechCrafterMenu.GRID_SIZE * CELL;
        int resultLeft = rx - 1;
        int arrowX = gridRight + (resultLeft - gridRight - ARROW_SIZE) / 2;
        int arrowY = y + OGEMechCrafterMenu.GRID_Y - 1
                + (OGEMechCrafterMenu.GRID_SIZE * CELL - ARROW_SIZE) / 2;
        gui.blit(ARROW_TEXTURE, arrowX, arrowY, 0.0F, 0.0F,
                ARROW_SIZE, ARROW_SIZE, ARROW_SIZE, ARROW_SIZE);

        // 锁定物品的虚影：画在格子正中（格心与边框中心重合）。
        for (int cell = 0; cell < OGEMechCrafterMenu.CELL_COUNT; cell++) {
            ItemStack lock = locks.get(cell);
            if (lock.isEmpty()) {
                continue;
            }
            int col = cell % OGEMechCrafterMenu.GRID_SIZE;
            int row = cell / OGEMechCrafterMenu.GRID_SIZE;
            renderGhost(gui, lock,
                    x + OGEMechCrafterMenu.GRID_X + col * CELL,
                    y + OGEMechCrafterMenu.GRID_Y + row * CELL, 0.5F);
        }
        if (!preview.isEmpty()) {
            // 16×16 的物品摆在 27×27 的结果框正中。
            int inset = (RESULT_CELL - 16) / 2;
            renderGhost(gui, preview, rx + inset, ry + inset, 0.5F);
        }

        // 悬停高亮：合成格不是真实槽位，原版不会替它画，这里照原版槽位高亮补一个
        // （半透明白色 16×16，正好盖住 18×18 边框里的内圈，观感和正常格子一致）。
        // 画在虚影物品之后，和原版“高亮压在物品上面”的层次保持一致。
        int hoveredCell = cellAt(mouseX, mouseY);
        if (hoveredCell >= 0) {
            int col = hoveredCell % OGEMechCrafterMenu.GRID_SIZE;
            int row = hoveredCell / OGEMechCrafterMenu.GRID_SIZE;
            renderSlotHighlight(gui,
                    x + OGEMechCrafterMenu.GRID_X + col * CELL,
                    y + OGEMechCrafterMenu.GRID_Y + row * CELL, 0);
        }
    }

    @Override
    protected void renderLabels(GuiGraphics gui, int mouseX, int mouseY) {
        gui.drawString(this.font, this.title, this.titleLabelX, this.titleLabelY, COLOR_TEXT);
        gui.drawString(this.font, this.playerInventoryTitle, this.inventoryLabelX, this.inventoryLabelY, COLOR_TEXT);
        gui.drawString(this.font, Component.translatable("gui.origolds_ia.mech_crafter.hint"),
                8, OGEMechCrafterMenu.HINT_Y, COLOR_DIM);
    }

    /** 画一个边长 {@code size} 的格子：外层描边 + 内层深色底。 */
    private static void drawBox(GuiGraphics gui, int x, int y, int size, int border, int inner) {
        gui.fill(x, y, x + size, y + size, border);
        gui.fill(x + 1, y + 1, x + size - 1, y + size - 1, inner);
    }

    /** 以指定透明度画一件“虚影”物品（锁定内容 / 屏障）。 */
    private static void renderGhost(GuiGraphics gui, ItemStack stack, int x, int y, float alpha) {
        if (stack.isEmpty()) {
            return;
        }
        gui.setColor(1.0F, 1.0F, 1.0F, alpha);
        gui.renderItem(stack, x, y);
        gui.setColor(1.0F, 1.0F, 1.0F, 1.0F);
    }

    // ------------------------------------------------------------------
    // 交互：左键锁定、右键清除
    // ------------------------------------------------------------------

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 || button == 1) {
            int cell = cellAt(mouseX, mouseY);
            if (cell >= 0) {
                if (button == 0) {
                    ItemStack carried = this.menu.getCarried();
                    if (!carried.isEmpty()) {
                        PacketDistributor.sendToServer(new OGEMechCrafterLockPayload(
                                cell, carried.copyWithCount(1)));
                    }
                } else {
                    PacketDistributor.sendToServer(new OGEMechCrafterLockPayload(
                            cell, ItemStack.EMPTY));
                }
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /** 命中哪个合成格；没命中返回 -1。 */
    private int cellAt(double mouseX, double mouseY) {
        double relX = mouseX - this.leftPos - OGEMechCrafterMenu.GRID_X;
        double relY = mouseY - this.topPos - OGEMechCrafterMenu.GRID_Y;
        if (relX < 0.0D || relY < 0.0D) {
            return -1;
        }
        int col = (int) (relX / CELL);
        int row = (int) (relY / CELL);
        if (col >= OGEMechCrafterMenu.GRID_SIZE || row >= OGEMechCrafterMenu.GRID_SIZE) {
            return -1;
        }
        return row * OGEMechCrafterMenu.GRID_SIZE + col;
    }

    /** 屏幕渲染末尾补一次物品提示：1.21.1 的容器基类不会自动画悬停物品的 tooltip。 */
    @Override
    public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        super.render(gui, mouseX, mouseY, partialTick);
        this.renderTooltip(gui, mouseX, mouseY);
    }

    /** 在客户端注册本屏幕到菜单类型。 */
    @SubscribeEvent
    public static void registerScreens(RegisterMenuScreensEvent event) {
        event.register(ModMenus.MECH_CRAFTER.get(), OGEMechCrafterScreen::new);
    }
}
