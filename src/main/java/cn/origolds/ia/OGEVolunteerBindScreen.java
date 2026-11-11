package cn.origolds.ia;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/**
 * 志愿者绑定管理界面：列出该观察员绑定的锯片台，支持
 * 拖把手排序、改名（空名回退默认坐标名）、点垃圾桶删除、
 * 右键把手进入“世界预览”（志愿者到目标格拉一条连线）。
 *
 * <p>数据不常驻：每次列表变化都由服务端 {@link OGEVolunteerBindSyncPayload} 回发
 * 后整表重建（{@link #handleSync}），编辑动作即时发往服务端。</p>
 */
public final class OGEVolunteerBindScreen extends Screen {

    private static final Identifier HANDLE_ICON = Identifier.fromNamespaceAndPath(
            OriGoldsIndustrializedAge.MOD_ID, "textures/gui/volunteer_bind_handle.png");
    private static final Identifier TRASH_ICON = Identifier.fromNamespaceAndPath(
            OriGoldsIndustrializedAge.MOD_ID, "textures/gui/trash.png");

    private static final int HANDLE_W = 24;
    private static final int NAME_W = 168;
    private static final int COORD_W = 60;
    private static final int TRASH_W = 20;
    private static final int GAP = 3;
    private static final int SIDE_PAD = 12;
    private static final int ROW_H = 20;
    private static final int TITLE_H = 14;
    private static final int HEADER_H = 12;
    private static final int BOTTOM_PAD = 10;
    private static final int SCREEN_MARGIN = 16;
    private static final int SCROLLBAR_W = 6;
    private static final int MIN_THUMB_H = 12;
    private static final int COLOR_PANEL = 0xF0101015;
    private static final int COLOR_BORDER = -2512609;
    private static final int COLOR_TEXT = -1;
    private static final int COLOR_DIM = -6645088;
    private static final int COLOR_SCROLL_TRACK = 1610612736;
    private static final int NAME_MAX = 64;

    /** 悬停展示全名的触发时长（毫秒）。 */
    private static final long TOOLTIP_HOLD_MS = 500L;

    /**
     * 垃圾桶悬停时叠加的加色层强度（0～1，越大越白），与 1.21.1 一致。
     * 26.x 的界面没有加色混合管线，这里用“预乘 alpha”管线再画一遍同一张贴图、alpha 取 1/255：
     * 结果约等于“底色 + 贴图颜色 × 强度”，只有贴图真正不透明的像素会变亮（全透明像素被着色器丢弃），
     * 效果与 1.21.1 的加色叠层相同。
     */
    private static final int TRASH_HOVER_TINT = 0x01595959;

    private final int volunteerId;
    private final List<Row> rows = new ArrayList<>();
    private int panelLeft;
    private int panelTop;
    private int panelWidth;
    private int panelHeight;
    private int listTop;
    private int listBottom;
    private int viewportH;
    private int scroll;
    private int maxScroll;
    private int dragIndex = -1;
    private double dragOffsetY;
    private boolean scrollDragging;
    private boolean closeForPreview;
    private final List<Seed> seeds = new ArrayList<>();
    private double mouseYLast;

    /** 名称悬停提示的计时。 */
    private int hoverRow = -1;
    private long hoverStartMs;

    public OGEVolunteerBindScreen(int volunteerId) {
        super(Component.translatable("gui.origolds_ia.volunteer_bind.title"));
        this.volunteerId = volunteerId;
    }

    /** 网络回发入口：界面已开着就原位刷新，否则新建并打开。 */
    public static void handleSync(OGEVolunteerBindSyncPayload payload) {
        Minecraft mc = Minecraft.getInstance();
        // 26.3 起屏幕不再挂在 Minecraft 上，改由 mc.gui 持有（原 mc.screen）
        if (mc.gui.screen() instanceof OGEVolunteerBindScreen open && open.volunteerId == payload.entityId()) {
            open.applyData(payload.positions(), payload.names());
        } else {
            OGEVolunteerBindScreen screen = new OGEVolunteerBindScreen(payload.entityId());
            screen.seeds.addAll(unzip(payload.positions(), payload.names()));
            // 26.3 起打开界面走 mc.gui.setScreen（原 mc.setScreen）
            mc.gui.setScreen(screen);
        }
    }

