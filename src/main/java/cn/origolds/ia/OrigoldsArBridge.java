package cn.origolds.ia;

import net.neoforged.fml.ModList;

import java.lang.reflect.Field;

import sun.misc.Unsafe;

/**
 * 与父模组（origolds_ar）客户端的桥接。
 *
 * <p>用于实现三模式互斥：开启本模组任一显示模式时，把父模组客户端的
 * “无线红石线路显示模式”（私有静态字段
 * {@code cn.blockforge.generated.origoldsar.client.WirelessConnectorClient#connectorDisplay}）
 * 关掉；父模组模式开启时反过来关掉本模组的两个模式。</p>
 *
 * <p><b>跨模组访问的两级加固策略</b>（此前只用了普通反射，在某些整合包
 * 的类加载/模块封装环境下会静默失效，导致互斥不生效）：</p>
 * <ol>
 *   <li>类解析依次尝试：调用方类加载器 → 线程上下文类加载器 →
 *       父模组自身的类加载器（经 {@link ModList} 拿到 origolds_ar 的
 *       mod 实例后取其 defining loader——它一定能加载自己 jar 里的类）；</li>
 *   <li>字段读写先用普通反射（setAccessible），被模块系统拒绝时降级为
 *       {@link Unsafe} 的静态字段寻址，绕过访问检查。</li>
 * </ol>
 *
 * <p>全程静默降级：父模组未安装、类被移除或字段改名时不做任何事，绝不抛错。</p>
 */
public final class OrigoldsArBridge {

    /** 父模组的 mod id。 */
    private static final String PARENT_MOD_ID = "origolds_ar";

    /** 父模组无线连接装置的客户端类（origolds_ar r166 起存在）。 */
    private static final String PARENT_CLIENT_CLASS =
            "cn.blockforge.generated.origoldsar.client.WirelessConnectorClient";

    /** 字段名。 */
    private static final String PARENT_DISPLAY_FIELD = "connectorDisplay";

    // ---- 解析结果（volatile 读，synchronized 写；每客户端刻轮询代价极小） ----

    /** true＝已完成一次解析尝试（成功或彻底失败）。 */
    private static volatile boolean tried;
    /** 普通反射通道：可 setAccessible 的字段句柄。 */
    private static volatile Field connectorDisplayField;
    /** Unsafe 兜底通道：静态字段基址 + 偏移。 */
    private static volatile Object unsafeBase;
    private static volatile long unsafeOffset;
    /** Unsafe 通道是否就绪。 */
    private static volatile boolean unsafeReady;
    /** 解析失败原因（供调试提示；成功时为 null）。 */
    private static volatile String failure;

    private OrigoldsArBridge() {
    }

    /** 桥是否可用（父模组在且开关可读写）。 */
    public static boolean isAvailable() {
        resolveIfNeeded();
        return connectorDisplayField != null || unsafeReady;
    }

    /** 最近一次解析失败的原因；可用时为 null。 */
    public static String failureReason() {
        resolveIfNeeded();
        return failure;
    }

    /** 父模组的“无线红石线路显示模式”是否安装（mod 层面）。 */
    public static boolean isParentModLoaded() {
        return ModList.get().isLoaded(PARENT_MOD_ID);
    }

    /** 关闭父模组的“线路显示模式”；父模组不在或字段找不到时静默跳过。 */
    public static void turnOffParentLineDisplay() {
        write(false);
    }

    /**
     * 读取父模组“线路显示模式”当前是否开启；父模组不在或字段找不到时返回 {@code false}。
     * 供本模组每客户端刻轮询，实现“父模组红石显示开启时关掉本模组两个模式”的互斥联动。
     */
    public static boolean isParentLineDisplayOn() {
        return read();
    }

    // ------------------------------------------------------------------
    // 读写
    // ------------------------------------------------------------------

    private static boolean read() {
        resolveIfNeeded();
        Field field = connectorDisplayField;
        if (field != null) {
            try {
                return field.getBoolean(null);
            } catch (Throwable ignored) {
                // 落到 Unsafe 通道
            }
        }
        Unsafe unsafe = UnsafeHolder.UNSAFE;
        if (unsafe != null && unsafeBase != null) {
            try {
                return unsafe.getBoolean(unsafeBase, unsafeOffset);
            } catch (Throwable ignored) {
                return false;
            }
        }
        return false;
    }

