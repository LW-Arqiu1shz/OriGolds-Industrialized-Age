package cn.origolds.ia;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/**
 * 文本超出框宽时不越界绘制的 {@link EditBox}：渲染前把绘图区裁剪到控件自身矩形。
 * 志愿者绑定界面的改名框用它，长名字不会画到相邻按钮上。
 */
public final class OGEClippedEditBox extends EditBox {

    public OGEClippedEditBox(Font font, int x, int y, int width, int height, Component message) {
        super(font, x, y, width, height, message);
    }

    @Override
    public void extractWidgetRenderState(GuiGraphicsExtractor gui, int mouseX, int mouseY, float partialTick) {
        gui.enableScissor(getX(), getY(), getX() + getWidth(), getY() + getHeight());
        super.extractWidgetRenderState(gui, mouseX, mouseY, partialTick);
        gui.disableScissor();
    }
}
