package cn.origolds.ia;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import org.lwjgl.glfw.GLFW;

/**
 * 金源锯片台界面：武器/锯片槽、储经验读数（xporb 图标 + 数字）、
 * 数量输入框、"MAX" 填充按钮和"取出"按钮，下面是玩家背包。
 *
 * <p>储经验由服务端经 {@link OGESawbladeMountSyncPayload} 推进
 * {@link OGESawbladeMountMenu#setStoredExperienceClient(int)}，这里只画菜单缓存值。
 * 点"取出"（或焦点在输入框时按回车/小键盘回车）发送 {@link OGESawbladeTakeXpPayload}；
 * "MAX" 把输入框填成当前储量并聚焦。输入框只收数字，最多 10 位。</p>
 *
 * <p>26.x 界面渲染走提取管线：面板与按钮画在 {@link #extractBackground}，
 * 文字标题画在 {@link #extractLabels}，不再覆写 {@code render}/{@code renderTooltip}。</p>
 */
@EventBusSubscriber(modid = OriGoldsIndustrializedAge.MOD_ID, value = Dist.CLIENT)
public final class OGESawbladeMountScreen extends AbstractContainerScreen<OGESawbladeMountMenu> {

    private static final int COLOR_BG = 0xFF101015;
    private static final int COLOR_BORDER = 0xFFD9A91F;
    private static final int COLOR_SLOT_BORDER = 0xFF6A5A22;
    private static final int COLOR_SLOT_INNER = 0xFF1C1C24;
    private static final int COLOR_TEXT = 0xFFFFFFFF;
    private static final int COLOR_BUTTON_BORDER = 0xFF6A5A22;
    private static final int COLOR_BUTTON_INNER = 0xFF1C1C24;
    private static final int COLOR_BUTTON_BORDER_HOVER = 0xFFD9A91F;
    private static final int COLOR_BUTTON_INNER_HOVER = 0xFF2A2A36;

    /** 储经验读数用的经验球图标。 */
    private static final Identifier XPORB_ICON =
            Identifier.fromNamespaceAndPath(OriGoldsIndustrializedAge.MOD_ID, "textures/gui/xporb.png");

    /** 输入框：位置与宽度（与菜单常量对齐）。 */
    private static final int FIELD_X = 21;
    private static final int FIELD_W = 58;
    /** MAX 按钮。 */
    private static final int MAX_X = 83;
    private static final int MAX_W = 26;
    /** "取出"按钮。 */
    private static final int TAKE_X = 113;
    private static final int TAKE_W = 42;

    private EditBox amountField;