    private static void write(boolean value) {
        resolveIfNeeded();
        Field field = connectorDisplayField;
        if (field != null) {
            try {
                field.setBoolean(null, value);
                return;
            } catch (Throwable ignored) {
                // 落到 Unsafe 通道
            }
        }
        Unsafe unsafe = UnsafeHolder.UNSAFE;
        if (unsafe != null && unsafeBase != null) {
            try {
                unsafe.putBoolean(unsafeBase, unsafeOffset, value);
            } catch (Throwable ignored) {
                // 放弃联动
            }
        }
    }

    // ------------------------------------------------------------------
    // 解析
    // ------------------------------------------------------------------

    private static void resolveIfNeeded() {
        if (tried) {
            return;
        }
        // 父模组不在：不解析也不缓存失败（整合包运行时可热安装？——保守起见每刻轻量复查）
        if (!isParentModLoaded()) {
            return;
        }
        synchronized (OrigoldsArBridge.class) {
            if (!tried) {
                try {
                    resolve();
                } catch (Throwable t) {
                    failure = "resolve: " + t;
                }
                tried = true;
            }
        }
    }

    private static void resolve() {
        if (!isParentModLoaded()) {
            failure = null; // 父模组不在：正常静默，不算故障
            return;
        }
        Class<?> cls = findParentClass();
        if (cls == null) {
            failure = "找不到父模组客户端类 " + PARENT_CLIENT_CLASS;
            return;
        }
        Field field;
        try {
            field = cls.getDeclaredField(PARENT_DISPLAY_FIELD);
        } catch (Throwable t) {
            failure = "找不到字段 " + PARENT_DISPLAY_FIELD + "：" + t;
            return;
        }
        // 通道 1：普通反射
        try {
            field.setAccessible(true);
            connectorDisplayField = field;
            failure = null;
            return;
        } catch (Throwable ignored) {
            // JPMS 强封装拒绝，落到通道 2
        }
        // 通道 2：Unsafe 静态字段寻址（不受模块开闭限制）
        Unsafe unsafe = UnsafeHolder.UNSAFE;
        if (unsafe != null) {
            try {
                unsafeBase = unsafe.staticFieldBase(field);
                unsafeOffset = unsafe.staticFieldOffset(field);
                unsafeReady = true;
                failure = null;
                return;
            } catch (Throwable t) {
                failure = "Unsafe 通道失败：" + t;
            }
        } else {
            failure = "反射与 Unsafe 通道均不可用";
        }
    }

    /** 依次用多种类加载器解析父模组客户端类；全部失败返回 null。 */
    private static Class<?> findParentClass() {
        // 1) 调用方类加载器（多数整合包所有模组共享一个加载器，直接命中）
        try {
            return Class.forName(PARENT_CLIENT_CLASS);
        } catch (Throwable ignored) {
        }
        // 2) 线程上下文类加载器
        try {
            ClassLoader ctx = Thread.currentThread().getContextClassLoader();
            if (ctx != null) {
                return Class.forName(PARENT_CLIENT_CLASS, true, ctx);
            }
        } catch (Throwable ignored) {
        }
        // 3) 游戏本体类加载器（开发环境与部分启动配置下 mod 类可见）
        try {
            return Class.forName(PARENT_CLIENT_CLASS, true, net.minecraft.client.Minecraft.class.getClassLoader());
        } catch (Throwable ignored) {
        }
        // 4) 父模组自己的类加载器：从 FML 的 FMLModContainer（mod id → 容器）里取
        //    它已加载的 @Mod 类（私有字段 modClasses），其 defining loader 必然
        //    能加载父模组自己 jar 内的类
        try {
            Object container = ModList.get().getModContainerById(PARENT_MOD_ID).orElse(null);
            if (container != null) {
                for (Class<?> c = container.getClass(); c != null; c = c.getSuperclass()) {
                    if (!c.getName().equals("net.neoforged.fml.javafmlmod.FMLModContainer")) {
                        continue;
                    }
                    Field modClassesField = c.getDeclaredField("modClasses");
                    modClassesField.setAccessible(true);
                    Object value = modClassesField.get(container);
                    if (value instanceof java.util.List<?> list && !list.isEmpty()
                            && list.get(0) instanceof Class<?> entryClass) {
                        return Class.forName(PARENT_CLIENT_CLASS, true, entryClass.getClassLoader());
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 取 {@link Unsafe} 单例（jdk.unsupported 默认开放反射）。 */
    private static final class UnsafeHolder {
        static final Unsafe UNSAFE = lookup();

        private static Unsafe lookup() {
            try {
                Field theUnsafe = Unsafe.class.getDeclaredField("theUnsafe");
                theUnsafe.setAccessible(true);
                return (Unsafe) theUnsafe.get(null);
            } catch (Throwable t) {
                return null;
            }
        }

        private UnsafeHolder() {
        }
    }
}
