package cn.origolds.ia;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 金源自动附魔机的界面。大小与铁砧一致，只有三个槽位和左上角标题，
 * 不额外显示其它文字。
 *
 * <p>铁砧原来画箭头的位置是一个可点击的箭头按钮：计划合法时呈金色，
 * 点击即向服务端确认开始本次合并；合并进行中按钮变成进度条。</p>
 *
 * <p>消耗与耗时文字显示在铁砧原来显示经验消耗的位置，格式为
 * “需求OGE：xxxxu / 预计用时：xx:xx:xx”。</p>
 */
@EventBusSubscriber(modid = OriGoldsIndustrializedAge.MOD_ID, value = Dist.CLIENT)
public final class OGEMechEnchanterScreen extends AbstractContainerScreen<OGEMechEnchanterMenu> {

    private static final int COLOR_BG = 0xFF101015;
    private static final int COLOR_BORDER = 0xFFD9A91F;
    private static final int COLOR_SLOT_BORDER = 0xFF6A5A22;
    private static final int COLOR_SLOT_INNER = 0xFF1C1C24;
    private static final int COLOR_TEXT = 0xFFFFFFFF;
    private static final int COLOR_GOLD = 0xFFD9A91F;
    private static final int COLOR_ARROW_OFF = 0xFF4A4A52;
    private static final int COLOR_ARROW_ON = 0xFFD9A91F;
    private static final int COLOR_ACTIVE = 0xFF55DDFF;
    private static final int COLOR_BAR_BG = 0xFF2A2A32;

    /** 箭头按钮在容器坐标系里的位置与大小（与三个槽位边框同高、同一水平线）。 */
    private static final int ARROW_X = 110;
    private static final int ARROW_Y = 23;
    private static final int ARROW_W = 22;
    private static final int ARROW_H = 18;

    /** “➕”连接符的中心（位于第一个与第二个输入框之间，与槽位竖向居中）。 */
    private static final int PLUS_CENTER_X = 47;
    private static final int PLUS_CENTER_Y = 32;

    /** 进度条位置（箭头正下方）。 */
    private static final int BAR_Y = 44;
    private static final int BAR_H = 3;

    /** 需求文字行所在的纵坐标（箭头进度条下方）。 */
    private static final int REQUEST_Y = 50;

