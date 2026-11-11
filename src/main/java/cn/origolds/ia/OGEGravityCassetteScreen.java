package cn.origolds.ia;

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
 * 金源引力匣 UI：与虚空抽屉同款外观（暗金底色 + 描边 + 槽位边框 + 容量读数），
 * 但固定 3 行 27 格全部可用——没有滚动、没有 ▲▼ 箭头、没有逐格解锁。
 */
@EventBusSubscriber(modid = OriGoldsIndustrializedAge.MOD_ID, value = Dist.CLIENT)
public final class OGEGravityCassetteScreen extends AbstractContainerScreen<OGEGravityCassetteMenu> {

    private static final int COLOR_BG = 0xFF101015;
    private static final int COLOR_BORDER = 0xFFD9A91F;
    private static final int COLOR_SLOT_BORDER = 0xFF6A5A22;
    private static final int COLOR_SLOT_INNER = 0xFF1C1C24;
    private static final int COLOR_TEXT = 0xFFFFFFFF;
    private static final int COLOR_DIM = 0xFF9A9AA0;

    public OGEGravityCassetteScreen(OGEGravityCassetteMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title, 176, 114 + OGEGravityCassetteMenu.ROWS * 18);
        this.titleLabelX = 8;
        this.titleLabelY = 6;
        this.inventoryLabelX = 8;
        this.inventoryLabelY = 18 + OGEGravityCassetteMenu.ROWS * 18 + 2;
    }

    /** 容量读数字符串（标题行右侧）：已用组数 / 27。 */
    private String countText() {
        return this.menu.getUsedStacks() + "/" + OGEGravityCassetteMenu.SLOT_COUNT;
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
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor gui, int mouseX, int mouseY) {
        gui.text(this.font, this.title, this.titleLabelX, this.titleLabelY, COLOR_TEXT);
        gui.text(this.font, this.playerInventoryTitle, this.inventoryLabelX, this.inventoryLabelY, COLOR_TEXT);

        String used = countText();
        int w = this.font.width(used);
        gui.text(this.font, used, this.imageWidth - 8 - w, this.titleLabelY, COLOR_DIM);
    }

    /** 绘制槽位边框：外层一圈描边 + 内层深色底。 */
    private void drawSlotBorder(GuiGraphicsExtractor gui, Slot slot) {
        int sx = this.leftPos + slot.x - 1;
        int sy = this.topPos + slot.y - 1;
        gui.fill(sx, sy, sx + 18, sy + 18, COLOR_SLOT_BORDER);
        gui.fill(sx + 1, sy + 1, sx + 17, sy + 17, COLOR_SLOT_INNER);
    }

    /** 空的引力匣槽位叠画绑定过滤物品的半透明幽灵。 */
    @Override
    protected void extractSlot(GuiGraphicsExtractor gui, Slot slot, int mouseX, int mouseY) {
        super.extractSlot(gui, slot, mouseX, mouseY);
        if (slot.getItem().isEmpty() && slot.index < this.menu.getCassetteSlotCount()) {
            OGESlotGhost.render(gui, slot.x, slot.y, this.menu.getBound());
        }
    }

    /** 屏幕渲染末尾补一次物品提示：1.21.1 的容器基类不会自动画悬停物品的 tooltip。 */
    @Override
    public void extractRenderState(GuiGraphicsExtractor gui, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(gui, mouseX, mouseY, partialTick);
    }

    /** 在客户端注册本屏幕到菜单类型。 */
    @SubscribeEvent
    public static void registerScreens(RegisterMenuScreensEvent event) {
        event.register(ModMenus.GRAVITY_CASSETTE.get(), OGEGravityCassetteScreen::new);
    }
}
