package cn.origolds.ia;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/**
 * 金源红石脉冲钟的计时界面：顶部居中一个控制按钮，下面一行三个等距、同高的
 * 文本输入框（时 : 分 : 秒），输入框正下方居中显示一行倒计时。
 *
 * <h2>顶部按钮</h2>
 * <ul>
 *   <li>计时中：按钮变成 {@code pause.png} 的样子，点击暂停，时间停在原地；</li>
 *   <li>已暂停：按钮回到 {@code start.png} 的样子，点击从暂停处继续；</li>
 *   <li>按住 Shift：无论当时是开始还是暂停，按钮都变成 {@code reset.png}，点击把
 *       剩余时间重置回上限，<b>不改变时间是否流逝</b>；</li>
 *   <li>未计时：显示 {@code start.png}，点击按输入框里的时长开始计时。</li>
 * </ul>
 *
 * <h2>输入规则</h2>
 * <ul>
 *   <li>点击输入框即可选中并输入数字；小时/分钟框允许小数，失焦或按回车时自动
 *       换算成时:分:秒（例如小时框输入 1.5 → 1:30:00）；</li>
 *   <li>分钟/秒框输入超过 60 的数字自动进位（例如秒框输入 90 → 0:01:30）；</li>
 *   <li>秒框最多两位小数、以 0.05 秒（1 刻）为一档，其它小数四舍五入到最近的
 *       0.05 秒（例如 0.06 → 0.05）。</li>
 * </ul>
 *
 * <p>点按钮后把“动作 + 折算好的刻数”发给服务端（{@link OGEClockCommandPayload}），
 * 并先在本地下发一次乐观显示；服务端随后通过 {@link OGEClockSyncPayload} 每刻回传
 * 剩余时间。三个框都是 0（或全空）时点按钮表示停止计时。</p>
 *
 * <p>玩家只是填了数没点按钮就退出界面时，{@link #onClose()} 会把填的时长以
 * {@link OGEClockCommandPayload#ACTION_SAVE} 存回方块，下次打开还能看到，且不会
 * 因此开始计时。</p>
 */
