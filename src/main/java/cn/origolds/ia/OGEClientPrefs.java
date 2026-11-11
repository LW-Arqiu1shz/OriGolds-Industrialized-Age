package cn.origolds.ia;

import net.neoforged.fml.loading.FMLPaths;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * 客户端偏好：把“金源能量显示模式”的开关状态写入
 * {@code config/origolds_ia-client.properties}，重启游戏后自动恢复。
 *
 * <p>读写失败时静默退回默认值（关），不影响游戏。</p>
 */
public final class OGEClientPrefs {

    private static final String FILE_NAME = "origolds_ia-client.properties";
    private static final String KEY_DISPLAY_MODE = "oge_display_mode";
    private static final String KEY_LOGISTICS_MODE = "oge_logistics_mode";

    private OGEClientPrefs() {
    }

    /** 读取上次保存的显示模式开关；文件缺失或损坏时返回 false。 */
    public static boolean loadDisplayMode() {
        try {
            Properties props = read();
            return Boolean.parseBoolean(props.getProperty(KEY_DISPLAY_MODE, "false"));
        } catch (Throwable t) {
            return false;
        }
    }

    /** 保存显示模式开关。 */
    public static void saveDisplayMode(boolean on) {
        try {
            Properties props = read();
            props.setProperty(KEY_DISPLAY_MODE, String.valueOf(on));
            Path file = file();
            Files.createDirectories(file.getParent());
            try (OutputStream out = Files.newOutputStream(file)) {
                props.store(out, "OriGolds: Industrialized Age - client display preferences");
            }
        } catch (Throwable ignored) {
            // 写失败不影响本次游戏，下次切换会再试
        }
    }

    /** 读取上次保存的物流显示模式开关；文件缺失或损坏时返回 false。 */
    public static boolean loadLogisticsMode() {
        try {
            Properties props = read();
            return Boolean.parseBoolean(props.getProperty(KEY_LOGISTICS_MODE, "false"));
        } catch (Throwable t) {
            return false;
        }
    }

    /** 保存物流显示模式开关。 */
    public static void saveLogisticsMode(boolean on) {
        try {
            Properties props = read();
            props.setProperty(KEY_LOGISTICS_MODE, String.valueOf(on));
            Path file = file();
            Files.createDirectories(file.getParent());
            try (OutputStream out = Files.newOutputStream(file)) {
                props.store(out, "OriGolds: Industrialized Age - client display preferences");
            }
        } catch (Throwable ignored) {
            // 写失败不影响本次游戏，下次切换会再试
        }
    }

    private static Properties read() throws Exception {
        Properties props = new Properties();
        Path file = file();
        if (Files.exists(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                props.load(in);
            }
        }
        return props;
    }

    private static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve(FILE_NAME);
    }
}