    /** 服务端广播某锯片台改名：所有开着的绑定界面把同坐标行原位刷新（正在编辑的行不打断）。 */
    public static void handleExternalRename(OGEVolunteerBindNamePayload payload) {
        Minecraft mc = Minecraft.getInstance();
        // 26.3 起当前界面由 mc.gui 持有（原 mc.screen）
        if (mc.gui.screen() instanceof OGEVolunteerBindScreen open) {
            open.applyExternalName(payload.pos(), payload.name());
        }
    }

    private void applyExternalName(long posLong, String name) {
        for (Row row : this.rows) {
            if (row.posLong == posLong && row.box != this.getFocused()) {
                row.custom = name;
                row.committed = name;
                row.box.setValue(name);
                row.box.setHint(Component.literal(this.defaultName(posLong, "")));
            }
        }
    }

    private static List<Seed> unzip(List<Long> positions, List<String> names) {
        List<Seed> list = new ArrayList<>(positions.size());
        for (int i = 0; i < positions.size(); i++) {
            list.add(new Seed(positions.get(i), names.get(i)));
        }
        return list;
    }

    @Override
    protected void init() {
        if (!this.seeds.isEmpty()) {
            this.rows.clear();
            this.scroll = 0;
            for (Seed seed : this.seeds) {
                OGEClippedEditBox box = new OGEClippedEditBox(this.font, 0, 0, NAME_W, 14,
                        Component.translatable("gui.origolds_ia.volunteer_bind.name"));
                box.setMaxLength(NAME_MAX);
                box.setBordered(true);
                Row row = new Row(seed.pos(), seed.name(), box);
                box.setValue(row.custom);
                box.setHint(Component.literal(this.defaultName(row.posLong, "")));
                this.rows.add(row);
            }
            this.seeds.clear();
        }
        for (Row row : this.rows) {
            this.addRenderableWidget(row.box);
        }
        this.layout();
    }

    private void layout() {
        this.panelWidth = SIDE_PAD + HANDLE_W + GAP + NAME_W + GAP + COORD_W * 3 + GAP + TRASH_W + SIDE_PAD;
        int desired = TITLE_H + HEADER_H + Math.max(1, this.rows.size()) * ROW_H + BOTTOM_PAD;
        int minPanel = TITLE_H + HEADER_H + ROW_H + BOTTOM_PAD;
        int maxPanel = Math.max(minPanel, this.height - SCREEN_MARGIN * 2);
        this.panelHeight = Math.min(desired, maxPanel);
        this.panelLeft = (this.width - this.panelWidth) / 2;
        this.panelTop = Math.max(SCREEN_MARGIN, (this.height - this.panelHeight) / 2);
        this.listTop = this.panelTop + TITLE_H + HEADER_H;
        this.listBottom = this.panelTop + this.panelHeight - BOTTOM_PAD;
        this.viewportH = Math.max(ROW_H, this.listBottom - this.listTop);
        this.maxScroll = Math.max(0, this.rows.size() * ROW_H - this.viewportH);
        this.scroll = Mth.clamp(this.scroll, 0, this.maxScroll);
    }

    private int handleLeft() {
        return this.panelLeft + SIDE_PAD;
    }

    private int nameLeft() {
        return this.handleLeft() + HANDLE_W + GAP;
    }

    private int coordLeft() {
        return this.nameLeft() + NAME_W + GAP;
    }

    private int trashLeft() {
        return this.coordLeft() + COORD_W * 3 + GAP;
    }

