package cn.origolds.ia;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 金源圆锯刃槽 UI：暗金面板，与引力匣 / 虚空抽屉同款外观。
 *
 * <p>自上而下：顶部水平居中的<b>武器槽</b>、{@code xporb.png} 图标 + 存储经验数、
 * 一行“数量输入框 + MAX + 取出”、底部水平居中的<b>废物槽</b>、玩家背包。
 * 所有控件的坐标都从 {@link OGESawbladeMountMenu} 的面板常量取，
 * 文字一律居中或右对齐绘制并保证落在面板描边以内。</p>
 */
@EventBusSubscriber(modid = OriGoldsIndustrializedAge.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class OGESawbladeMountScreen extends AbstractContainerScreen<OGESawbladeMountMenu> {

    private static final int COLOR_BG = 0xFF101015;
    private static final int COLOR_BORDER = 0xFFD9A91F;
    private static final int COLOR_SLOT_BORDER = 0xFF6A5A22;
    private static final int COLOR_SLOT_INNER = 0xFF1C1C24;
    private static final int COLOR_TEXT = 0xFFFFFFFF;
    private static final int COLOR_DIM = 0xFF9A9AA0;
    private static final int COLOR_BUTTON_BORDER = 0xFF6A5A22;
    private static final int COLOR_BUTTON_INNER = 0xFF26262E;
    private static final int COLOR_BUTTON_BORDER_HOVER = 0xFFD9A91F;
    private static final int COLOR_BUTTON_INNER_HOVER = 0xFF3A3A44;

    private static final ResourceLocation XPORB_ICON = ResourceLocation.fromNamespaceAndPath(
            OriGoldsIndustrializedAge.MOD_ID, "textures/gui/xporb.png");

    // 输入行：数量框 + MAX + 取出，三者整行在面板里水平居中。
    private static final int FIELD_X = 21;
    private static final int FIELD_W = 58;
    private static final int MAX_X = 83;
    private static final int MAX_W = 26;
    private static final int TAKE_X = 113;
    private static final int TAKE_W = 42;
    private static final int GAP = 4;

    private EditBox amountField;

    public OGESawbladeMountScreen(OGESawbladeMountMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = OGESawbladeMountMenu.PANEL_WIDTH;
        this.imageHeight = OGESawbladeMountMenu.PANEL_HEIGHT;
        this.titleLabelX = 8;
        this.titleLabelY = 6;
        this.inventoryLabelX = 8;
        this.inventoryLabelY = OGESawbladeMountMenu.PLAYER_Y - 10;
    }

    @Override
    protected void init() {
        super.init();
        amountField = new EditBox(this.font,
                this.leftPos + FIELD_X, this.topPos + OGESawbladeMountMenu.INPUT_Y,
                FIELD_W, OGESawbladeMountMenu.INPUT_H,
                Component.translatable("gui.origolds_ia.sawblade_mount.amount"));
        amountField.setMaxLength(10);
        amountField.setTextColor(COLOR_TEXT);
        amountField.setTextShadow(false);
        // 只让敲纯数字（最多 10 位）
        amountField.setFilter(text -> text.isEmpty() || (text.length() <= 10 && text.chars().allMatch(Character::isDigit)));
        amountField.setValue("");
        addRenderableWidget(amountField);
    }

    /** 输入框里当前填的数量；留空或非法按 0 处理（服务端把 0 视作“全额取出”）。 */
    private int parseAmount() {
        String text = amountField.getValue();
        if (text.isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 同步报文入口：把服务端发来的经验值写进菜单镜像。 */
    public static void handleSync(OGESawbladeMountSyncPayload payload) {
        if (Minecraft.getInstance().screen instanceof OGESawbladeMountScreen screen) {
            screen.menu.setStoredExperienceClient(payload.storedExperience());
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

        for (Slot slot : this.menu.slots) {
            drawSlotBorder(gui, slot);
        }

        drawExperience(gui);
        drawButton(gui, MAX_X, MAX_W, OGESawbladeMountMenu.INPUT_Y,
                Component.literal("MAX"), mouseX, mouseY);
        drawButton(gui, TAKE_X, TAKE_W, OGESawbladeMountMenu.INPUT_Y,
                Component.translatable("gui.origolds_ia.sawblade_mount.take"), mouseX, mouseY);
    }

    /** 经验行：{@code xporb.png} 图标 + 数字，整体水平居中。 */
    private void drawExperience(GuiGraphics gui) {
        String text = Integer.toString(this.menu.getStoredExperience());
        int textWidth = this.font.width(text);
        int groupWidth = 16 + GAP + textWidth;
        int startX = (imageWidth - groupWidth) / 2;
        int rowY = OGESawbladeMountMenu.XP_Y;
        gui.blit(XPORB_ICON, this.leftPos + startX, this.topPos + rowY,
                0.0F, 0.0F, 16, 16, 16, 16);
        gui.drawString(this.font, text,
                this.leftPos + startX + 16 + GAP, rowY + this.topPos + (16 - this.font.lineHeight) / 2,
                COLOR_TEXT);
    }

    /** 暗金小按钮：描边方块 + 居中字，悬停提亮。 */
    private void drawButton(GuiGraphics gui, int x, int w, int y, Component label, int mouseX, int mouseY) {
        boolean hovered = isOverRect(x, y, w, mouseX, mouseY);
        int bx = this.leftPos + x;
        int by = this.topPos + y;
        gui.fill(bx, by, bx + w, by + OGESawbladeMountMenu.INPUT_H,
                hovered ? COLOR_BUTTON_BORDER_HOVER : COLOR_BUTTON_BORDER);
        gui.fill(bx + 1, by + 1, bx + w - 1, by + OGESawbladeMountMenu.INPUT_H - 1,
                hovered ? COLOR_BUTTON_INNER_HOVER : COLOR_BUTTON_INNER);
        String text = label.getString();
        int tw = this.font.width(text);
        int tx = bx + (w - tw) / 2;
        int ty = by + (OGESawbladeMountMenu.INPUT_H - this.font.lineHeight) / 2;
        gui.drawString(this.font, text, tx, ty, COLOR_TEXT);
    }

    /** 绘制槽位边框：外层一圈描边 + 内层深色底。 */
    private void drawSlotBorder(GuiGraphics gui, Slot slot) {
        int sx = this.leftPos + slot.x - 1;
        int sy = this.topPos + slot.y - 1;
        gui.fill(sx, sy, sx + 18, sy + 18, COLOR_SLOT_BORDER);
        gui.fill(sx + 1, sy + 1, sx + 17, sy + 17, COLOR_SLOT_INNER);
    }

    @Override
    protected void renderLabels(GuiGraphics gui, int mouseX, int mouseY) {
        gui.drawString(this.font, this.title, this.titleLabelX, this.titleLabelY, COLOR_TEXT);
        gui.drawString(this.font, this.playerInventoryTitle, this.inventoryLabelX,
                this.inventoryLabelY, COLOR_TEXT);
    }

    @Override
    public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        super.render(gui, mouseX, mouseY, partialTick);
        this.renderTooltip(gui, mouseX, mouseY);
    }

    // ------------------------------------------------------------------
    // 交互
    // ------------------------------------------------------------------

    private boolean isOverRect(int x, int y, int w, int mouseX, int mouseY) {
        int bx = this.leftPos + x;
        int by = this.topPos + y;
        return mouseX >= bx && mouseX < bx + w && mouseY >= by && mouseY < by + OGESawbladeMountMenu.INPUT_H;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && isOverRect(MAX_X, OGESawbladeMountMenu.INPUT_Y, MAX_W, (int) mouseX, (int) mouseY)) {
            amountField.setValue(Integer.toString(this.menu.getStoredExperience()));
            amountField.setFocused(true);
            return true;
        }
        if (button == 0 && isOverRect(TAKE_X, OGESawbladeMountMenu.INPUT_Y, TAKE_W, (int) mouseX, (int) mouseY)) {
            takeRequested();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 257 || keyCode == 335) { // Enter / 小键盘 Enter
            takeRequested();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /** 发“取出经验”报文：留空 = 全额（服务端把 ≤0 视作全额）。 */
    private void takeRequested() {
        PacketDistributor.sendToServer(new OGESawbladeTakeXpPayload(parseAmount()));
    }

    /** 在客户端注册本屏幕到菜单类型。 */
    @SubscribeEvent
    public static void registerScreens(RegisterMenuScreensEvent event) {
        event.register(ModMenus.SAWBLADE_MOUNT.get(), OGESawbladeMountScreen::new);
    }
}