    public OGEMechEnchanterScreen(OGEMechEnchanterMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title, OGEMechEnchanterMenu.PANEL_WIDTH, OGEMechEnchanterMenu.PANEL_HEIGHT);
        this.titleLabelX = 8;
        this.titleLabelY = 6;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor gui, int mouseX, int mouseY, float partialTick) {
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

        drawConfirmButton(gui, mouseX, mouseY);
        drawPlus(gui);
        drawProgressBar(gui);
        drawRequirement(gui);
    }

    /** 屏幕渲染末尾补一次物品提示：1.21.1 的容器基类不会自动画悬停物品的 tooltip。 */
    @Override
    public void extractRenderState(GuiGraphicsExtractor gui, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(gui, mouseX, mouseY, partialTick);
    }

    /** 左上角标题：只有这一处文字（以及下方需求文字）。 */
    @Override
    protected void extractLabels(GuiGraphicsExtractor gui, int mouseX, int mouseY) {
        gui.text(this.font, this.title, this.titleLabelX, this.titleLabelY, COLOR_TEXT);
    }

    /** 铁砧箭头位置上的确认按钮：可点时金色、不可用时灰色、进行中青色。 */
    private void drawConfirmButton(GuiGraphicsExtractor gui, int mouseX, int mouseY) {
        int x = this.leftPos + ARROW_X;
        int y = this.topPos + ARROW_Y;
        boolean working = this.menu.isWorking();
        boolean enabled = this.menu.canStart();
        boolean hover = isOverArrow(mouseX, mouseY);
        int color = working ? COLOR_ACTIVE : (enabled ? (hover ? 0xFFFFE28A : COLOR_ARROW_ON) : COLOR_ARROW_OFF);

        gui.fill(x, y, x + ARROW_W, y + ARROW_H, COLOR_SLOT_BORDER);
        gui.fill(x + 1, y + 1, x + ARROW_W - 1, y + ARROW_H - 1, COLOR_SLOT_INNER);
        drawArrow(gui, x + 3, y + 3, ARROW_W - 6, ARROW_H - 6, color);
    }

    /** 画一支向右的箭头：竖三角 + 横杆。 */
    private void drawArrow(GuiGraphicsExtractor gui, int x, int y, int width, int height, int color) {
        int midY = y + height / 2;
        int head = Math.max(3, height / 2);
        for (int i = 0; i < head; i++) {
            int half = head - i;
            gui.fill(x + i, midY - half, x + i + 1, midY + half, color);
        }
        gui.fill(x, midY - 1, x + width, midY + 1, color);
    }

    /** 两个输入框之间的“➕”连接符：纯色块拼出加号，避免字体缺字。 */
    private void drawPlus(GuiGraphicsExtractor gui) {
        int cx = this.leftPos + PLUS_CENTER_X;
        int cy = this.topPos + PLUS_CENTER_Y;
        int arm = 4;
        int thick = 1;
        gui.fill(cx - arm, cy - thick, cx + arm + 1, cy + thick + 1, COLOR_GOLD);
        gui.fill(cx - thick, cy - arm, cx + thick + 1, cy + arm + 1, COLOR_GOLD);
    }

    /** 合并进行中在箭头下方显示一条进度条。 */
    private void drawProgressBar(GuiGraphicsExtractor gui) {
        if (!this.menu.isWorking()) {
            return;
        }
        int total = this.menu.getPlanTicks();
        int remaining = this.menu.getRemainingTicks();
        float progress = total > 0 ? 1.0f - (float) remaining / (float) total : 0.0f;
        int x = this.leftPos + ARROW_X;
        int y = this.topPos + BAR_Y;
        gui.fill(x, y, x + ARROW_W, y + BAR_H, COLOR_BAR_BG);
        int fill = (int) (ARROW_W * Math.max(0.0f, Math.min(1.0f, progress)));
        if (fill > 0) {
            gui.fill(x, y, x + fill, y + BAR_H, COLOR_ACTIVE);
        }
    }

    /** 需求文字：“需求OGE：xxxxu / 预计用时：xx:xx:xx”；开始后用时一栏显示剩余时间。 */
    private void drawRequirement(GuiGraphicsExtractor gui) {
        int cost = this.menu.getPlanCost();
        boolean working = this.menu.isWorking();
        // 待机时显示本次预计耗时，开工后实时显示剩余时间。
        int ticks = working ? this.menu.getRemainingTicks() : this.menu.getPlanTicks();
        if (cost <= 0 || (!working && ticks <= 0)) {
            return;
        }
        String time = OGEMechEnchanterBlockEntity.formatDuration(Math.max(0, ticks) / 20);
        Component line = Component.translatable("gui.origolds_ia.mech_enchanter_requirement", cost, time);
        int width = this.font.width(line);
        int available = this.imageWidth - 8;
        if (width <= available) {
            gui.text(this.font, line, this.leftPos + (imageWidth - width) / 2, this.topPos + REQUEST_Y,
                    COLOR_TEXT);
            return;
        }
        // 面板收窄后长文案（例如其它语言）按比例缩小，保证始终留在边框内。
        float scale = (float) available / width;
        int scaledWidth = Math.round(width * scale);
        gui.pose().pushMatrix();
        gui.pose().translate(this.leftPos + (imageWidth - scaledWidth) / 2.0f, this.topPos + REQUEST_Y);
        gui.pose().scale(scale, scale);
        gui.text(this.font, line, 0, 0, COLOR_TEXT);
        gui.pose().popMatrix();
    }

    /** 鼠标是否落在箭头按钮上（屏幕坐标）。 */
    private boolean isOverArrow(double mouseX, double mouseY) {
        int x = this.leftPos + ARROW_X;
        int y = this.topPos + ARROW_Y;
        return mouseX >= x && mouseX < x + ARROW_W && mouseY >= y && mouseY < y + ARROW_H;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == 0 && isOverArrow(event.x(), event.y())) {
            if (this.menu.canStart()) {
                ClientPacketDistributor.sendToServer(new OGEMechEnchanterStartPayload());
            }
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    /** 绘制槽位边框：外层一圈描边 + 内层深色底。 */
    private void drawSlotBorder(GuiGraphicsExtractor gui, Slot slot) {
        int sx = this.leftPos + slot.x - 1;
        int sy = this.topPos + slot.y - 1;
        gui.fill(sx, sy, sx + 18, sy + 18, COLOR_SLOT_BORDER);
        gui.fill(sx + 1, sy + 1, sx + 17, sy + 17, COLOR_SLOT_INNER);
    }

    /** 在客户端注册本屏幕到菜单类型。 */
    @SubscribeEvent
    public static void registerScreens(RegisterMenuScreensEvent event) {
        event.register(ModMenus.MECH_ENCHANTER.get(), OGEMechEnchanterScreen::new);
    }
}