    private void applyData(List<Long> positions, List<String> names) {
        this.rows.clear();
        this.seeds.clear();
        this.seeds.addAll(unzip(positions, names));
        this.scroll = 0;
        this.dragIndex = -1;
        this.scrollDragging = false;
        this.rebuildWidgets();
    }

    private String defaultName(long posLong, String custom) {
        if (custom != null && !custom.isBlank()) {
            return custom;
        }
        BlockPos pos = BlockPos.of(posLong);
        return Component.translatable("gui.origolds_ia.volunteer_bind.default_name",
                pos.getX(), pos.getY(), pos.getZ()).getString();
    }

    // ------------------------------------------------------------------
    // 渲染
    // ------------------------------------------------------------------

    @Override
    public void extractBackground(GuiGraphicsExtractor gui, int mouseX, int mouseY, float partialTick) {
        // 不画任何背景遮罩：面板悬在游戏画面上。
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor gui, int mouseX, int mouseY, float partialTick) {
        this.layout();
        gui.fill(this.panelLeft, this.panelTop, this.panelLeft + this.panelWidth,
                this.panelTop + this.panelHeight, COLOR_PANEL);
        this.drawBorder(gui);
        gui.centeredText(this.font, this.title, this.panelLeft + this.panelWidth / 2, this.panelTop + 3, COLOR_TEXT);

        // 表头：名字 + X/Y/Z 三列
        int nameLeft = this.nameLeft();
        int coordLeft = this.coordLeft();
        int headerY = this.panelTop + TITLE_H + 2;
        gui.text(this.font, Component.translatable("gui.origolds_ia.volunteer_bind.header_name"),
                nameLeft + 2, headerY, COLOR_DIM);
        String[] headers = {"X", "Y", "Z"};
        for (int c = 0; c < 3; c++) {
            String text = headers[c];
            int cellLeft = coordLeft + c * COORD_W;
            gui.text(this.font, text, cellLeft + (COORD_W - this.font.width(text)) / 2, headerY, COLOR_DIM);
        }

        // 列表区（含每行的改名输入框）裁剪到面板内
        gui.enableScissor(this.panelLeft + 1, this.listTop, this.panelLeft + this.panelWidth - 1, this.listBottom);
        for (int i = 0; i < this.rows.size(); i++) {
            Row row = this.rows.get(i);
            int y = this.rowY(i);
            boolean visible = y + ROW_H > this.listTop && y < this.listBottom;
            row.box.setVisible(visible);
            row.box.setX(nameLeft);
            row.box.setY(y + 3);
            if (visible) {
                gui.blit(RenderPipelines.GUI_TEXTURED, HANDLE_ICON, this.handleLeft() + 5, y + 3,
                        0.0F, 0.0F, 14, 14, 16, 16);
                int textY = y + (ROW_H - this.font.lineHeight) / 2 + 1;
                String[] values = {
                        Integer.toString(row.pos.getX()), Integer.toString(row.pos.getY()), Integer.toString(row.pos.getZ())};
                for (int c = 0; c < 3; c++) {
                    int cellLeft = coordLeft + c * COORD_W;
                    gui.text(this.font, values[c], cellLeft + (COORD_W - this.font.width(values[c])) / 2, textY, COLOR_TEXT);
                }
                this.drawTrashIcon(gui, y, mouseX, mouseY);
            }
        }
        super.extractRenderState(gui, mouseX, mouseY, partialTick);
        gui.disableScissor();

        if (this.rows.isEmpty()) {
            gui.centeredText(this.font, Component.translatable("gui.origolds_ia.volunteer_bind.empty"),
                    this.panelLeft + this.panelWidth / 2, this.listTop + 4, COLOR_DIM);
        }
        this.drawScrollbar(gui);
        this.renderNameTooltip(gui, mouseX, mouseY);
    }

