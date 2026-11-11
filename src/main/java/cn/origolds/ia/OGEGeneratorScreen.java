package cn.origolds.ia;

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
 * 金源能量发生器（火驱/魔驱）的原料 UI。
 *
 * <p>布局：左上为原料槽（燃料/魔金之核），右列从上到下依次为“金源能量”标签、
 * 能量条、储量数值、工作状态，以及（火驱/魔驱）燃烧进度条。所有数值通过菜单
 * {@link net.minecraft.world.inventory.DataSlot} 每 tick 从服务器同步刷新；
 * 所有槽位都画了边框，避免“按键/槽位消失、文字重叠”的问题。</p>
 */
@EventBusSubscriber(modid = OriGoldsIndustrializedAge.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class OGEGeneratorScreen extends AbstractContainerScreen<OGEGeneratorMenu> {

    private static final int COLOR_BG = 0xFF101015;
    private static final int COLOR_BORDER = 0xFFD9A91F;
    private static final int COLOR_SLOT_BORDER = 0xFF6A5A22;
    private static final int COLOR_SLOT_INNER = 0xFF1C1C24;
    private static final int COLOR_BAR_BG = 0xFF2A2A32;
    private static final int COLOR_GOLD = 0xFFD9A91F;
    private static final int COLOR_BURN = 0xFFFF8C1A;
    private static final int COLOR_TEXT = 0xFFFFFFFF;
    private static final int COLOR_DIM = 0xFF9A9AA0;
    private static final int COLOR_GREEN = 0xFF55FF55;
    private static final int COLOR_RED = 0xFFFF5555;

    public OGEGeneratorScreen(OGEGeneratorMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        // 面板加宽到 228：左列（原料槽+标签）与右列（能量信息）各占一边，
        // 长英文标签（如 "Magigold Core"）不再与数值行横向重叠。
        this.imageWidth = 228;
        this.imageHeight = 166;
        this.titleLabelX = 8;
        this.titleLabelY = 6;
        this.inventoryLabelX = 30;
        this.inventoryLabelY = 72;
    }

    /** 绘制容器面板：背景、边框、槽位边界、能量条、工作状态与燃烧进度条。 */
    @Override
    protected void renderBg(GuiGraphics gui, float partialTick, int mouseX, int mouseY) {
        int x = this.leftPos;
        int y = this.topPos;

        // 背景与金色边框
        gui.fill(x, y, x + imageWidth, y + imageHeight, COLOR_BG);
        gui.fill(x, y, x + imageWidth, y + 1, COLOR_BORDER);
        gui.fill(x, y + imageHeight - 1, x + imageWidth, y + imageHeight, COLOR_BORDER);
        gui.fill(x, y, x + 1, y + imageHeight, COLOR_BORDER);
        gui.fill(x + imageWidth - 1, y, x + imageWidth, y + imageHeight, COLOR_BORDER);

        // 所有槽位边界（原料槽 + 玩家背包）
        for (Slot slot : this.menu.slots) {
            drawSlotBorder(gui, slot);
        }

        boolean magic = isMagic();
        // 原料槽标签
        if (this.menu.isFueled()) {
            gui.drawString(this.font, Component.translatable(magic ? "gui.origolds_ia.oge_core" : "gui.origolds_ia.oge_fuel"),
                    x + 8, y + 40, COLOR_DIM);
        }

        // 右列：金源能量（起点 x+96，与左列的原料槽/标签完全错开）
        int barX = x + 96;
        int barY = y + 30;
        int barW = 120;
        int barH = 8;
        gui.drawString(this.font, Component.translatable("gui.origolds_ia.oge_energy"), barX, barY - 10, COLOR_DIM);
        int stored = this.menu.getStoredOGE();
        int capacity = this.menu.getCapacity();
        drawBar(gui, barX, barY, barW, barH, capacity > 0 ? (float) stored / capacity : 0.0f, COLOR_GOLD);
        gui.drawString(this.font, stored + "u / " + capacity + "u", barX, barY + 10, COLOR_TEXT);

        // 工作状态
        boolean working = this.menu.isWorking();
        Component status = Component.translatable(working ? "gui.origolds_ia.oge_working" : "gui.origolds_ia.oge_idle");
        gui.drawString(this.font, status, barX, barY + 20, working ? COLOR_GREEN : COLOR_RED);

        // 燃烧进度条（火驱/魔驱）
        if (this.menu.isFueled()) {
            drawBar(gui, barX, barY + 30, barW, 5, this.menu.getBurnProgress(), COLOR_BURN);
        }
    }

    /** 画一个横向进度条：深色底 + 按比例填充。 */
    private void drawBar(GuiGraphics gui, int x, int y, int width, int height, float fraction, int fillColor) {
        gui.fill(x, y, x + width, y + height, COLOR_BAR_BG);
        int fill = (int) (width * Math.max(0.0f, Math.min(1.0f, fraction)));
        if (fill > 0) {
            gui.fill(x, y, x + fill, y + height, fillColor);
        }
    }

    /** 绘制槽位边框：外层一圈描边 + 内层深色底，让槽位边界清晰可见。 */
    private void drawSlotBorder(GuiGraphics gui, Slot slot) {
        int sx = this.leftPos + slot.x - 1;
        int sy = this.topPos + slot.y - 1;
        gui.fill(sx, sy, sx + 18, sy + 18, COLOR_SLOT_BORDER);
        gui.fill(sx + 1, sy + 1, sx + 17, sy + 17, COLOR_SLOT_INNER);
    }

    private boolean isMagic() {
        return this.menu.getBlockEntity() != null
                && this.menu.getBlockEntity().getKind() == OGEMachineKind.MAGIC;
    }

    @Override
    protected void renderLabels(GuiGraphics gui, int mouseX, int mouseY) {
        gui.drawString(this.font, this.title, this.titleLabelX, this.titleLabelY, COLOR_TEXT);
        gui.drawString(this.font, this.playerInventoryTitle, this.inventoryLabelX, this.inventoryLabelY, COLOR_TEXT);
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
        event.register(ModMenus.GENERATOR.get(), OGEGeneratorScreen::new);
    }
}
