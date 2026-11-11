package cn.origolds.ia;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Field;

/**
 * 金源虚空抽屉 UI：箱子式 9 列网格 + 玩家背包。
 *
 * <p>抽屉区是<b>固定 3 行的滚动窗口</b>：已解锁格数超过 3 行后全部通过滚轮翻页
 * （滚轮、标题栏的 ▲▼ 箭头均可），面板高度恒定不随内容增长。每次放入物品自动
 * 解锁下一格时，视图会自动跳到新格所在行。</p>
 *
 * <p>标题行右侧为"占用组数 / 729"读数，其<b>左边</b>紧跟 ▲▼ 滚动方向指示
 * （当前方向可滚动时亮金色，否则暗灰）。抽屉区可滚动的条件由 {@link #maxScroll()} 统一给出。</p>
 *
 * <p>抽屉槽与玩家背包槽的屏幕坐标每帧按当前滚动量重算；未解锁的槽被放到屏幕外，
 * 因此既画不出也点不到。底色与描边沿用本模组机器 UI 的暗金风格（纯代码绘制）。</p>
 *
 * <p>划动快速转移的防抽干由按键暂停实现，见 {@link OGEVoidDrawerAutoSortSync}：
 * 按住 Ctrl/Shift/Alt 任一按键期间服务端暂停抽屉的自动码满，搬空一格不会再把
 * 下一堆补进来；松开按键立即恢复整理。该暂停对所有以这三个键为基础键的
 * 物品栏管理模组（IPN 及同类）统一生效，不针对特定模组。</p>
 */
