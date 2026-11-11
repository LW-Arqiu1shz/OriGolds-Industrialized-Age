package cn.origolds.ia;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

/**
 * 金源炼成台界面：暗金描边面板 + 3 行 27 格原料区 + 玩家背包。
 *
 * <p>标题行右侧显示当前 CRV 读数，标题下方一条进度条表示“距离下一次炼成还差多少”
 * （满条即攒够 {@value OGEChrysopoeiaStationBlockEntity#CRV_PER_REFINEMENT} CRV）。
 * CRV 值由服务端经 {@link OGEChrysopoeiaSyncPayload} 推送，见 {@link #handleSync}。</p>
 */
@EventBusSubscriber(modid = OriGoldsIndustrializedAge.MOD_ID, value = Dist.CLIENT)
public final class OGEChrysopoeiaStationScreen extends AbstractContainerScreen<OGEChrysopoeiaStationMenu> {

    private static final int COLOR_BG = 0xFF101015;
    private static final int COLOR_BORDER = 0xFFD9A91F;
    private static final int COLOR_SLOT_BORDER = 0xFF6A5A22;
    private static final int COLOR_SLOT_INNER = 0xFF1C1C24;
    private static final int COLOR_TEXT = 0xFFFFFFFF;
    private static final int COLOR_DIM = 0xFF9A9AA0;
    private static final int COLOR_BAR_TRACK = 0xFF1C1C24;
    private static final int COLOR_BAR_FILL = 0xFFD9A91F;

    /** CRV 进度条的位置与尺寸（面板相对坐标）。 */
    private static final int BAR_X = 8;
    private static final int BAR_Y = 20;
    private static final int BAR_W = 160;
    private static final int BAR_H = 6;

    /** 服务端同步来的当前 CRV 充能池读数。 */
    private int crv;

    public OGEChrysopoeiaStationScreen(OGEChrysopoeiaStationMenu menu, Inventory playerInventory,
                                       Component title) {
        super(menu, playerInventory, title, OGEChrysopoeiaStationMenu.PANEL_WIDTH,
                OGEChrysopoeiaStationMenu.PANEL_HEIGHT);
        this.titleLabelX = 8;
        this.titleLabelY = 6;
        this.inventoryLabelX = 8;
        this.inventoryLabelY = 88;
    }

    /** 报文处理器入口：把 CRV 同步投递到当前打开的界面。 */
    public static void handleSync(OGEChrysopoeiaSyncPayload payload) {
        // 26.3 起屏幕不再挂在 Minecraft 上，改由 mc.gui.screen() 取（原 Minecraft.screen）
        if (Minecraft.getInstance().gui.screen() instanceof OGEChrysopoeiaStationScreen screen) {
            screen.crv = payload.crv();
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

        int barX = x + BAR_X;
        int barY = y + BAR_Y;
        gui.fill(barX, barY, barX + BAR_W, barY + BAR_H, COLOR_BAR_TRACK);
        int progress = Math.min(this.crv, OGEChrysopoeiaStationBlockEntity.CRV_PER_REFINEMENT);
        int filled = (int) Math.round(progress / (double) OGEChrysopoeiaStationBlockEntity.CRV_PER_REFINEMENT * BAR_W);
        if (filled > 0) {
            gui.fill(barX, barY, barX + filled, barY + BAR_H, COLOR_BAR_FILL);
        }
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor gui, int mouseX, int mouseY) {
        gui.text(this.font, this.title, this.titleLabelX, this.titleLabelY, COLOR_TEXT);
        gui.text(this.font, this.playerInventoryTitle, this.inventoryLabelX, this.inventoryLabelY, COLOR_TEXT);

        String text = this.crv + " CRV";
        int w = this.font.width(text);
        gui.text(this.font, text, this.imageWidth - 8 - w, this.titleLabelY, COLOR_DIM);
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
        event.register(ModMenus.CHRYSOPOEIA_STATION.get(), OGEChrysopoeiaStationScreen::new);
    }
}