@EventBusSubscriber(modid = OriGoldsIndustrializedAge.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class OGEClockScreen extends AbstractContainerScreen<OGEClockMenu> {

    private static final int COLOR_BG = 0xFF101015;
    private static final int COLOR_BORDER = 0xFFD9A91F;
    private static final int COLOR_BUTTON_BORDER = 0xFF6A5A22;
    private static final int COLOR_BUTTON_BORDER_HOVER = 0xFFD9A91F;
    private static final int COLOR_BUTTON_INNER = 0xFF1C1C24;
    private static final int COLOR_BUTTON_INNER_HOVER = 0xFF2A2A36;
    private static final int COLOR_GOLD = 0xFFD9A91F;
    private static final int COLOR_TEXT = 0xFFFFFFFF;
    private static final int COLOR_PAUSED = 0xFFB0B0BE;

    /** 顶部居中的控制按钮。 */
    private static final int BUTTON_X = 78;
    private static final int BUTTON_Y = 8;
    private static final int BUTTON_W = 20;
    private static final int BUTTON_H = 20;

    /** 按钮里那张 16×16 图标的边长与内缩。 */
    private static final int ICON_SIZE = 16;
    private static final int ICON_INSET = 2;

    /** 控制按钮的三张图标。 */
    private static final ResourceLocation ICON_START = texture("textures/gui/clock_start.png");
    private static final ResourceLocation ICON_PAUSE = texture("textures/gui/clock_pause.png");
    private static final ResourceLocation ICON_RESET = texture("textures/gui/clock_reset.png");

    /** 三个等距同高的输入框。 */
    private static final int FIELD_Y = 38;
    private static final int FIELD_W = 42;
    private static final int FIELD_H = 18;
    private static final int FIELD_HOURS_X = 9;
    private static final int FIELD_MINUTES_X = 67;
    private static final int FIELD_SECONDS_X = 125;

    /** 两个“ : ”分隔符的中心位置。 */
    private static final int SEPARATOR_1_X = 59;
    private static final int SEPARATOR_2_X = 117;
    private static final int SEPARATOR_Y = FIELD_Y + 5;

    /** 倒计时那一行的纵坐标。 */
    private static final int DISPLAY_Y = 62;

    private EditBox hoursField;
    private EditBox minutesField;
    private EditBox secondsField;

    /** 是否已经用服务端的初始状态回填过输入框。 */
    private boolean fieldsSeeded;

    /** 玩家是否亲手改过输入框（用来判断退出界面时要不要把内容存回去）。 */
    private boolean userEdited;

    /** 程序自己写回输入框时置起，避免把手动写入误当成玩家编辑。 */
    private boolean suppressEditTracking;

    /** 退出界面时是否已经存过一次，防止 {@link #onClose()} 与 {@link #removed()} 重复发送。 */
    private boolean pendingInputPersisted;

    /** 服务端同步过来的状态：当前周期、距离下次脉冲的刻数、时间是否正在流逝。 */
    private int syncedTotal;
    private int syncedRemaining;
    private boolean syncedRunning;

    public OGEClockScreen(OGEClockMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = OGEClockMenu.PANEL_WIDTH;
        this.imageHeight = OGEClockMenu.PANEL_HEIGHT;
    }

    private static ResourceLocation texture(String path) {
        return ResourceLocation.fromNamespaceAndPath(OriGoldsIndustrializedAge.MOD_ID, path);
    }

    @Override
    protected void init() {
        super.init();
        // 窗口缩放会重建控件，先把玩家已经输入的内容记下来再放回去。
        String hours = hoursField != null ? hoursField.getValue() : "0";
        String minutes = minutesField != null ? minutesField.getValue() : "0";
        String seconds = secondsField != null ? secondsField.getValue() : "1";
        hoursField = addField(FIELD_HOURS_X, 4);
        minutesField = addField(FIELD_MINUTES_X, 4);
        secondsField = addField(FIELD_SECONDS_X, 2);
        setValueQuietly(hoursField, hours);
        setValueQuietly(minutesField, minutes);
        setValueQuietly(secondsField, seconds);
    }

    /** 生成一个输入框：小时/分钟允许 4 位小数，秒只允许 2 位。 */
    private EditBox addField(int x, int maxFractionDigits) {
        EditBox box = new EditBox(this.font, this.leftPos + x, this.topPos + FIELD_Y, FIELD_W, FIELD_H,
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

    @Override
    protected void renderBg(GuiGraphics gui, float partialTick, int mouseX, int mouseY) {
        int x = this.leftPos;
        int y = this.topPos;
        gui.fill(x, y, x + this.imageWidth, y + this.imageHeight, COLOR_BG);
        gui.fill(x, y, x + this.imageWidth, y + 1, COLOR_BORDER);
        gui.fill(x, y + this.imageHeight - 1, x + this.imageWidth, y + this.imageHeight, COLOR_BORDER);
        gui.fill(x, y, x + 1, y + this.imageHeight, COLOR_BORDER);
        gui.fill(x + this.imageWidth - 1, y, x + this.imageWidth, y + this.imageHeight, COLOR_BORDER);
        drawControlButton(gui, mouseX, mouseY);
    }

    @Override
    protected void renderLabels(GuiGraphics gui, int mouseX, int mouseY) {
        Component separator = Component.literal(" : ");
        gui.drawCenteredString(this.font, separator, SEPARATOR_1_X, SEPARATOR_Y, COLOR_GOLD);
        gui.drawCenteredString(this.font, separator, SEPARATOR_2_X, SEPARATOR_Y, COLOR_GOLD);
        drawCountdown(gui);
    }

    /** 顶部居中的控制按钮：底框 + 当前该显示的那张图标。 */
    private void drawControlButton(GuiGraphics gui, int mouseX, int mouseY) {
        int x = this.leftPos + BUTTON_X;
        int y = this.topPos + BUTTON_Y;
        boolean hovered = isOverControlButton(mouseX, mouseY);
        gui.fill(x, y, x + BUTTON_W, y + BUTTON_H,
                hovered ? COLOR_BUTTON_BORDER_HOVER : COLOR_BUTTON_BORDER);
        gui.fill(x + 1, y + 1, x + BUTTON_W - 1, y + BUTTON_H - 1,
                hovered ? COLOR_BUTTON_INNER_HOVER : COLOR_BUTTON_INNER);
        gui.blit(currentIcon(), x + ICON_INSET, y + ICON_INSET, 0.0f, 0.0f,
                ICON_SIZE, ICON_SIZE, ICON_SIZE, ICON_SIZE);
    }

    /**
     * 当前按钮该显示的图标：按住 Shift 一律是“重置”，否则计时中用“暂停”、
     * 其余情况用“开始”。
     */
    private ResourceLocation currentIcon() {
        if (Screen.hasShiftDown()) {
            return ICON_RESET;
        }
        return syncedRunning ? ICON_PAUSE : ICON_START;
    }

    /** 输入框下方居中显示倒计时；正在流逝与已暂停用不同的前缀与颜色。 */
    private void drawCountdown(GuiGraphics gui) {
        if (syncedTotal <= 0) {
            return;
        }
        String key = syncedRunning
                ? "gui.origolds_ia.clock_next_pulse"
                : "gui.origolds_ia.clock_paused_next_pulse";
        Component line = Component.translatable(key,
                OGEClockTime.formatHms(syncedRemaining),
                OGEClockTime.formatCentis(syncedRemaining));
        drawCenteredScaled(gui, line, this.imageWidth / 2, DISPLAY_Y, this.imageWidth - 8,
                syncedRunning ? COLOR_TEXT : COLOR_PAUSED);
    }

    /** 居中画一行字；太长时整体缩放，保证不越出面板。 */
    private void drawCenteredScaled(GuiGraphics gui, Component text, int centerX, int y,
                                    int maxWidth, int color) {
        int width = this.font.width(text);
        if (width <= maxWidth) {
            gui.drawString(this.font, text, centerX - width / 2, y, color);
            return;
        }
        float scale = (float) maxWidth / width;
        gui.pose().pushPose();
        gui.pose().translate(centerX - width * scale / 2.0f, y, 0.0f);
        gui.pose().scale(scale, scale, 1.0f);
        gui.drawString(this.font, text, 0, 0, color);
        gui.pose().popPose();
    }

    private boolean isOverControlButton(double mouseX, double mouseY) {
        int x = this.leftPos + BUTTON_X;
        int y = this.topPos + BUTTON_Y;
        return mouseX >= x && mouseX < x + BUTTON_W && mouseY >= y && mouseY < y + BUTTON_H;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && isOverControlButton(mouseX, mouseY)) {
            this.setFocused(null);
            onControlButton();
            return true;
        }
        EditBox before = focusedField();
        boolean handled = super.mouseClicked(mouseX, mouseY, button);
        if (before != focusedField()) {
            // 点了别的输入框（或点到空白）：把刚离开的那个框里的内容规范化。
            normalizeFields();
        }
        return handled;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        boolean handled = super.keyPressed(keyCode, scanCode, modifiers);
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            normalizeFields();
        }
        return handled;
    }

    /**
     * 退出界面前先把输入框里填的内容存回服务端。
     *
     * <p>必须赶在 {@code super.onClose()} 之前发：关闭容器时客户端会先送出一个
     * {@code ServerboundContainerClosePacket}，服务端一收到就把当前菜单换回物品栏，
     * 之后再到的存档报文会因为“当前菜单不再是脉冲钟”而被丢掉。</p>
     */
    @Override
    public void onClose() {
        persistPendingInput();
        super.onClose();
    }

    /** 不是走 Esc / E 关闭的路径（例如被服务端强制关闭）也兜一次底。 */
    @Override
    public void removed() {
        persistPendingInput();
        super.removed();
    }

    /**
     * 玩家改过输入框却没点按钮就退出时，把当前折算好的时长发给服务端记下来。
     * 只存正数，且与已记录的周期不同才发；存下来的时长不改变时间是否正在流逝。
     */
    private void persistPendingInput() {
        if (pendingInputPersisted) {
            return;
        }
        pendingInputPersisted = true;
        if (!userEdited || hoursField == null) {
            return;
        }
        normalizeFields();
        int ticks = readTicks();
        if (ticks > 0 && ticks != syncedTotal) {
            sendCommand(OGEClockCommandPayload.ACTION_SAVE, ticks);
        }
    }

    private EditBox focusedField() {
        return getFocused() instanceof EditBox box ? box : null;
    }

    /** 把三个框里的内容按规则折算后写回规范形式（进位、小数、四舍五入都在这里）。 */
    private void normalizeFields() {
        if (hoursField == null || minutesField == null || secondsField == null) {
            return;
        }
        // 玩家已经动过输入框，之后服务端的初始状态就不要再覆盖他填的内容。
        fieldsSeeded = true;
        int ticks = readTicks();
        setValueQuietly(hoursField, Integer.toString(OGEClockTime.hours(ticks)));
        setValueQuietly(minutesField, Integer.toString(OGEClockTime.minutes(ticks)));
        setValueQuietly(secondsField, OGEClockTime.formatSecondsField(ticks));
    }

    private int readTicks() {
        return OGEClockTime.toTicks(
                OGEClockTime.parseDouble(hoursField.getValue()),
                OGEClockTime.parseDouble(minutesField.getValue()),
                OGEClockTime.parseDouble(secondsField.getValue()));
    }

    /**
     * 顶按钮的语义：Shift=重置；计时中=暂停；已暂停=继续（三个框都清空/填 0 时改为停止）；
     * 未计时=按输入框的时长开始。点完立刻在本地下发一次乐观显示，等服务端回包再校准。
     */
    private void onControlButton() {
        if (Screen.hasShiftDown()) {
            normalizeFields();
            int ticks = readTicks();
            int newTotal = ticks > 0 ? ticks : syncedTotal;
            if (newTotal <= 0) {
                return;
            }
            sendCommand(OGEClockCommandPayload.ACTION_RESET, ticks);
            syncedTotal = newTotal;
            syncedRemaining = newTotal;
            return;
        }
        if (syncedRunning) {
            sendCommand(OGEClockCommandPayload.ACTION_PAUSE, 0);
            syncedRunning = false;
            return;
        }
        if (syncedTotal > 0) {
            if (readTicks() <= 0) {
                // 暂停中把三个框清空/填 0 再点按钮：停止计时。
                sendCommand(OGEClockCommandPayload.ACTION_SET, 0);
                syncedTotal = 0;
                syncedRemaining = 0;
                syncedRunning = false;
            } else {
                sendCommand(OGEClockCommandPayload.ACTION_RESUME, 0);
                syncedRunning = true;
            }
            return;
        }
        normalizeFields();
        int ticks = readTicks();
        sendCommand(OGEClockCommandPayload.ACTION_SET, ticks);
        syncedTotal = ticks;
        syncedRemaining = ticks;
        syncedRunning = ticks > 0;
    }

    private void sendCommand(int action, int ticks) {
        PacketDistributor.sendToServer(new OGEClockCommandPayload(action, ticks));
    }

    /** 服务端每刻回传的倒计时状态。 */
    public void onSync(OGEClockSyncPayload payload) {
        this.syncedTotal = payload.totalTicks();
        this.syncedRemaining = payload.remainingTicks();
        this.syncedRunning = payload.running();
        if (!fieldsSeeded && hoursField != null) {
            fieldsSeeded = true;
            if (payload.totalTicks() > 0) {
                setValueQuietly(hoursField, Integer.toString(OGEClockTime.hours(payload.totalTicks())));
                setValueQuietly(minutesField, Integer.toString(OGEClockTime.minutes(payload.totalTicks())));
                setValueQuietly(secondsField, OGEClockTime.formatSecondsField(payload.totalTicks()));
            }
        }
    }

    /** 报文处理器入口：把同步包投递到当前打开的钟界面。 */
    public static void handleSync(OGEClockSyncPayload payload) {
        if (Minecraft.getInstance().screen instanceof OGEClockScreen screen) {
            screen.onSync(payload);
        }
    }

    /** 在客户端注册本屏幕到菜单类型。 */
    @SubscribeEvent
    public static void registerScreens(RegisterMenuScreensEvent event) {
        event.register(ModMenus.REDSTONE_PULSE_CLOCK.get(), OGEClockScreen::new);
    }
}
