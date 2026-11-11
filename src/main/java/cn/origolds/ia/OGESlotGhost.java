package cn.origolds.ia;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.item.ItemStack;

/**
 * 界面上“绑定过滤物品”的半透明幽灵画法的公共实现。
 *
 * <p>机器绑定了过滤物品后，往界面上的目标槽位半透明叠画一份绑定物，
 * 提示玩家这个槽只收这一种东西。抽屉、引力匣、超级漏斗、锯片台等界面共用。</p>
 */
final class OGESlotGhost {

    /** 叠在幽灵物品上的半透明暗色（约 75% 不透明的深黑）。 */
    private static final int OVERLAY = 0xBF000004;

    private OGESlotGhost() {
    }

    /** 在 (x, y) 处画一个幽灵物品；绑定为空时什么都不画。 */
    static void render(GuiGraphicsExtractor gui, int x, int y, ItemStack bound) {
        if (!bound.isEmpty()) {
            gui.item(bound, x, y);
            gui.fill(x, y, x + 16, y + 16, OVERLAY);
        }
    }
}
