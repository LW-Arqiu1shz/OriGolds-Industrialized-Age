package cn.origolds.ia;

import net.neoforged.fml.ModList;

/**
 * 与父模组（origolds_ar 1.2.0+）预留的 WireVisuals 线路显示接口的对接闸门。
 *
 * <p>父模组在 {@code cn.blockforge.generated.origoldsar.api.visual} 包里开放了一套
 * 无线线路渲染通道（{@code WireVisuals.register(id, priority, active, collect)}），
 * 附带深度分层、泛光、悬停高亮与 Iris 兼容。本模组的<b>全部世界高亮</b>——金源能量、
 * 无线物流两个显示模式的线路，志愿者快速绑定/预览高亮，以及金源放置器投影外框线——
 * 在父模组在场时全部转交给这套接口渲染（穿墙可见、光影兼容），
 * 具体接线见 {@link OGEWireVisualsChannels}。</p>
 *
 * <p><b>安全降级</b>：父模组未安装、或安装的是没有该 API 包的旧版时，
 * 这里探测失败后本模组继续沿用旧的自绘路径（{@code OGEWirelessConnectorClient}
 * 里的 {@code SubmitCustomGeometryEvent} 绘制），不报错、不崩溃。
 * 探测与注册只做一次（幂等），由客户端刻触发。</p>
 */
public final class OGEWireVisualsBridge {

    /** WireVisuals 接口类名——先按名探测，成功才允许加载引用它的通道类。 */
    private static final String API_CLASS =
            "cn.blockforge.generated.origoldsar.api.visual.WireVisuals";

    /** true＝父模组的线路通道已注册，本模组的两个模式渲染全部走父模组管线。 */
    private static volatile boolean delegated;
    /** 是否已完成一次探测（成功或失败都算）。 */
    private static volatile boolean tried;
    /** 最近一次探测失败原因（仅调试用）。 */
    private static volatile String failure;

    private OGEWireVisualsBridge() {
    }

    /**
     * 幂等探测并注册。线程安全：双重检查 + 同步块，注册本身也在同步块内，
     * 保证 {@code delegated=true} 时通道必然已经注册完毕。
     */
    public static void ensureRegistered() {
        if (tried) {
            return;
        }
        synchronized (OGEWireVisualsBridge.class) {
            if (tried) {
                return;
            }
            try {
                if (!ModList.get().isLoaded("origolds_ar")) {
                    failure = "父模组 origolds_ar 未安装";
                    return;
                }
                // 先探测接口类是否存在（旧版父模组没有这个包）：依次用父模组的
                // 定义类加载器与本模组类加载器尝试，任一命中即算接口可用。
                boolean apiFound = probeApiClass(OrigoldsArBridge.parentModClassLoader())
                        || probeApiClass(OGEWireVisualsBridge.class.getClassLoader());
                if (!apiFound) {
                    failure = "父模组没有 " + API_CLASS + "（版本过旧）";
                    return;
                }
                OGEWireVisualsChannels.registerAll();
                delegated = true;
                failure = null;
            } catch (Throwable t) {
                delegated = false;
                failure = String.valueOf(t);
            } finally {
                tried = true;
            }
        }
    }

    /** 在指定类加载器里探测 WireVisuals 接口类是否存在（不触发静态初始化）。 */
    private static boolean probeApiClass(ClassLoader loader) {
        if (loader == null) {
            return false;
        }
        try {
            Class.forName(API_CLASS, false, loader);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 本模组的两个模式的线路渲染是否已移交父模组 WireVisuals 通道。 */
    public static boolean isDelegated() {
        return delegated;
    }

    /** 最近一次探测失败的原因（调试/日志用；成功时为 null）。 */
    public static String failureReason() {
        return failure;
    }
}