    /** 画第 {@code rowTop} 行最右侧的垃圾桶图标；鼠标悬停时微微变白（只亮图标本身的像素）。 */
    private void drawTrashIcon(GuiGraphicsExtractor gui, int rowTop, int mouseX, int mouseY) {
        int x = this.trashLeft() + (TRASH_W - 16) / 2;
        int y = rowTop + (ROW_H - 16) / 2;
        gui.blit(RenderPipelines.GUI_TEXTURED, TRASH_ICON, x, y, 0.0F, 0.0F, 16, 16, 16, 16);
        boolean hovered = mouseX >= x && mouseX < x + 16 && mouseY >= y && mouseY < y + 16;
        if (hovered) {
            gui.blit(RenderPipelines.GUI_TEXTURED_PREMULTIPLIED_ALPHA, TRASH_ICON, x, y, 0.0F, 0.0F,
                    16, 16, 16, 16, TRASH_HOVER_TINT);
        }
    }

    /** 名称超长时，在名称栏上悬停 0.5 秒弹提示框展示全文（拖拽中不打扰），与 1.21.1 一致。 */
    private void renderNameTooltip(GuiGraphicsExtractor gui, int mouseX, int mouseY) {
        int hovered = -1;
        if (this.dragIndex < 0 && !this.scrollDragging) {
            int idx = this.rowIndexAt(mouseY);
            int nameLeft = this.nameLeft();
            if (idx >= 0 && mouseX >= nameLeft && mouseX < nameLeft + NAME_W) {
                hovered = idx;
            }
        }
        if (hovered != this.hoverRow) {
            this.hoverRow = hovered;
            this.hoverStartMs = System.currentTimeMillis();
        }
        if (this.hoverRow < 0 || this.hoverRow >= this.rows.size()
                || System.currentTimeMillis() - this.hoverStartMs < TOOLTIP_HOLD_MS) {
            return;
        }
        Row row = this.rows.get(this.hoverRow);
        String full = row.custom.isBlank() ? this.defaultName(row.posLong, "") : row.custom;
        if (this.font.width(full) <= NAME_W - 8) {
            return; // 没截断就不弹
        }
        gui.setTooltipForNextFrame(this.font, Component.literal(full), mouseX, mouseY);
    }

    private int rowY(int i) {
        return i == this.dragIndex
                ? (int) (this.mouseYLast - this.dragOffsetY)
                : this.listTop + i * ROW_H - this.scroll;
    }

    private int rowIndexAt(double mouseY) {
        if (mouseY < this.listTop || mouseY >= this.listBottom) {
            return -1;
        }
        int idx = (int) Math.floor((mouseY - this.listTop + this.scroll) / (double) ROW_H);
        return idx >= 0 && idx < this.rows.size() ? idx : -1;
    }

    private int scrollbarX() {
        return this.panelLeft + this.panelWidth - SIDE_PAD + GAP;
    }

    private int thumbHeight() {
        int total = Math.max(1, this.rows.size() * ROW_H);
        return Math.max(MIN_THUMB_H, this.viewportH * this.viewportH / total);
    }

    private void scrollFromMouse(double mouseY) {
        int thumbH = this.thumbHeight();
        int travel = Math.max(1, this.viewportH - thumbH);
        double t = (mouseY - this.listTop - thumbH / 2.0) / travel;
        this.scroll = (int) Math.round(Mth.clamp(t, 0.0, 1.0) * this.maxScroll);
    }

    private void drawScrollbar(GuiGraphicsExtractor gui) {
        if (this.maxScroll <= 0) {
            return;
        }
        int x = this.scrollbarX();
        gui.fill(x, this.listTop, x + SCROLLBAR_W, this.listBottom, COLOR_SCROLL_TRACK);
        int thumbH = this.thumbHeight();
        int travel = Math.max(0, this.viewportH - thumbH);
        int thumbY = this.listTop + (int) Math.round((double) this.scroll / this.maxScroll * travel);
        gui.fill(x, thumbY, x + SCROLLBAR_W, thumbY + thumbH, COLOR_BORDER);
    }

