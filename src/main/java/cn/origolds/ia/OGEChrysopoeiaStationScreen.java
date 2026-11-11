package cn.origolds.ia;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

/**
 * 金源炼金站 UI：与虚空抽屉／引力匣同款外观（暗金底色 + 描边 + 槽位边框），
 * 3 行 27 格物品栏全部可用，标题行右侧显示当前炼金凝造价量（CRV），
 * 标题下方一条金色进度条表示“到 2048 的这一轮还差多少”。
 */
@EventBusSubscriber(modid = OriGoldsIndustrializedAge.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class OGEChrysopoeiaStationScreen extends AbstractContainerScreen<OGEChrysopoeiaStationMenu> {

    private static final int COLOR_BG = 0xFF101015;
    private static final int COLOR_BORDER = 0xFFD9A91F;
    private static final int COLOR_SLOT_BORDER = 0xFF6A5A22;
    private static final int COLOR_SLOT_INNER = 0xFF1C1C24;
    private static final int COLOR_TEXT = 0xFFFFFFFF;
    private static final int COLOR_DIM = 0xFF9A9AA0;
    private static final int COLOR_BAR_TRACK = 0xFF1C1C24;
    private static final int COLOR_BAR_FILL = 0xFFD9A91F;

    /** 进度条的几何。 */
    private static final int BAR_X = 8;
    private static final int BAR_Y = 20;
    private static final int BAR_W = 160;
    private static final int BAR_H = 6;

    /** 服务端同步过来的炼金凝造价量。 */
    private int crv;

    public OGEChrysopoeiaStationScreen(OGEChrysopoeiaStationMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = OGEChrysopoeiaStationMenu.PANEL_WIDTH;
        this.imageHeight = OGEChrysopoeiaStationMenu.PANEL_HEIGHT;
        this.titleLabelX = 8;
        this.titleLabelY = 6;
        this.inventoryLabelX = 8;
        this.inventoryLabelY = OGEChrysopoeiaStationMenu.SLOTS_Y
                + OGEChrysopoeiaStationMenu.ROWS * 18 + 2;
    }

    /** 收到服务端的 CRV 同步后刷新读数。 */
    public static void handleSync(OGEChrysopoeiaSyncPayload payload) {
        if (Minecraft.getInstance().screen instanceof OGEChrysopoeiaStationScreen screen) {
            screen.crv = payload.crv();
        }
    }

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

        // 炼金进度条：当前一轮（2048 CRV）的完成度。
        int barX = x + BAR_X;
        int barY = y + BAR_Y;
        gui.fill(barX, barY, barX + BAR_W, barY + BAR_H, COLOR_BAR_TRACK);
        int progress = Math.min(crv, OGEChrysopoeiaStationBlockEntity.CRV_PER_REFINEMENT);
        int filled = (int) Math.round((double) progress
                / OGEChrysopoeiaStationBlockEntity.CRV_PER_REFINEMENT * BAR_W);
        if (filled > 0) {
            gui.fill(barX, barY, barX + filled, barY + BAR_H, COLOR_BAR_FILL);
        }
    }

    @Override
    protected void renderLabels(GuiGraphics gui, int mouseX, int mouseY) {
        gui.drawString(this.font, this.title, this.titleLabelX, this.titleLabelY, COLOR_TEXT);
        gui.drawString(this.font, this.playerInventoryTitle, this.inventoryLabelX, this.inventoryLabelY, COLOR_TEXT);

        String text = crv + " CRV";
        int w = this.font.width(text);
        gui.drawString(this.font, text, this.imageWidth - 8 - w, this.titleLabelY, COLOR_DIM);
    }

    /** 绘制槽位边框：外层一圈描边 + 内层深色底。 */
    private void drawSlotBorder(GuiGraphics gui, Slot slot) {
        int sx = this.leftPos + slot.x - 1;
        int sy = this.topPos + slot.y - 1;
        gui.fill(sx, sy, sx + 18, sy + 18, COLOR_SLOT_BORDER);
        gui.fill(sx + 1, sy + 1, sx + 17, sy + 17, COLOR_SLOT_INNER);
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
        event.register(ModMenus.CHRYSOPOEIA_STATION.get(), OGEChrysopoeiaStationScreen::new);
    }
}
