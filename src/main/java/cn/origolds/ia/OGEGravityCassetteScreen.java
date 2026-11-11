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
 * 金源引力匣 UI：与虚空抽屉同款外观（暗金底色 + 描边 + 槽位边框 + 容量读数），
 * 但固定 3 行 27 格全部可用——没有滚动、没有 ▲▼ 箭头、没有逐格解锁。
 */
@EventBusSubscriber(modid = OriGoldsIndustrializedAge.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class OGEGravityCassetteScreen extends AbstractContainerScreen<OGEGravityCassetteMenu> {

    private static final int COLOR_BG = 0xFF101015;
    private static final int COLOR_BORDER = 0xFFD9A91F;
    private static final int COLOR_SLOT_BORDER = 0xFF6A5A22;
    private static final int COLOR_SLOT_INNER = 0xFF1C1C24;
    private static final int COLOR_TEXT = 0xFFFFFFFF;
    private static final int COLOR_DIM = 0xFF9A9AA0;

    public OGEGravityCassetteScreen(OGEGravityCassetteMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = 176;
        this.imageHeight = 114 + OGEGravityCassetteMenu.ROWS * 18;
        this.titleLabelX = 8;
        this.titleLabelY = 6;
        this.inventoryLabelX = 8;
        this.inventoryLabelY = 18 + OGEGravityCassetteMenu.ROWS * 18 + 2;
    }

    /** 容量读数字符串（标题行右侧）：已用组数 / 27。 */
    private String countText() {
        return this.menu.getUsedStacks() + "/" + OGEGravityCassetteMenu.SLOT_COUNT;
    }

    /**
     * 绑定物品后，匣里每个空槽都画一份 25% 透明的该物品虚影。
     *
     * <p>只画引力匣自己的槽（{@code index < 27}），玩家背包/快捷栏的空槽不画；
     * 画法与虚空抽屉共用 {@link OGESlotGhost}。</p>
     */
    @Override
    protected void renderSlot(GuiGraphics gui, Slot slot) {
        super.renderSlot(gui, slot);
        if (!slot.getItem().isEmpty() || slot.index >= this.menu.getCassetteSlotCount()) {
            return;
        }
        OGESlotGhost.render(gui, slot.x, slot.y, this.menu.getBound());
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
    }

    @Override
    protected void renderLabels(GuiGraphics gui, int mouseX, int mouseY) {
        gui.drawString(this.font, this.title, this.titleLabelX, this.titleLabelY, COLOR_TEXT);
        gui.drawString(this.font, this.playerInventoryTitle, this.inventoryLabelX, this.inventoryLabelY, COLOR_TEXT);

        String used = countText();
        int w = this.font.width(used);
        gui.drawString(this.font, used, this.imageWidth - 8 - w, this.titleLabelY, COLOR_DIM);
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
        event.register(ModMenus.GRAVITY_CASSETTE.get(), OGEGravityCassetteScreen::new);
    }
}