    public OGESawbladeMountScreen(OGESawbladeMountMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title, OGESawbladeMountMenu.PANEL_WIDTH, OGESawbladeMountMenu.PANEL_HEIGHT);
        this.titleLabelX = 8;
        this.titleLabelY = 6;
        this.inventoryLabelX = 8;
        this.inventoryLabelY = 124;
    }

    @Override
    protected void init() {
        super.init();
        this.amountField = new EditBox(this.font,
                this.leftPos + FIELD_X, this.topPos + OGESawbladeMountMenu.INPUT_Y, FIELD_W,
                OGESawbladeMountMenu.INPUT_H, Component.translatable("gui.origolds_ia.sawblade_mount.amount"));
        this.amountField.setMaxLength(10);
        this.amountField.setTextColor(COLOR_TEXT);
        this.amountField.setTextShadow(false);
        this.amountField.setFilter(text -> text.isEmpty() || text.length() <= 10
                && text.chars().allMatch(Character::isDigit));
        this.amountField.setValue("");
        this.addRenderableWidget(this.amountField);
    }

    /** 输入框里的数字；空或非法按 0（服务端会把 0 当作"全部取出"处理前的请求量）。 */
    private int parseAmount() {
        String text = this.amountField.getValue();
        if (text.isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    /** 服务端同步储经验进来时由报文处理器调用。 */
    public static void handleSync(OGESawbladeMountSyncPayload payload) {
        if (Minecraft.getInstance().screen instanceof OGESawbladeMountScreen screen) {
            screen.menu.setStoredExperienceClient(payload.storedExperience());
        }
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor gui, int mouseX, int mouseY, float partialTick) {
        int x = this.leftPos;
        int y = this.topPos;
        gui.fill(x, y, x + this.imageWidth, y + this.imageHeight, COLOR_BG);
        gui.fill(x, y, x + this.imageWidth, y + 1, COLOR_BORDER);
        gui.fill(x, y + this.imageHeight - 1, x + this.imageWidth, y + this.imageHeight, COLOR_BORDER);
        gui.fill(x, y, x + 1, y + this.imageHeight, COLOR_BORDER);
        gui.fill(x + this.imageWidth - 1, y, x + this.imageWidth, y + this.imageHeight, COLOR_BORDER);

        for (Slot slot : this.menu.slots) {
            drawSlotBorder(gui, slot);
        }

        drawExperience(gui);
        drawButton(gui, MAX_X, MAX_W, OGESawbladeMountMenu.INPUT_Y, Component.literal("MAX"), mouseX, mouseY);
        drawButton(gui, TAKE_X, TAKE_W, OGESawbladeMountMenu.INPUT_Y,
                Component.translatable("gui.origolds_ia.sawblade_mount.take"), mouseX, mouseY);
    }

    /** 居中一行：xporb 图标 + 储经验数字。 */
    private void drawExperience(GuiGraphicsExtractor gui) {
        String text = Integer.toString(this.menu.getStoredExperience());
        int textWidth = this.font.width(text);
        int groupWidth = 20 + textWidth;
        int startX = (this.imageWidth - groupWidth) / 2;
        int rowY = OGESawbladeMountMenu.XP_Y;
        gui.blit(RenderPipelines.GUI_TEXTURED, XPORB_ICON, this.leftPos + startX, this.topPos + rowY,
                0.0F, 0.0F, 16, 16, 16, 16);
        gui.text(this.font, text, this.leftPos + startX + 16 + 4, this.topPos + rowY + (16 - 9) / 2, COLOR_TEXT);
    }

    /** 画一个手绘按钮：悬停亮金边深底，否则暗金边。 */
    private void drawButton(GuiGraphicsExtractor gui, int x, int w, int y, Component label, int mouseX, int mouseY) {
        boolean hovered = isOverRect(x, y, w, mouseX, mouseY);
        int bx = this.leftPos + x;
        int by = this.topPos + y;
        gui.fill(bx, by, bx + w, by + 18, hovered ? COLOR_BUTTON_BORDER_HOVER : COLOR_BUTTON_BORDER);
        gui.fill(bx + 1, by + 1, bx + w - 1, by + 18 - 1, hovered ? COLOR_BUTTON_INNER_HOVER : COLOR_BUTTON_INNER);
        String text = label.getString();
        int tw = this.font.width(text);
        gui.text(this.font, text, bx + (w - tw) / 2, by + (18 - 9) / 2, COLOR_TEXT);
    }

    /** 槽位边框：外圈描边 + 内层深底。 */
    private void drawSlotBorder(GuiGraphicsExtractor gui, Slot slot) {
        int sx = this.leftPos + slot.x - 1;
        int sy = this.topPos + slot.y - 1;
        gui.fill(sx, sy, sx + 18, sy + 18, COLOR_SLOT_BORDER);
        gui.fill(sx + 1, sy + 1, sx + 17, sy + 17, COLOR_SLOT_INNER);
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor gui, int mouseX, int mouseY) {
        gui.text(this.font, this.title, this.titleLabelX, this.titleLabelY, COLOR_TEXT);
        gui.text(this.font, this.playerInventoryTitle, this.inventoryLabelX, this.inventoryLabelY, COLOR_TEXT);
    }

    /** 命中检测：面板内 (x, y, w, 18) 的按钮矩形。 */
    private boolean isOverRect(int x, int y, int w, double mouseX, double mouseY) {
        int bx = this.leftPos + x;
        int by = this.topPos + y;
        return mouseX >= bx && mouseX < bx + w && mouseY >= by && mouseY < by + 18;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT && isOverRect(MAX_X, OGESawbladeMountMenu.INPUT_Y, MAX_W,
                event.x(), event.y())) {
            this.amountField.setValue(Integer.toString(this.menu.getStoredExperience()));
            this.setFocused(this.amountField);
            return true;
        } else if (event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT && isOverRect(TAKE_X, OGESawbladeMountMenu.INPUT_Y,
                TAKE_W, event.x(), event.y())) {
            takeRequested();
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER) {
            takeRequested();
            return true;
        }
        return super.keyPressed(event);
    }

    /** 把"取出"请求发给服务端（0 = 全部，由服务端菜单处理）。 */
    private void takeRequested() {
        ClientPacketDistributor.sendToServer(new OGESawbladeTakeXpPayload(this.parseAmount()));
    }

    @SubscribeEvent
    public static void registerScreens(RegisterMenuScreensEvent event) {
        event.register(ModMenus.SAWBLADE_MOUNT.get(), OGESawbladeMountScreen::new);
    }
}