    private void drawBorder(GuiGraphicsExtractor gui) {
        gui.fill(this.panelLeft, this.panelTop, this.panelLeft + this.panelWidth, this.panelTop + 1, COLOR_BORDER);
        gui.fill(this.panelLeft, this.panelTop + this.panelHeight - 1, this.panelLeft + this.panelWidth,
                this.panelTop + this.panelHeight, COLOR_BORDER);
        gui.fill(this.panelLeft, this.panelTop, this.panelLeft + 1, this.panelTop + this.panelHeight, COLOR_BORDER);
        gui.fill(this.panelLeft + this.panelWidth - 1, this.panelTop, this.panelLeft + this.panelWidth,
                this.panelTop + this.panelHeight, COLOR_BORDER);
    }

    // ------------------------------------------------------------------
    // 输入
    // ------------------------------------------------------------------

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mouseX = event.x();
        double mouseY = event.y();
        this.mouseYLast = mouseY;
        // 26.3 起输入后端从 GLFW 换成 SDL：鼠标键码不再是 0=左/1=右，
        // 必须按 InputConstants 常量判定（SDL 下 左=1、中=2、右=3）。
        // 写死字面量会让左键被当成右键、button==0 分支永不触发——
        // 表现为把手拖拽排序失灵、垃圾桶删除按钮点不动。
        int button = event.button();
        int handleLeft = this.handleLeft();
        if (button == InputConstants.MOUSE_BUTTON_RIGHT) {
            int idx = this.rowIndexAt(mouseY);
            if (idx >= 0 && mouseX >= handleLeft && mouseX < handleLeft + HANDLE_W) {
                // 右键把手：切换该行的世界预览（打开预览时先收界面）
                this.commitBox(this.focusedBox());
                Row row = this.rows.get(idx);
                if (OGEVolunteerBindClient.isPreviewing(this.volunteerId, row.posLong)) {
                    OGEVolunteerBindClient.stopPreview();
                } else {
                    this.closeForPreview = true;
                    OGEVolunteerBindClient.startPreview(this.volunteerId, row.posLong);
                    this.onClose();
                }
                return true;
            }
            if (OGEVolunteerBindClient.isPreviewingVolunteer(this.volunteerId)) {
                OGEVolunteerBindClient.stopPreview();
                return true;
            }
            return super.mouseClicked(event, doubleClick);
        }
        if (button == InputConstants.MOUSE_BUTTON_LEFT) {
            if (this.maxScroll > 0 && mouseX >= this.scrollbarX() && mouseX < this.scrollbarX() + SCROLLBAR_W
                    && mouseY >= this.listTop && mouseY < this.listBottom) {
                this.scrollDragging = true;
                this.scrollFromMouse(mouseY);
                return true;
            }
            int idx = this.rowIndexAt(mouseY);
            if (idx >= 0 && mouseX >= this.trashLeft() && mouseX < this.trashLeft() + TRASH_W) {
                this.commitBox(this.focusedBox());
                this.deleteRow(idx);
                return true;
            }
            if (idx >= 0 && mouseX >= handleLeft && mouseX < handleLeft + HANDLE_W) {
                // 左键按住把手：开始拖拽排序
                this.commitBox(this.focusedBox());
                // 先按未拖拽时的行位算出按下点相对行顶的偏移，再进入拖拽状态：
                // rowY 对 dragIndex 会走"跟鼠标"分支，顺序反了就会让行顶吸到鼠标。
                double rowTop = this.rowY(idx);
                this.dragIndex = idx;
                this.dragOffsetY = mouseY - rowTop;
                return true;
            }
            EditBox before = this.focusedBox();
            boolean handled;
            if (mouseY >= this.listTop && mouseY < this.listBottom) {
                handled = super.mouseClicked(event, doubleClick);
            } else {
                this.setFocused(null);
                handled = true;
            }
            if (before != null && before != this.focusedBox()) {
                this.commitBox(before);
            }
            return handled;
        }
        return super.mouseClicked(event, doubleClick);
    }

    private void deleteRow(int idx) {
        Row row = this.rows.remove(idx);
        this.removeWidget(row.box);
        if (this.getFocused() == row.box) {
            this.setFocused(null);
        }
        if (OGEVolunteerBindClient.isPreviewing(this.volunteerId, row.posLong)) {
            OGEVolunteerBindClient.stopPreview();
        }
        ClientPacketDistributor.sendToServer(OGEVolunteerBindEditPayload.ofDelete(this.volunteerId, row.posLong));
        this.layout();
    }

    private EditBox focusedBox() {
        return this.getFocused() instanceof EditBox box ? box : null;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (this.maxScroll > 0) {
            this.scroll = (int) Mth.clamp(this.scroll - scrollY * ROW_H, 0, this.maxScroll);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        double mouseY = event.y();
        this.mouseYLast = mouseY;
        if (this.scrollDragging) {
            this.scrollFromMouse(mouseY);
            return true;
        }
        if (this.dragIndex >= 0) {
            int target = (int) Math.floor((mouseY - this.listTop + this.scroll) / (double) ROW_H);
            target = Math.max(0, Math.min(this.rows.size() - 1, target));
            if (target != this.dragIndex) {
                Row moving = this.rows.remove(this.dragIndex);
                this.rows.add(target, moving);
                this.dragIndex = target;
            }
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (this.scrollDragging) {
            this.scrollDragging = false;
            return true;
        }
        if (this.dragIndex < 0) {
            return super.mouseReleased(event);
        }
        // 拖拽结束：把最终顺序发给服务端
        this.dragIndex = -1;
        List<Long> order = new ArrayList<>(this.rows.size());
        for (Row row : this.rows) {
            order.add(row.posLong);
        }
        ClientPacketDistributor.sendToServer(OGEVolunteerBindEditPayload.ofReorder(this.volunteerId, order));
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        // Enter / 小键盘 Enter：先提交当前输入框，再走原版分发（失焦）。
        // 26.3 起键码是 SDL 扫描码，必须用 InputConstants 常量（GLFW 时代的 257/335 失效）。
        if (event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_NUMPADENTER) {
            this.commitBox(this.focusedBox());
        }
        return super.keyPressed(event);
    }

    @Override
    public void onClose() {
        this.commitBox(this.focusedBox());
        if (!this.closeForPreview) {
            OGEVolunteerBindClient.stopPreview();
        }
        super.onClose();
    }

    @Override
    public void removed() {
        if (!this.closeForPreview) {
            OGEVolunteerBindClient.stopPreview();
        }
        super.removed();
    }

    private void commitBox(EditBox box) {
        if (box == null) {
            return;
        }
        for (Row row : this.rows) {
            if (row.box == box) {
                this.commit(row);
                return;
            }
        }
    }

    private void commit(Row row) {
        String typed = row.box.getValue().strip();
        String defaultName = this.defaultName(row.posLong, "");
        String custom = !typed.equals(defaultName) && !typed.isBlank() ? typed : "";
        row.custom = custom;
        row.box.setValue(custom);
        row.box.setHint(Component.literal(defaultName));
        if (!custom.equals(row.committed)) {
            row.committed = custom;
            ClientPacketDistributor.sendToServer(
                    OGEVolunteerBindEditPayload.ofRename(this.volunteerId, row.posLong, custom));
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** 一行绑定：坐标 + 自定义名 + 输入控件。 */
    private static final class Row {
        final long posLong;
        final BlockPos pos;
        String custom;
        String committed;
        final EditBox box;

        Row(long posLong, String custom, EditBox box) {
            this.posLong = posLong;
            this.pos = BlockPos.of(posLong);
            this.custom = custom;
            this.committed = custom;
            this.box = box;
        }
    }

    /** 服务端回发的一条初始数据（重建行之前暂存）。 */
    private record Seed(long pos, String name) {
    }
}
