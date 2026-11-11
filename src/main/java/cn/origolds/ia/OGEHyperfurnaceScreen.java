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
 * 金源超级熔炉界面：与金源炼成台同款暗金外观（底色 + 描边 + 槽位边框），
 * 上面 3 行 27 格原料区、下面玩家背包，没有进度条与读数——档位切换走
 * 空手 + Ctrl + 点击的准星交互（见 {@link OGEHyperfurnaceClient}）。
 */
@EventBusSubscriber(modid = OriGoldsIndustrializedAge.MOD_ID, value = Dist.CLIENT)
public final class OGEHyperfurnaceScreen extends AbstractContainerScreen<OGEHyperfurnaceMenu> {

    private static final int COLOR_BG = 0xFF101015;
    private static final int COLOR_BORDER = 0xFFD9A91F;
    private static final int COLOR_SLOT_BORDER = 0xFF6A5A22;
    private static final int COLOR_SLOT_INNER = 0xFF1C1C24;
    private static final int COLOR_TEXT = 0xFFFFFFFF;

    public OGEHyperfurnaceScreen(OGEHyperfurnaceMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title, OGEHyperfurnaceMenu.PANEL_WIDTH,
                OGEHyperfurnaceMenu.PANEL_HEIGHT);
        this.titleLabelX = 8;
        this.titleLabelY = 6;
        this.inventoryLabelX = 8;
        this.inventoryLabelY = 76;
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
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor gui, int mouseX, int mouseY) {
        gui.text(this.font, this.title, this.titleLabelX, this.titleLabelY, COLOR_TEXT);
        gui.text(this.font, this.playerInventoryTitle, this.inventoryLabelX, this.inventoryLabelY, COLOR_TEXT);
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
        event.register(ModMenus.HYPERFURNACE.get(), OGEHyperfurnaceScreen::new);
    }
}
