package cn.origolds.ia;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/**
 * 内容超出边框时被裁掉的 {@link EditBox}。
 *
 * <p>原版只把 <b>值</b> 截断，<b>提示文字</b>与高亮光标仍可能画出边框外
 * （默认名"圆锯刃槽[x,y,z]"坐标位数一多就飞出输入框）。这里给整个控件套一层
 * 剪刀矩形，点击框外空白处保存的交互逻辑不受影响。</p>
 */
public final class OGEClippedEditBox extends EditBox {

    public OGEClippedEditBox(Font font, int x, int y, int width, int height, Component message) {
        super(font, x, y, width, height, message);
    }

    @Override
    public void renderWidget(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        gui.enableScissor(getX(), getY(), getX() + getWidth(), getY() + getHeight());
        super.renderWidget(gui, mouseX, mouseY, partialTick);
        gui.disableScissor();
    }
}
