package cn.origolds.ia;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 挂机志愿者的圆锯刃槽绑定配置界面（普通右键志愿者打开）。
 *
 * <p>一行一条绑定：最左是 {@code volunteer_bind_handle.png} 三横杠拖拽标志
 * （按住拖动整行换序，松手把完整顺序发回服务端；右键进入 / 退出预览模式），其后依次是
 * <b>名称输入框</b>（默认"圆锯刃槽[x,y,z]"，点框外空白处保存，留空回默认名，
 * 超长时悬停 0.5 秒弹提示框展示全文）、该刃槽的 X / Y / Z 坐标
 * （纯展示，水平竖直居中），最右是垃圾桶（左键删除这条绑定）。</p>
 *
 * <p>横向尺寸是初版的 1.5 倍（文本框与 XYZ 栏一起变宽）。列表区距离屏幕顶端与底端
 * 各留固定的 {@value #SCREEN_MARGIN} 像素，装不下时右侧出现滚动条，也支持鼠标滚轮。</p>
 *
 * <p>不是容器界面：数据由 {@link OGEVolunteerBindSyncPayload} 一次性下发，
 * 改动经 {@link OGEVolunteerBindEditPayload} 发回，服务端复核后落到志愿者身上。</p>
 */
public final class OGEVolunteerBindScreen extends Screen {

    /** 拖拽标志贴图。 */
    private static final ResourceLocation HANDLE_ICON = ResourceLocation.fromNamespaceAndPath(
            OriGoldsIndustrializedAge.MOD_ID, "textures/gui/volunteer_bind_handle.png");

    /** 删除绑定（垃圾桶）贴图。 */
    private static final ResourceLocation TRASH_ICON = ResourceLocation.fromNamespaceAndPath(
            OriGoldsIndustrializedAge.MOD_ID, "textures/gui/trash.png");

    // ---- 面板与行列布局（全像素，固定不动）。横向全部按 1.5 倍放大 ----
    private static final int HANDLE_W = 24;
    private static final int NAME_W = 168;
    private static final int COORD_W = 60;
    /** 最右侧垃圾桶栏宽度。 */
    private static final int TRASH_W = 20;
    private static final int GAP = 3;
    private static final int SIDE_PAD = 12;
    private static final int ROW_H = 20;
    private static final int TITLE_H = 14;
    private static final int HEADER_H = 12;
    private static final int BOTTOM_PAD = 10;
    /** 列表区距离屏幕最顶端 / 最底端的固定留白。 */
    private static final int SCREEN_MARGIN = 16;
    /** 右侧滚动条宽度（占用右留白）。 */
    private static final int SCROLLBAR_W = 6;
    /** 滚动条滑块的最小高度。 */
    private static final int MIN_THUMB_H = 12;

    private static final int COLOR_PANEL = 0xF0101015;
    private static final int COLOR_BORDER = 0xFFD9A91F;
    private static final int COLOR_TEXT = 0xFFFFFFFF;
    private static final int COLOR_DIM = 0xFF9A9AA0;
    private static final int COLOR_SCROLL_TRACK = 0x60000000;

    /** 名字输入上限，与服务端复核口径一致。 */
    private static final int NAME_MAX = 64;

    /** 悬停展示全名的触发时长（毫秒）。 */
    private static final long TOOLTIP_HOLD_MS = 500L;

    /** 垃圾桶悬停时叠加的加色层强度（0～1，越大越白）。 */
    private static final float TRASH_HOVER_WHITEN = 0.35F;

    /** 一条绑定的本地视图。 */
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

    private final int volunteerId;
    private final List<Row> rows = new ArrayList<>();

    private int panelLeft;
    private int panelTop;
    private int panelWidth;
    private int panelHeight;
    private int listTop;
    private int listBottom;
    private int viewportH;

    /** 列表纵向滚动量（像素）与上限。 */
    private int scroll;
    private int maxScroll;

    /** 正在拖的行（-1 没有）与按下时的纵偏移。 */
    private int dragIndex = -1;
    private double dragOffsetY;

    /** 是否正在拖右侧滚动条滑块。 */
    private boolean scrollDragging;

    /** 因为要进入预览模式而关闭本界面：这次关闭不该把刚开的预览一起停掉。 */
    private boolean closeForPreview;

    /** 名称悬停提示的计时刻。 */
    private int hoverRow = -1;
    private long hoverStartMs;

    /** 打开请求带回来的初始数据，widget 要等 init 时才建，先寄存。 */
    private record Seed(long pos, String name) {
    }

    private final List<Seed> seeds = new ArrayList<>();

    public OGEVolunteerBindScreen(int volunteerId) {
        super(Component.translatable("gui.origolds_ia.volunteer_bind.title"));
        this.volunteerId = volunteerId;
    }

    /** 收到服务端绑定表：同一个志愿者就原地刷新，否则开新界面。 */
    public static void handleSync(OGEVolunteerBindSyncPayload payload) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.screen instanceof OGEVolunteerBindScreen open && open.volunteerId == payload.entityId()) {
            open.applyData(payload.positions(), payload.names());
            return;
        }
        OGEVolunteerBindScreen screen = new OGEVolunteerBindScreen(payload.entityId());
        screen.seeds.addAll(unzip(payload.positions(), payload.names()));
        mc.setScreen(screen);
    }

    /**
     * 收到"某个刃槽改名了"：名字唯一存在刃槽那边，别的志愿者（甚至别人）改的名也发到这里，
     * 开着的界面就地刷新对应那一行；没开界面就什么都不做（不会因此弹窗）。
     */
    public static void handleExternalRename(OGEVolunteerBindNamePayload payload) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.screen instanceof OGEVolunteerBindScreen open) {
            open.applyExternalName(payload.pos(), payload.name());
        }
    }

    /** 原地把某一行的显示名换成服务端的新值；正在输入的那一格不动，免得吞掉刚敲的字。 */
    private void applyExternalName(long posLong, String name) {
        for (Row row : rows) {
            if (row.posLong != posLong || row.box == getFocused()) {
                continue;
            }
            row.custom = name;
            // committed 一起改：否则下一次 commit 会以为本地是新值、服务端是旧值，把旧名字再发一遍。
            row.committed = name;
            row.box.setValue(name);
            row.box.setHint(Component.literal(defaultName(posLong, "")));
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
        if (!seeds.isEmpty()) {
            // 新下发的整表：按种子重建行。
            rows.clear();
            scroll = 0;
            for (Seed seed : seeds) {
                EditBox box = new OGEClippedEditBox(this.font, 0, 0, NAME_W, 14,
                        Component.translatable("gui.origolds_ia.volunteer_bind.name"));
                box.setMaxLength(NAME_MAX);
                box.setBordered(true);
                Row row = new Row(seed.pos(), seed.name(), box);
                box.setValue(row.custom);
                box.setHint(Component.literal(defaultName(row.posLong, "")));
                rows.add(row);
            }
            seeds.clear();
        }
        // resize 引发的重复 init：children 已被清空，把现有行的控件全部挂回去。
        for (Row row : rows) {
            addRenderableWidget(row.box);
        }
        layout();
    }

    /** 按当前行数与屏幕高度重算面板尺寸、列表区与滚动上限。 */
    private void layout() {
        panelWidth = SIDE_PAD + HANDLE_W + GAP + NAME_W + GAP + COORD_W * 3 + GAP + TRASH_W + SIDE_PAD;
        int desired = TITLE_H + HEADER_H + Math.max(1, rows.size()) * ROW_H + BOTTOM_PAD;
        int minPanel = TITLE_H + HEADER_H + ROW_H + BOTTOM_PAD;
        int maxPanel = Math.max(minPanel, this.height - SCREEN_MARGIN * 2);
        panelHeight = Math.min(desired, maxPanel);
        panelLeft = (this.width - panelWidth) / 2;
        panelTop = Math.max(SCREEN_MARGIN, (this.height - panelHeight) / 2);
        listTop = panelTop + TITLE_H + HEADER_H;
        listBottom = panelTop + panelHeight - BOTTOM_PAD;
        viewportH = Math.max(ROW_H, listBottom - listTop);
        maxScroll = Math.max(0, rows.size() * ROW_H - viewportH);
        scroll = Mth.clamp(scroll, 0, maxScroll);
    }

    /** 三横杠栏 / 名称栏 / 坐标栏 / 垃圾桶栏的左边界。 */
    private int handleLeft() {
        return panelLeft + SIDE_PAD;
    }

    private int nameLeft() {
        return handleLeft() + HANDLE_W + GAP;
    }

    private int coordLeft() {
        return nameLeft() + NAME_W + GAP;
    }

    private int trashLeft() {
        return coordLeft() + COORD_W * 3 + GAP;
    }

    /** 已有数据时整表刷新（同 id 重复下发的兜底路径）。 */
    private void applyData(List<Long> positions, List<String> names) {
        rows.clear();
        seeds.clear();
        seeds.addAll(unzip(positions, names));
        scroll = 0;
        dragIndex = -1;
        scrollDragging = false;
        rebuildWidgets();
    }

    /** 展示名：自定义名为空就回默认名。 */
    private String defaultName(long posLong, String custom) {
        if (custom != null && !custom.isBlank()) {
            return custom;
        }
        BlockPos pos = BlockPos.of(posLong);
        return Component.translatable("gui.origolds_ia.volunteer_bind.default_name",
                pos.getX(), pos.getY(), pos.getZ()).getString();
    }

    /**
     * 不画任何背景：原版会在这里触发整屏高斯模糊并压暗世界，本界面要求背景清晰可见，
     * 所以整段留空（既不模糊也不加暗色蒙版）。
     */
    @Override
    public void renderBackground(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        // 故意留空。
    }

    @Override
    public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(gui, mouseX, mouseY, partialTick);
        layout();
        gui.fill(panelLeft, panelTop, panelLeft + panelWidth, panelTop + panelHeight, COLOR_PANEL);
        drawBorder(gui);

        gui.drawCenteredString(this.font, this.title, panelLeft + panelWidth / 2, panelTop + 3, COLOR_TEXT);

        int nameLeft = nameLeft();
        int coordLeft = coordLeft();
        int headerY = panelTop + TITLE_H + 2;
        gui.drawString(this.font, Component.translatable("gui.origolds_ia.volunteer_bind.header_name"),
                nameLeft + 2, headerY, COLOR_DIM);
        String[] headers = {"X", "Y", "Z"};
        for (int c = 0; c < 3; c++) {
            String text = headers[c];
            int cellLeft = coordLeft + c * COORD_W;
            gui.drawString(this.font, text, cellLeft + (COORD_W - this.font.width(text)) / 2, headerY, COLOR_DIM);
        }

        // 列表区：超出视口的部分裁掉，滚动条 / 滚轮负责浏览。
        gui.enableScissor(panelLeft + 1, listTop, panelLeft + panelWidth - 1, listBottom);
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            int y = rowY(i);
            boolean visible = y + ROW_H > listTop && y < listBottom;
            row.box.setVisible(visible);
            row.box.setX(nameLeft);
            row.box.setY(y + (ROW_H - 14) / 2);
            if (!visible) {
                continue;
            }
            // 三横杠拖拽标志（在 24 宽的槽里居中画 14 像素的贴图）。
            gui.blit(HANDLE_ICON, panelLeft + SIDE_PAD + (HANDLE_W - 14) / 2, y + (ROW_H - 14) / 2,
                    0.0F, 0.0F, 14, 14, 16, 16);
            // 坐标：水平竖直居中，纯展示。
            int textY = y + (ROW_H - this.font.lineHeight) / 2 + 1;
            String[] values = {Integer.toString(row.pos.getX()), Integer.toString(row.pos.getY()),
                    Integer.toString(row.pos.getZ())};
            for (int c = 0; c < 3; c++) {
                int cellLeft = coordLeft + c * COORD_W;
                gui.drawString(this.font, values[c],
                        cellLeft + (COORD_W - this.font.width(values[c])) / 2, textY, COLOR_TEXT);
            }
            // 垃圾桶：左键删除这条绑定，悬停时微微变白。
            drawTrashIcon(gui, y, mouseX, mouseY);
        }
        super.render(gui, mouseX, mouseY, partialTick);
        gui.disableScissor();

        if (rows.isEmpty()) {
            gui.drawCenteredString(this.font, Component.translatable("gui.origolds_ia.volunteer_bind.empty"),
                    panelLeft + panelWidth / 2, listTop + 4, COLOR_DIM);
        }

        drawScrollbar(gui);
        renderNameTooltip(gui, mouseX, mouseY);
    }

    /**
     * 画第 {@code rowTop} 行最右侧的垃圾桶图标；鼠标悬停时把同一张贴图用加色混合再叠一层，
     * 只有图标真正不透明的像素会变亮，透明留白不受影响（直接盖白方块会露出一个方框）。
     */
    private void drawTrashIcon(GuiGraphics gui, int rowTop, int mouseX, int mouseY) {
        int x = trashLeft() + (TRASH_W - 16) / 2;
        int y = rowTop + (ROW_H - 16) / 2;
        gui.blit(TRASH_ICON, x, y, 0.0F, 0.0F, 16, 16, 16, 16);
        boolean hovered = mouseX >= x && mouseX < x + 16 && mouseY >= y && mouseY < y + 16;
        if (!hovered) {
            return;
        }
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, TRASH_HOVER_WHITEN);
        gui.blit(TRASH_ICON, x, y, 0.0F, 0.0F, 16, 16, 16, 16);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.defaultBlendFunc();
    }

    /** 第 i 行的当前顶边 y：拖拽中的行严格跟着鼠标走（按下点始终在鼠标下），其余按滚动量偏移。 */
    private int rowY(int i) {
        if (i == dragIndex) {
            return (int) (mouseYLast - dragOffsetY);
        }
        return listTop + i * ROW_H - scroll;
    }

    /** 鼠标纵坐标落在第几行；不在列表区 / 没有对应行时返回 -1。 */
    private int rowIndexAt(double mouseY) {
        if (mouseY < listTop || mouseY >= listBottom) {
            return -1;
        }
        int idx = (int) Math.floor((mouseY - listTop + scroll) / ROW_H);
        return idx >= 0 && idx < rows.size() ? idx : -1;
    }

    private double mouseYLast;

    private int scrollbarX() {
        return panelLeft + panelWidth - SIDE_PAD + 3;
    }

    private int thumbHeight() {
        int total = Math.max(1, rows.size() * ROW_H);
        return Math.max(MIN_THUMB_H, (int) ((long) viewportH * viewportH / total));
    }

    /** 按鼠标位置把滑块中心对到鼠标上，换算成滚动量。 */
    private void scrollFromMouse(double mouseY) {
        int thumbH = thumbHeight();
        int travel = Math.max(1, viewportH - thumbH);
        double t = (mouseY - listTop - thumbH / 2.0) / travel;
        scroll = (int) Math.round(Mth.clamp(t, 0.0, 1.0) * maxScroll);
    }

    private void drawScrollbar(GuiGraphics gui) {
        if (maxScroll <= 0) {
            return;
        }
        int x = scrollbarX();
        gui.fill(x, listTop, x + SCROLLBAR_W, listBottom, COLOR_SCROLL_TRACK);
        int thumbH = thumbHeight();
        int travel = Math.max(0, viewportH - thumbH);
        int thumbY = listTop + (int) Math.round((double) scroll / maxScroll * travel);
        gui.fill(x, thumbY, x + SCROLLBAR_W, thumbY + thumbH, COLOR_BORDER);
    }

    private void drawBorder(GuiGraphics gui) {
        gui.fill(panelLeft, panelTop, panelLeft + panelWidth, panelTop + 1, COLOR_BORDER);
        gui.fill(panelLeft, panelTop + panelHeight - 1, panelLeft + panelWidth, panelTop + panelHeight, COLOR_BORDER);
        gui.fill(panelLeft, panelTop, panelLeft + 1, panelTop + panelHeight, COLOR_BORDER);
        gui.fill(panelLeft + panelWidth - 1, panelTop, panelLeft + panelWidth, panelTop + panelHeight, COLOR_BORDER);
    }

    /** 名称超长时悬停 0.5 秒弹提示框（拖拽中不打扰）。 */
    private void renderNameTooltip(GuiGraphics gui, int mouseX, int mouseY) {
        int hovered = -1;
        if (dragIndex < 0 && !scrollDragging) {
            int idx = rowIndexAt(mouseY);
            int nameLeft = nameLeft();
            if (idx >= 0 && mouseX >= nameLeft && mouseX < nameLeft + NAME_W) {
                hovered = idx;
            }
        }
        if (hovered != hoverRow) {
            hoverRow = hovered;
            hoverStartMs = System.currentTimeMillis();
        }
        if (hoverRow < 0 || hoverRow >= rows.size()
                || System.currentTimeMillis() - hoverStartMs < TOOLTIP_HOLD_MS) {
            return;
        }
        Row row = rows.get(hoverRow);
        String full = row.custom.isBlank() ? defaultName(row.posLong, "") : row.custom;
        if (this.font.width(full) <= NAME_W - 8) {
            return; // 没截断就不弹
        }
        gui.renderTooltip(this.font, Component.literal(full), mouseX, mouseY);
    }

    // ------------------------------------------------------------------
    // 交互
    // ------------------------------------------------------------------

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        this.mouseYLast = mouseY;
        int handleLeft = handleLeft();
        if (button == 1) {
            // 右键三横杠：进入 / 切换 / 退出预览模式；在别处右键则退出预览。
            int idx = rowIndexAt(mouseY);
            if (idx >= 0 && mouseX >= handleLeft && mouseX < handleLeft + HANDLE_W) {
                commitBox(focusedBox());
                Row row = rows.get(idx);
                if (OGEVolunteerBindClient.isPreviewing(volunteerId, row.posLong)) {
                    OGEVolunteerBindClient.stopPreview();
                } else {
                    // 预览是世界里的效果，配置界面会挡住它：开启后立刻关掉界面。
                    closeForPreview = true;
                    OGEVolunteerBindClient.startPreview(volunteerId, row.posLong);
                    this.onClose();
                }
                return true;
            }
            if (OGEVolunteerBindClient.isPreviewingVolunteer(volunteerId)) {
                OGEVolunteerBindClient.stopPreview();
                return true;
            }
            return super.mouseClicked(mouseX, mouseY, button);
        }
        if (button == 0) {
            // 先判滚动条，它压在右留白上。
            if (maxScroll > 0 && mouseX >= scrollbarX() && mouseX < scrollbarX() + SCROLLBAR_W
                    && mouseY >= listTop && mouseY < listBottom) {
                scrollDragging = true;
                scrollFromMouse(mouseY);
                return true;
            }
            int idx = rowIndexAt(mouseY);
            // 垃圾桶：删掉这条绑定。
            if (idx >= 0 && mouseX >= trashLeft() && mouseX < trashLeft() + TRASH_W) {
                commitBox(focusedBox());
                deleteRow(idx);
                return true;
            }
            // 按在三横杠上：先存掉当前输入，再开始拖这一行。
            if (idx >= 0 && mouseX >= handleLeft && mouseX < handleLeft + HANDLE_W) {
                commitBox(focusedBox());
                // 先按未拖拽时的行位算出按下点相对行顶的偏移，再进入拖拽状态：
                // rowY 对 dragIndex 会走"跟鼠标"分支，顺序反了就会让行顶吸到鼠标。
                double rowTop = rowY(idx);
                dragIndex = idx;
                dragOffsetY = mouseY - rowTop;
                return true;
            }
            // 记录点之前的焦点框：焦点一旦转移（含点到空白、点到别的框），把离开的框保存掉。
            EditBox before = focusedBox();
            boolean handled;
            if (mouseY >= listTop && mouseY < listBottom) {
                handled = super.mouseClicked(mouseX, mouseY, button);
            } else {
                // 列表区外（标题 / 面板外）：保存并取消焦点，避免点到滚出视口的输入框。
                setFocused(null);
                handled = true;
            }
            if (before != null && before != focusedBox()) {
                commitBox(before);
            }
            return handled;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /** 删掉一行：先本地移除（界面立刻少一行），再发报文让服务端解绑。 */
    private void deleteRow(int idx) {
        Row row = rows.remove(idx);
        removeWidget(row.box);
        if (getFocused() == row.box) {
            setFocused(null);
        }
        if (OGEVolunteerBindClient.isPreviewing(volunteerId, row.posLong)) {
            OGEVolunteerBindClient.stopPreview();
        }
        PacketDistributor.sendToServer(OGEVolunteerBindEditPayload.ofDelete(volunteerId, row.posLong));
        layout();
    }

    private EditBox focusedBox() {
        return getFocused() instanceof EditBox box ? box : null;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (maxScroll > 0) {
            scroll = (int) Mth.clamp(scroll - scrollY * ROW_H, 0, maxScroll);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        this.mouseYLast = mouseY;
        if (scrollDragging) {
            scrollFromMouse(mouseY);
            return true;
        }
        if (dragIndex >= 0) {
            // 鼠标所在行就是新序号：实时整表挪位，让别的行让路。
            int target = (int) Math.floor((mouseY - listTop + scroll) / ROW_H);
            target = Math.max(0, Math.min(rows.size() - 1, target));
            if (target != dragIndex) {
                Row moving = rows.remove(dragIndex);
                rows.add(target, moving);
                dragIndex = target;
            }
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (scrollDragging) {
            scrollDragging = false;
            return true;
        }
        if (dragIndex >= 0) {
            dragIndex = -1;
            List<Long> order = new ArrayList<>(rows.size());
            for (Row row : rows) {
                order.add(row.posLong);
            }
            PacketDistributor.sendToServer(OGEVolunteerBindEditPayload.ofReorder(volunteerId, order));
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER || keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER) {
            commitBox(focusedBox());
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        commitBox(focusedBox());
        if (!closeForPreview) {
            OGEVolunteerBindClient.stopPreview();
        }
        super.onClose();
    }

    @Override
    public void removed() {
        if (!closeForPreview) {
            OGEVolunteerBindClient.stopPreview();
        }
        super.removed();
    }

    /** 把指定名称框的内容存进本地行并发改服务端。 */
    private void commitBox(EditBox box) {
        if (box == null) {
            return;
        }
        for (Row row : rows) {
            if (row.box == box) {
                commit(row);
                return;
            }
        }
    }

    private void commit(Row row) {
        String typed = row.box.getValue().strip();
        String defaultName = defaultName(row.posLong, "");
        String custom = typed.equals(defaultName) || typed.isBlank() ? "" : typed;
        // 输入结束后才回写显示：留空 / 恰好等于默认名 → 显示默认名。
        row.custom = custom;
        row.box.setValue(custom);
        row.box.setHint(Component.literal(defaultName));
        if (!custom.equals(row.committed)) {
            row.committed = custom;
            PacketDistributor.sendToServer(
                    OGEVolunteerBindEditPayload.ofRename(volunteerId, row.posLong, custom));
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
