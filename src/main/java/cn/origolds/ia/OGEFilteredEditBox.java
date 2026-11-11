package cn.origolds.ia;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.network.chat.Component;

/**
 * 带输入校验的文本框：把 26.1.2 时代 {@code EditBox.setFilter(Predicate<String>)} 的语义补回来。
 *
 * <p>26.3 起 {@code setFilter} 被删除，只剩下显示层的
 * {@code addFormatter(EditBox.TextFormatter)}——它只改渲染结果、不拦输入，
 * 不能当校验用。这里改成“先让父类正常写入，写完再校验，不合法就整体回退”：</p>
 *
 * <ul>
 *   <li>覆写 {@link #charTyped(CharacterEvent)} 与 {@link #insertText(String)}：
 *       记下改动前的文本 → 调 {@code super} → 若当前值不再满足过滤器，就用
 *       {@code setValue(before)} 回到改动前，并把光标/选区还原；</li>
 *   <li>被拒绝时 {@link #charTyped(CharacterEvent)} 返回 {@code true}：这次按键已被本控件消费，
 *       不会再穿透到界面上的其它控件（旧版 setFilter 拦下时也是同样效果）；</li>
 *   <li>responder（各界面用它标记“玩家亲手编辑过”）只在改动被接受后回调一次：
 *       校验期间与回退期间一律静默，否则非法字符也会把界面标成“已编辑”，退出时误发保存包；</li>
 *   <li>程序性 {@code setValue(...)} 不做特殊放行，走父类原样写回（各界面回填的都是合法值）。</li>
 * </ul>
 *
 * <p>用法与旧 API 完全一致：{@code new OGEFilteredEditBox(...)} 之后
 * {@code box.setFilter(text -> ...)}，调用点无需改动。</p>
 */
public class OGEFilteredEditBox extends EditBox {

    /** 当前过滤器；默认全放行，等价于没设过 filter 的原版 EditBox。 */
    private Predicate<String> filter = text -> true;

    /** 真正的业务回调（各界面通过 {@link #setResponder} 交进来）。 */
    private Consumer<String> responder;

    /** 正在回退到改动前的文本：期间不向外通知。 */
    private boolean restoring;

    /** “改后校验”的嵌套深度：>0 表示这次改动还没定论，先扣住回调。 */
    private int validating;

    /** 改动前的选区锚点：26.3 的 EditBox 没有 getHighlightPos，只能自己记。 */
    private int highlightPos;

    public OGEFilteredEditBox(Font font, Component message) {
        super(font, message);
    }

    public OGEFilteredEditBox(Font font, int width, int height, Component message) {
        super(font, width, height, message);
    }

    public OGEFilteredEditBox(Font font, int x, int y, int width, int height, Component message) {
        super(font, x, y, width, height, message);
    }

    public OGEFilteredEditBox(Font font, int x, int y, int width, int height, EditBox previousDelegate,
                              Component message) {
        super(font, x, y, width, height, previousDelegate, message);
    }

    /** 与旧版同名同参：设置输入过滤器，{@code null} 视为不过滤。 */
    public void setFilter(Predicate<String> filter) {
        this.filter = filter != null ? filter : (text -> true);
    }

    @Override
    public void setResponder(Consumer<String> responder) {
        this.responder = responder;
        // 交给父类的永远是这层守卫：校验中、回退中都不通知，接受与否由本类统一决定。
        super.setResponder(text -> {
            if (this.restoring || this.validating > 0) {
                return;
            }
            if (this.responder != null) {
                this.responder.accept(text);
            }
        });
    }

    @Override
    public void setHighlightPos(int pos) {
        // 记一份锚点，供回退时还原选区（父类只在 setHighlightPos 里写这个字段）。
        this.highlightPos = pos;
        super.setHighlightPos(pos);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        return tryValidatedChange(() -> super.charTyped(event));
    }

    @Override
    public void insertText(String text) {
        // 粘贴、双击选词后的覆盖输入以及父类 charTyped 的落字最终都走这里。
        tryValidatedChange(() -> {
            super.insertText(text);
            return false;
        });
    }

    /**
     * 执行一次文本改动并校验：合法则（在最外层）补发一次 responder，
     * 非法则整体回退到改动前且不发通知。
     *
     * <p>父类 {@code charTyped} 内部会再调 {@code this.insertText}，两个覆写点会嵌套，
     * 因此用 {@link #validating} 保证只有最外层那次负责回退与通知，回调不会重复。</p>
     *
     * @param change 实际的改动，返回父类“是否消费了这次输入”的结果
     * @return 被过滤器拒绝时返回 {@code true}（按键已消费，别穿透到界面）；
     *         其余情况原样返回 {@code change} 的结果
     */
    private boolean tryValidatedChange(BooleanSupplier change) {
        String before = getValue();
        int cursorBefore = getCursorPosition();
        int highlightBefore = this.highlightPos;
        boolean outermost = this.validating == 0;
        this.validating++;
        boolean consumed;
        try {
            consumed = change.getAsBoolean();
        } finally {
            this.validating--;
        }
        String after = getValue();
        if (after.equals(before)) {
            // 文本没变（不可编辑、焦点不在本框等）：保持父类语义。
            return consumed;
        }
        if (!this.filter.test(after)) {
            // 回退到改动前的文本，并还原光标与选区（setValue 会把光标顶到末尾）。
            this.restoring = true;
            try {
                setValue(before);
            } finally {
                this.restoring = false;
            }
            int length = before.length();
            moveCursorTo(Math.min(cursorBefore, length), true);
            setHighlightPos(Math.min(highlightBefore, length));
            return true;
        }
        if (outermost && this.responder != null) {
            this.responder.accept(after);
        }
        return consumed;
    }
}
