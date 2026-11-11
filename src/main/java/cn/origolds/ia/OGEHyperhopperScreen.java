package cn.origolds.ia;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 金源超级漏斗的界面：最上面是“开始/暂停/重置”按钮 + 单次漏取上限（深灰文本框），
 * 中间是与金源红石脉冲钟同款的三格时间输入框（时 : 分 : 秒）和一行剩余时间，
 * 下面才是 27 格容量区，再接玩家背包。
 *
 * <h2>控制按钮</h2>
 * <ul>
 *   <li>吸入中：按钮显示 {@code pause.png}，点击暂停（只停吸入，缓存仍会往外推）；</li>
 *   <li>已暂停：按钮显示 {@code start.png}，点击从暂停处继续，并把文本框里的新间隔一并应用；</li>
 *   <li>按住 Shift：按钮显示 {@code reset.png}，点击把剩余时间拉回上限，不改变是否正在吸入。</li>
 * </ul>
 *
 * <h2>两个输入区</h2>
 * <ul>
 *   <li><b>时 : 分 : 秒</b>（黑色框）：决定“漏取物品间隔”，默认 0 : 0 : 0.05 秒（= 1 刻）。
 *       折算规则完全复用 {@link OGEClockTime}：允许小数、分秒超过 60 自动进位、
 *       秒最多两位小数且以 0.05 秒为一档；间隔最小 1 刻。</li>
 *   <li><b>单次漏取上限</b>（深灰框，上方有浅灰小字）：默认 1，只接受 1 以上的整数，
 *       决定每次最多取多少个物品。</li>
 * </ul>
 *
 * <p>玩家只填数没点按钮就退出界面时，{@link #onClose()} 会把两处内容以
 * {@link OGEHyperhopperCommandPayload#ACTION_SAVE} 存回方块。</p>
 */
@EventBusSubscriber(modid = OriGoldsIndustrializedAge.MOD_ID, value = Dist.CLIENT)
public final class OGEHyperhopperScreen extends AbstractContainerScreen<OGEHyperhopperMenu> {

    private static final int COLOR_BG = 0xFF101015;
    private static final int COLOR_BORDER = 0xFFD9A91F;
    private static final int COLOR_SLOT_BORDER = 0xFF6A5A22;
    private static final int COLOR_SLOT_INNER = 0xFF1C1C24;
    private static final int COLOR_BUTTON_BORDER = 0xFF6A5A22;
    private static final int COLOR_BUTTON_BORDER_HOVER = 0xFFD9A91F;
    private static final int COLOR_BUTTON_INNER = 0xFF1C1C24;
    private static final int COLOR_BUTTON_INNER_HOVER = 0xFF2A2A36;
    private static final int COLOR_GOLD = 0xFFD9A91F;
    private static final int COLOR_TEXT = 0xFFFFFFFF;
    private static final int COLOR_PAUSED = 0xFFB0B0BE;
    private static final int COLOR_LIMIT_LABEL = 0xFF9A9AA0;

    /** 顶部居左的控制按钮。 */
    private static final int BUTTON_X = 9;
    private static final int BUTTON_Y = 27;
    private static final int BUTTON_W = 20;
    private static final int BUTTON_H = 20;

    /** 按钮里那张 16×16 图标的边长与内缩。 */
    private static final int ICON_SIZE = 16;
    private static final int ICON_INSET = 2;

    /** 控制按钮的三张图标（与脉冲钟共用）。 */
    private static final Identifier ICON_START = texture("textures/gui/clock_start.png");
    private static final Identifier ICON_PAUSE = texture("textures/gui/clock_pause.png");
    private static final Identifier ICON_RESET = texture("textures/gui/clock_reset.png");

    /** 单次漏取上限：深灰文本框与上方浅灰小字。 */
    private static final int LIMIT_LABEL_Y = 17;
    /** 左缘 = 按钮右缘 + 与黑色文本框相同的间距；右缘与秒框右缘对齐；与按钮同一行。 */
    private static final int LIMIT_X = 45;
    private static final int LIMIT_Y = BUTTON_Y;
    private static final int LIMIT_W = 122;
    private static final int LIMIT_H = 18;

    /** 三个等距同高的时间输入框。 */
    private static final int FIELD_Y = 54;
    private static final int FIELD_W = 42;
    private static final int FIELD_H = 18;
    private static final int FIELD_HOURS_X = 9;
    private static final int FIELD_MINUTES_X = 67;
    private static final int FIELD_SECONDS_X = 125;

    /** 两个“ : ”分隔符的中心位置。 */
    private static final int SEPARATOR_1_X = 59;
    private static final int SEPARATOR_2_X = 117;
    private static final int SEPARATOR_Y = FIELD_Y + 5;

    /** 剩余时间那一行的纵坐标。 */
    private static final int DISPLAY_Y = 76;

    private EditBox hoursField;
    private EditBox minutesField;
    private EditBox secondsField;
    private GreyEditBox limitField;

    /** 是否已经用服务端的初始状态回填过输入框。 */
    private boolean fieldsSeeded;
    private boolean limitSeeded;

    /** 玩家是否亲手改过输入框（退出界面时判断要不要存回去）。 */
    private boolean userEdited;
    private boolean userLimitEdited;

    /** 程序自己写回输入框时置起，避免把手动写入误当成玩家编辑。 */
    private boolean suppressEditTracking;

    /** 退出界面时是否已经存过一次，防止 {@link #onClose()} 与 {@link #removed()} 重复发送。 */
    private boolean pendingInputPersisted;

    /** 服务端同步过来的状态。 */
    private int syncedTotal;
    private int syncedRemaining;
    private boolean syncedRunning;
    private int syncedLimit = OGEHyperhopperBlockEntity.DEFAULT_MAX_PER_TRANSFER;

    public OGEHyperhopperScreen(OGEHyperhopperMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title, OGEHyperhopperMenu.PANEL_WIDTH, OGEHyperhopperMenu.PANEL_HEIGHT);
        this.titleLabelX = 8;
        this.titleLabelY = 6;
        this.inventoryLabelX = 8;
        this.inventoryLabelY = OGEHyperhopperMenu.PLAYER_Y - 10;
    }

    private static Identifier texture(String path) {
        return Identifier.fromNamespaceAndPath(OriGoldsIndustrializedAge.MOD_ID, path);
    }

    @Override
    protected void init() {
        super.init();
        // 窗口缩放会重建控件，先把玩家已经输入的内容记下来再放回去。
        String hours = hoursField != null ? hoursField.getValue() : "0";
        String minutes = minutesField != null ? minutesField.getValue() : "0";
        String seconds = secondsField != null ? secondsField.getValue() : "0.05";
        String limit = limitField != null ? limitField.getValue()
                : Integer.toString(OGEHyperhopperBlockEntity.DEFAULT_MAX_PER_TRANSFER);

        hoursField = addField(FIELD_HOURS_X, 4);
        minutesField = addField(FIELD_MINUTES_X, 4);
        secondsField = addField(FIELD_SECONDS_X, 2);
        limitField = addLimitField();

        setValueQuietly(hoursField, hours);
        setValueQuietly(minutesField, minutes);
        setValueQuietly(secondsField, seconds);
        setValueQuietly(limitField, limit);
    }

    /** 生成一个时间输入框：小时/分钟允许 4 位小数，秒只允许 2 位。 */
    private EditBox addField(int x, int maxFractionDigits) {
        // 26.3 起 EditBox.setFilter 被删除，改用本模组的 OGEFilteredEditBox 承接校验。
        OGEFilteredEditBox box = new OGEFilteredEditBox(this.font, this.leftPos + x, this.topPos + FIELD_Y, FIELD_W, FIELD_H,
                Component.empty());
        box.setMaxLength(9);
        box.setTextColor(COLOR_TEXT);
        box.setTextShadow(false);
        box.setFilter(text -> isNumeric(text, maxFractionDigits));
        // 只有玩家亲手敲进去的字才算“编辑”，程序回填、规范化都不算。
        box.setResponder(text -> {
            if (!suppressEditTracking) {
                userEdited = true;
            }
        });
        addRenderableWidget(box);
        return box;
    }

    /** 生成深灰底的“单次漏取上限”输入框：只接受纯数字。 */
    private GreyEditBox addLimitField() {
        GreyEditBox box = new GreyEditBox(this.font, this.leftPos + LIMIT_X, this.topPos + LIMIT_Y,
                LIMIT_W, LIMIT_H, Component.empty());
        box.setMaxLength(9);
        box.setTextColor(COLOR_TEXT);
        box.setTextShadow(false);
        box.setFilter(OGEHyperhopperScreen::isDigits);
        box.setResponder(text -> {
            if (!suppressEditTracking) {
                userLimitEdited = true;
            }
        });
        addRenderableWidget(box);
        return box;
    }

    /** 程序自己写回输入框：临时关掉编辑追踪，免得被当成玩家输入。 */
    private void setValueQuietly(EditBox box, String value) {
        suppressEditTracking = true;
        try {
            box.setValue(value);
        } finally {
            suppressEditTracking = false;
        }
    }

    /** 输入框过滤器：只放行数字和最多一个小数点，并限制小数位数。 */
    private static boolean isNumeric(String text, int maxFractionDigits) {
        if (text.isEmpty()) {
            return true;
        }
        if (text.length() > 9) {
            return false;
        }
        int dot = text.indexOf('.');
        if (dot != text.lastIndexOf('.')) {
            return false;
        }
        String digits = dot < 0 ? text : text.substring(0, dot) + text.substring(dot + 1);
        for (int i = 0; i < digits.length(); i++) {
            if (!Character.isDigit(digits.charAt(i))) {
                return false;
            }
        }
        return dot < 0 || text.length() - dot - 1 <= maxFractionDigits;
    }

    /** 上限输入框过滤器：只放行数字。 */
    private static boolean isDigits(String text) {
        if (text.length() > 9) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            if (!Character.isDigit(text.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------
    // 绘制
    // ------------------------------------------------------------------

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
        drawControlButton(gui, mouseX, mouseY);
    }

    /** 槽位边框：外层一圈描边 + 内层深色底。 */
    private void drawSlotBorder(GuiGraphicsExtractor gui, Slot slot) {
        int sx = this.leftPos + slot.x - 1;
        int sy = this.topPos + slot.y - 1;
        gui.fill(sx, sy, sx + 18, sy + 18, COLOR_SLOT_BORDER);
        gui.fill(sx + 1, sy + 1, sx + 17, sy + 17, COLOR_SLOT_INNER);
    }

    /** 顶部居左的控制按钮：底框 + 当前该显示的那张图标。 */
    private void drawControlButton(GuiGraphicsExtractor gui, int mouseX, int mouseY) {
        int x = this.leftPos + BUTTON_X;
        int y = this.topPos + BUTTON_Y;
        boolean hovered = isOverControlButton(mouseX, mouseY);
        gui.fill(x, y, x + BUTTON_W, y + BUTTON_H,
                hovered ? COLOR_BUTTON_BORDER_HOVER : COLOR_BUTTON_BORDER);
        gui.fill(x + 1, y + 1, x + BUTTON_W - 1, y + BUTTON_H - 1,
                hovered ? COLOR_BUTTON_INNER_HOVER : COLOR_BUTTON_INNER);
        gui.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, currentIcon(), x + ICON_INSET, y + ICON_INSET, 0.0f, 0.0f,
                ICON_SIZE, ICON_SIZE, ICON_SIZE, ICON_SIZE);
    }

    /** 当前按钮该显示的图标：按住 Shift 一律是“重置”，否则吸入中用“暂停”、其余用“开始”。 */
    private Identifier currentIcon() {
        if (Minecraft.getInstance().hasShiftDown()) {
            return ICON_RESET;
        }
        return syncedRunning ? ICON_PAUSE : ICON_START;
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor gui, int mouseX, int mouseY) {
        gui.text(this.font, this.title, this.titleLabelX, this.titleLabelY, COLOR_TEXT);
        gui.text(this.font, this.playerInventoryTitle, this.inventoryLabelX, this.inventoryLabelY, COLOR_TEXT);

        // 深灰文本框上方的浅灰小字
        drawSmallText(gui, Component.translatable("gui.origolds_ia.hyperhopper_limit"),
                LIMIT_X, LIMIT_LABEL_Y, COLOR_LIMIT_LABEL);

        Component separator = Component.literal(" : ");
        gui.centeredText(this.font, separator, SEPARATOR_1_X, SEPARATOR_Y, COLOR_GOLD);
        gui.centeredText(this.font, separator, SEPARATOR_2_X, SEPARATOR_Y, COLOR_GOLD);
        drawCountdown(gui);
    }

    /** 输入框下方居中显示“下次吸取”的剩余时间；正在吸入与已暂停用不同的前缀与颜色。 */
    private void drawCountdown(GuiGraphicsExtractor gui) {
        if (syncedTotal <= 0) {
            return;
        }
        String key = syncedRunning
                ? "gui.origolds_ia.hyperhopper_next_take"
                : "gui.origolds_ia.hyperhopper_paused_next_take";
        Component line = Component.translatable(key,
                OGEClockTime.formatHms(syncedRemaining),
                OGEClockTime.formatCentis(syncedRemaining));
        drawCenteredScaled(gui, line, this.imageWidth / 2, DISPLAY_Y, this.imageWidth - 8,
                syncedRunning ? COLOR_TEXT : COLOR_PAUSED);
    }

    /** 居中画一行字；太长时整体缩放，保证不越出面板。 */
    private void drawCenteredScaled(GuiGraphicsExtractor gui, Component text, int centerX, int y,
                                    int maxWidth, int color) {
        int width = this.font.width(text);
        if (width <= maxWidth) {
            gui.text(this.font, text, centerX - width / 2, y, color);
            return;
        }
        float scale = (float) maxWidth / width;
        gui.pose().pushMatrix();
        gui.pose().translate(centerX - width * scale / 2.0f, y);
        gui.pose().scale(scale, scale);
        gui.text(this.font, text, 0, 0, color);
        gui.pose().popMatrix();
    }

    /** 以 0.75 倍字号画一行浅灰小字。 */
    private void drawSmallText(GuiGraphicsExtractor gui, Component text, int x, int y, int color) {
        gui.pose().pushMatrix();
        gui.pose().translate(x, y);
        gui.pose().scale(0.75f, 0.75f);
        gui.text(this.font, text, 0, 0, color, false);
        gui.pose().popMatrix();
    }

    /** 空的容量区槽位叠画绑定过滤物品的半透明幽灵。 */
    @Override
    protected void extractSlot(GuiGraphicsExtractor gui, Slot slot, int mouseX, int mouseY) {
        super.extractSlot(gui, slot, mouseX, mouseY);
        if (slot.getItem().isEmpty() && slot.index < this.menu.getHyperhopperSlotCount()) {
            OGESlotGhost.render(gui, slot.x, slot.y, this.menu.getBound());
        }
    }

    /** 屏幕渲染末尾补一次物品提示：1.21.1 的容器基类不会自动画悬停物品的 tooltip。 */
    @Override
    public void extractRenderState(GuiGraphicsExtractor gui, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(gui, mouseX, mouseY, partialTick);
    }

    private boolean isOverControlButton(double mouseX, double mouseY) {
        int x = this.leftPos + BUTTON_X;
        int y = this.topPos + BUTTON_Y;
        return mouseX >= x && mouseX < x + BUTTON_W && mouseY >= y && mouseY < y + BUTTON_H;
    }

    // ------------------------------------------------------------------
    // 输入
    // ------------------------------------------------------------------

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        // 26.3 起鼠标键码为 SDL 编号（左=1、中=2、右=3），不能写死 GLFW 时代的 0。
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && isOverControlButton(event.x(), event.y())) {
            this.setFocused(null);
            onControlButton();
            return true;
        }
        EditBox before = focusedField();
        boolean handled = super.mouseClicked(event, doubleClick);
        if (before != focusedField()) {
            // 点了别的输入框（或点到空白）：把刚离开的那个框里的内容规范化。
            normalizeAll();
        }
        return handled;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        boolean handled = super.keyPressed(event);
        if (event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_NUMPADENTER) {
            normalizeAll();
        }
        return handled;
    }

    @Override
    public void onClose() {
        persistPendingInput();
        super.onClose();
    }

    @Override
    public void removed() {
        persistPendingInput();
        super.removed();
    }

    /** 玩家改过输入框却没点按钮就退出时，把当前内容发给服务端记下来。 */
    private void persistPendingInput() {
        if (pendingInputPersisted) {
            return;
        }
        pendingInputPersisted = true;
        int ticks = 0;
        int limit = 0;
        boolean send = false;
        if (userEdited && hoursField != null) {
            normalizeFields();
            int value = readTicks();
            if (value > 0 && value != syncedTotal) {
                ticks = value;
                send = true;
            }
        }
        if (userLimitEdited && limitField != null) {
            normalizeLimit();
            int value = readLimit();
            if (value != syncedLimit) {
                limit = value;
                send = true;
            }
        }
        if (send) {
            sendCommand(OGEHyperhopperCommandPayload.ACTION_SAVE, ticks, limit);
        }
    }

    private EditBox focusedField() {
        return getFocused() instanceof EditBox box ? box : null;
    }

    /** 把两个输入区的当前内容按规则折算后写回规范形式。 */
    private void normalizeAll() {
        normalizeFields();
        normalizeLimit();
    }

    /** 时间框：进位、小数、四舍五入都在这里。 */
    private void normalizeFields() {
        if (hoursField == null || minutesField == null || secondsField == null) {
            return;
        }
        fieldsSeeded = true;
        int ticks = readTicks();
        setValueQuietly(hoursField, Integer.toString(OGEClockTime.hours(ticks)));
        setValueQuietly(minutesField, Integer.toString(OGEClockTime.minutes(ticks)));
        setValueQuietly(secondsField, OGEClockTime.formatSecondsField(ticks));
    }

    /** 上限框：清空或小于 1 一律规范成 1，超过封顶值则夹住。 */
    private void normalizeLimit() {
        if (limitField == null) {
            return;
        }
        limitSeeded = true;
        setValueQuietly(limitField, Integer.toString(readLimit()));
    }

    private int readTicks() {
        return OGEClockTime.toTicks(
                OGEClockTime.parseDouble(hoursField.getValue()),
                OGEClockTime.parseDouble(minutesField.getValue()),
                OGEClockTime.parseDouble(secondsField.getValue()));
    }

    /** 读上限：空串或解析失败按 1 算，并夹到 [1, 上限封顶]。 */
    private int readLimit() {
        if (limitField == null) {
            return OGEHyperhopperBlockEntity.DEFAULT_MAX_PER_TRANSFER;
        }
        String text = limitField.getValue().trim();
        if (text.isEmpty()) {
            return OGEHyperhopperBlockEntity.DEFAULT_MAX_PER_TRANSFER;
        }
        try {
            return OGEHyperhopperBlockEntity.clampMaxPerTransfer(Integer.parseInt(text));
        } catch (NumberFormatException e) {
            return OGEHyperhopperBlockEntity.DEFAULT_MAX_PER_TRANSFER;
        }
    }

    /**
     * 按钮语义：Shift=重置剩余时间；吸入中=暂停；已暂停=继续（并把新间隔/新上限一并应用）；
     * 未开始=按文本框的间隔开始。点完立刻在本地下发一次乐观显示，等服务端回包再校准。
     */
    private void onControlButton() {
        if (Minecraft.getInstance().hasShiftDown()) {
            normalizeAll();
            int ticks = readTicks();
            int newTotal = ticks > 0 ? ticks : syncedTotal;
            if (newTotal <= 0) {
                return;
            }
            sendCommand(OGEHyperhopperCommandPayload.ACTION_RESET, ticks, readLimit());
            syncedTotal = newTotal;
            syncedRemaining = newTotal;
            return;
        }
        if (syncedRunning) {
            sendCommand(OGEHyperhopperCommandPayload.ACTION_PAUSE, 0, readLimit());
            syncedRunning = false;
            return;
        }
        normalizeAll();
        int ticks = readTicks();
        int limit = readLimit();
        if (syncedTotal <= 0) {
            int newTotal = ticks > 0 ? ticks : OGEHyperhopperBlockEntity.DEFAULT_INTERVAL_TICKS;
            sendCommand(OGEHyperhopperCommandPayload.ACTION_SET, newTotal, limit);
            syncedTotal = newTotal;
            syncedRemaining = newTotal;
        } else {
            sendCommand(OGEHyperhopperCommandPayload.ACTION_RESUME, ticks, limit);
            if (ticks > 0) {
                syncedTotal = ticks;
            }
        }
        syncedRunning = true;
    }

    private void sendCommand(int action, int ticks, int limit) {
        ClientPacketDistributor.sendToServer(new OGEHyperhopperCommandPayload(action, ticks, limit));
    }

    // ------------------------------------------------------------------
    // 服务端同步
    // ------------------------------------------------------------------

    /** 服务端每刻回传的计时状态与上限。 */
    public void onSync(OGEHyperhopperSyncPayload payload) {
        this.syncedTotal = payload.totalTicks();
        this.syncedRemaining = payload.remainingTicks();
        this.syncedRunning = payload.running();
        this.syncedLimit = payload.limit();
        if (!fieldsSeeded && hoursField != null) {
            fieldsSeeded = true;
            if (payload.totalTicks() > 0) {
                setValueQuietly(hoursField, Integer.toString(OGEClockTime.hours(payload.totalTicks())));
                setValueQuietly(minutesField, Integer.toString(OGEClockTime.minutes(payload.totalTicks())));
                setValueQuietly(secondsField, OGEClockTime.formatSecondsField(payload.totalTicks()));
            }
        }
        if (!limitSeeded && limitField != null) {
            limitSeeded = true;
            setValueQuietly(limitField, Integer.toString(payload.limit()));
        }
    }

    /** 报文处理器入口：把同步包投递到当前打开的超级漏斗界面。 */
    public static void handleSync(OGEHyperhopperSyncPayload payload) {
        // 26.3 起屏幕不再挂在 Minecraft 上，改由 mc.gui.screen() 取（原 Minecraft.screen）
        if (Minecraft.getInstance().gui.screen() instanceof OGEHyperhopperScreen screen) {
            screen.onSync(payload);
        }
    }

    /** 在客户端注册本屏幕到菜单类型。 */
    @SubscribeEvent
    public static void registerScreens(RegisterMenuScreensEvent event) {
        event.register(ModMenus.HYPERHOPPER.get(), OGEHyperhopperScreen::new);
    }

    // ------------------------------------------------------------------
    // 深灰底输入框
    // ------------------------------------------------------------------

    /**
     * 深灰底的输入框：原版输入框用的是 {@code widget/text_field} 精灵（近黑），
     * 这里自己画深灰底 + 金色描边，并把文字往右下挪一点做居中。
     * 26.3 起 setFilter 从 EditBox 上删除，所以改挂在 {@link OGEFilteredEditBox} 上。
     */
    private static final class GreyEditBox extends OGEFilteredEditBox {

        private static final int BACKGROUND = 0xFF303038;
        private static final int BORDER = 0xFF6A5A22;

        GreyEditBox(Font font, int x, int y, int width, int height, Component message) {
            super(font, x, y, width, height, message);
            setBordered(false);
        }

        /** 关掉原版描边后，文字会从左侧第 0 像素起步；这里补回 4 像素内缩。 */
        @Override
        public int getInnerWidth() {
            return this.width - 8;
        }

        @Override
        public void extractWidgetRenderState(GuiGraphicsExtractor gui, int mouseX, int mouseY, float partialTick) {
            int x = this.getX();
            int y = this.getY();
            int w = this.getWidth();
            int h = this.getHeight();
            gui.fill(x, y, x + w, y + h, BORDER);
            gui.fill(x + 1, y + 1, x + w - 1, y + h - 1, BACKGROUND);
            gui.pose().pushMatrix();
            gui.pose().translate(4.0f, (h - 8) / 2.0f);
            super.extractWidgetRenderState(gui, mouseX, mouseY, partialTick);
            gui.pose().popMatrix();
        }
    }
}