@EventBusSubscriber(modid = OriGoldsIndustrializedAge.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class OGEVoidDrawerScreen extends AbstractContainerScreen<OGEVoidDrawerMenu> {

    private static final int COLOR_BG = 0xFF101015;
    private static final int COLOR_BORDER = 0xFFD9A91F;
    private static final int COLOR_SLOT_BORDER = 0xFF6A5A22;
    private static final int COLOR_SLOT_INNER = 0xFF1C1C24;
    private static final int COLOR_TEXT = 0xFFFFFFFF;
    private static final int COLOR_DIM = 0xFF9A9AA0;
    private static final int COLOR_ARROW = 0xFFD9A91F;

    private static final String ARROW_UP = "▲";
    private static final String ARROW_DOWN = "▼";

    /**
     * {@code Slot.x/y} 在 1.21.1 是实例级 final 字段。
     * 抽屉需要"放一格开一格"的动态布局，只能在客户端每帧把槽位挪到位
     * （未解锁槽挪到屏幕外）；实例 final 字段用 setAccessible 反射写入是
     * Java 允许的路径，失败时布局退化为静态摆放但点击仍由服务端 mayPlace 兜底。
     */
    private static final Field SLOT_X;
    private static final Field SLOT_Y;

    static {
        Field x = null;
        Field y = null;
        try {
            x = Slot.class.getDeclaredField("x");
            y = Slot.class.getDeclaredField("y");
            x.setAccessible(true);
            y.setAccessible(true);
        } catch (ReflectiveOperationException | RuntimeException e) {
            x = null;
            y = null;
        }
        SLOT_X = x;
        SLOT_Y = y;
    }

    /** 移动槽位显示坐标；反射不可用时保持菜单里的名义坐标。 */
    private static void place(Slot slot, int nx, int ny) {
        if (SLOT_X == null || SLOT_Y == null) {
            return;
        }
        try {
            SLOT_X.setInt(slot, nx);
            SLOT_Y.setInt(slot, ny);
        } catch (IllegalAccessException | RuntimeException e) {
            // 保持原位即可，服务端会拒绝类型不符槽位上的任何操作。
        }
    }

    /** 抽屉区行滚动量（行）。 */
    private int scroll;
    /** 上一帧的解锁格数，用于"自动跳到新解锁行"。 */
    private int lastUnlocked = -1;

    public OGEVoidDrawerScreen(OGEVoidDrawerMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = 176;
        // 面板高度恒定：3 行抽屉窗口 + 玩家背包，不随内容增长。
        this.imageHeight = 114 + OGEVoidDrawerMenu.MAX_VISIBLE_ROWS * 18;
        this.titleLabelX = 8;
        this.titleLabelY = 6;
        this.inventoryLabelX = 8;
    }

    /** 当前可滚动的最大行数（0 表示无需滚动）。 */
    private int maxScroll() {
        int unlocked = this.menu.getUnlockedSlotCount();
        int usedRows = Math.max(1, (unlocked + OGEVoidDrawerMenu.COLUMNS - 1) / OGEVoidDrawerMenu.COLUMNS);
        return Math.max(0, usedRows - OGEVoidDrawerMenu.MAX_VISIBLE_ROWS);
    }

    /** 容量读数字符串（标题行右侧）。 */
    private String countText() {
        return this.menu.getUsedStacks() + "/" + OGEVoidDrawerBlockEntity.SLOT_COUNT;
    }

    /**
     * 标题行右侧布局：占用数字右对齐在 {@code imageWidth-8}，▲▼ 两个箭头
     * 依次排在数字左边，互不重叠。返回 [箭头▲x, 箭头▲宽, 箭头▼x, 箭头▼宽]（面板相对坐标）。
     */
    private int[] arrowLayout() {
        int countX = this.imageWidth - 8 - this.font.width(countText());
        int upW = this.font.width(ARROW_UP);
        int downW = this.font.width(ARROW_DOWN);
        int downX = countX - 4 - downW;
        int upX = downX - 2 - upW;
        return new int[] {upX, upW, downX, downW};
    }

    /** 按当前解锁格数与滚动量重排所有槽位坐标（3 行固定窗口）。 */
    private void layoutSlots() {
        int unlocked = this.menu.getUnlockedSlotCount();
        int drawerSlots = this.menu.getDrawerSlotCount();

        if (lastUnlocked >= 0 && unlocked > lastUnlocked) {
            // 刚放入物品解锁了新格：视图滚动到新格所在行
            int newRow = (unlocked - 1) / OGEVoidDrawerMenu.COLUMNS;
            if (newRow >= scroll + OGEVoidDrawerMenu.MAX_VISIBLE_ROWS) {
                scroll = Math.max(0, newRow - OGEVoidDrawerMenu.MAX_VISIBLE_ROWS + 1);
            }
        }
        lastUnlocked = unlocked;
        this.scroll = Mth.clamp(this.scroll, 0, maxScroll());

        int visibleRows = OGEVoidDrawerMenu.MAX_VISIBLE_ROWS;
        this.inventoryLabelY = 18 + visibleRows * 18 + 2;

        int invTop = 18 + visibleRows * 18 + 16;
        for (int i = 0; i < drawerSlots; i++) {
            Slot slot = this.menu.slots.get(i);
            int row = i / OGEVoidDrawerMenu.COLUMNS;
            int visibleRow = row - this.scroll;
            if (i < unlocked && visibleRow >= 0 && visibleRow < visibleRows) {
                place(slot, 8 + (i % OGEVoidDrawerMenu.COLUMNS) * 18, 18 + visibleRow * 18);
            } else {
                place(slot, -1000, -1000);
            }
        }
        for (int p = 0; p < 36; p++) {
            Slot slot = this.menu.slots.get(drawerSlots + p);
            if (p < 27) {
                place(slot, 8 + (p % 9) * 18, invTop + (p / 9) * 18);
            } else {
                place(slot, 8 + (p - 27) * 18, invTop + 58);
            }
        }
    }

    @Override
    public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        layoutSlots();
        super.render(gui, mouseX, mouseY, partialTick);
        // 1.21.1 的容器基类不会自动画悬停物品的 tooltip，必须在渲染末尾自己补一次。
        this.renderTooltip(gui, mouseX, mouseY);
    }

    /** 绘制容器面板：背景、金色边框、槽位边界、滚动箭头与容量信息。 */
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
            if (slot.x >= 0) {
                drawSlotBorder(gui, slot);
            }
        }

        // 滚动方向指示：▲▼ 排在占用数字的左边，可滚方向亮金、不可滚方向暗灰。
        if (maxScroll() > 0) {
            int[] lay = arrowLayout();
            gui.drawString(this.font, ARROW_UP, x + lay[0], y + this.titleLabelY,
                    this.scroll > 0 ? COLOR_ARROW : COLOR_DIM);
            gui.drawString(this.font, ARROW_DOWN, x + lay[2], y + this.titleLabelY,
                    this.scroll < maxScroll() ? COLOR_ARROW : COLOR_DIM);
        }
    }

    @Override
    protected void renderLabels(GuiGraphics gui, int mouseX, int mouseY) {
        gui.drawString(this.font, this.title, this.titleLabelX, this.titleLabelY, COLOR_TEXT);
        gui.drawString(this.font, this.playerInventoryTitle, this.inventoryLabelX, this.inventoryLabelY, COLOR_TEXT);

        // 右侧容量读数：已用组数 / 729
        String used = countText();
        int w = this.font.width(used);
        gui.drawString(this.font, used, this.imageWidth - 8 - w, this.titleLabelY, COLOR_DIM);
    }

    @Override
    protected void renderSlot(GuiGraphics gui, Slot slot) {
        if (slot.x < 0) {
            return;
        }
        super.renderSlot(gui, slot);
        // 绑定物品后，抽屉区里每个可见的空槽都画一份 25% 透明的该物品虚影。
        if (!slot.getItem().isEmpty()
                || slot.index >= this.menu.getDrawerSlotCount()
                || slot.index >= this.menu.getUnlockedSlotCount()) {
            return;
        }
        // 画法统一在 OGESlotGhost，与金源引力匣共用同一套，避免两边走样。
        OGESlotGhost.render(gui, slot.x, slot.y, this.menu.getBound());
    }

    /** 绘制槽位边框：外层一圈描边 + 内层深色底。 */
    private void drawSlotBorder(GuiGraphics gui, Slot slot) {
        int sx = this.leftPos + slot.x - 1;
        int sy = this.topPos + slot.y - 1;
        gui.fill(sx, sy, sx + 18, sy + 18, COLOR_SLOT_BORDER);
        gui.fill(sx + 1, sy + 1, sx + 17, sy + 17, COLOR_SLOT_INNER);
    }

    /** 标题行 ▲▼ 箭头的点击命中：按到就滚动一行并消费事件。 */
    private boolean handleArrowClick(double mouseX, double mouseY) {
        if (maxScroll() <= 0) {
            return false;
        }
        int relX = (int) mouseX - this.leftPos;
        int relY = (int) mouseY - this.topPos;
        if (relY < this.titleLabelY - 2 || relY >= this.titleLabelY + this.font.lineHeight) {
            return false;
        }
        int[] lay = arrowLayout();
        if (relX >= lay[0] && relX < lay[0] + lay[1]) {
            this.scroll = Mth.clamp(this.scroll - 1, 0, maxScroll());
            return true;
        }
        if (relX >= lay[2] && relX < lay[2] + lay[3]) {
            this.scroll = Mth.clamp(this.scroll + 1, 0, maxScroll());
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && handleArrowClick(mouseX, mouseY)) {
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /** 滚轮滚动抽屉区（1.21.1 签名：水平/垂直两个增量都给出，取垂直向）。 */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollDeltaX, double scrollDeltaY) {
        int max = maxScroll();
        if (max <= 0) {
            return false;
        }
        int step = scrollDeltaY > 0 ? -1 : 1;
        this.scroll = Mth.clamp(this.scroll + step, 0, max);
        return true;
    }

    /** 在客户端注册本屏幕到菜单类型。 */
    @SubscribeEvent
    public static void registerScreens(RegisterMenuScreensEvent event) {
        event.register(ModMenus.VOID_DRAWER.get(), OGEVoidDrawerScreen::new);
    }
}
