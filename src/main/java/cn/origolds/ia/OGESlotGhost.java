package cn.origolds.ia;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.item.ItemStack;

/**
 * 界面里“绑定物品虚影”的统一画法，金源虚空抽屉与金源引力匣共用
 * （两边的 {@code renderSlot} 各自只负责判断哪些槽该画，画法都走这里，避免各写一份走样）。
 *
 * <p>绑定过滤物品后，机器里还没有放东西的空槽会叠一层 25% 透明度的该物品，
 * 让玩家一眼看到这台机器以后只收什么。</p>
 *
 * <p>做法是“先正常画出物品，再用 75% 不透明的槽底色盖一遍”，结果正好是 25% 物品 + 75%
 * 底色。不用 {@code setShaderColor} 调 alpha，是因为实体方块这类物品走的是 cutout
 * 渲染管线，其渲染类型会把混合整个关掉，那样虚影会原样不透明地画出来。覆盖用
 * {@link RenderType#guiOverlay()}：它不带深度测试，保证一定盖在物品之上。</p>
 */
final class OGESlotGhost {

    /** 覆盖色：槽底色 {@code 0x1C1C24} 配 75% 不透明度（ARGB 高两位 {@code 0xBF}）。 */
    private static final int OVERLAY = 0xBF1C1C24;

    private OGESlotGhost() {
    }

    /**
     * 在一个槽的屏幕坐标上画出绑定物品虚影。
     *
     * @param bound 绑定的样品物品；空栈时什么都不画
     */
    static void render(GuiGraphics gui, int x, int y, ItemStack bound) {
        if (bound.isEmpty()) {
            return;
        }
        gui.renderItem(bound, x, y);
        gui.fill(RenderType.guiOverlay(), x, y, x + 16, y + 16, OVERLAY);
    }
}
