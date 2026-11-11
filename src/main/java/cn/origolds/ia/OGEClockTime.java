package cn.origolds.ia;

import java.util.Locale;

/**
 * 金源红石脉冲钟（Origold Redstone-Pulse Clock）的时间换算工具。
 *
 * <p>界面在客户端解析三个输入框、显示倒计时，服务端负责真正的计时与发脉冲，
 * 两边都要做同一套换算，因此把规则集中放在这里：</p>
 * <ul>
 *   <li>游戏的最小时间单位是刻，{@value #TICKS_PER_SECOND} 刻 = 1 秒，
 *       也就是 1 刻 = 0.05 秒；</li>
 *   <li>界面上把时间显示成“时:分:秒（十毫秒）”，
 *       {@value #CENTIS_PER_TICK} 个十毫秒 = 1 刻；</li>
 *   <li>输入的小数、超过 60 的分/秒都在这里统一折算成刻，
 *       不足 1 刻的正数向上取到 1 刻，全 0 视为“停止计时”。</li>
 * </ul>
 */
public final class OGEClockTime {

    /** 一秒的游戏刻数。 */
    public static final int TICKS_PER_SECOND = 20;

    /** 一分钟的游戏刻数。 */
    public static final int TICKS_PER_MINUTE = 60 * TICKS_PER_SECOND;

    /** 一小时的游戏刻数。 */
    public static final int TICKS_PER_HOUR = 3600 * TICKS_PER_SECOND;

    /** 一个刻等于多少个“十毫秒”（1 刻 = 50ms = 5 × 10ms）。 */
    public static final int CENTIS_PER_TICK = 5;

    /** 允许输入的最大小时数（防止溢出 int）。 */
    public static final int MAX_HOURS = 999;

    /** 可设定的最大刻数：999:59:59.95。 */
    public static final int MAX_TICKS =
            (MAX_HOURS * 3600 + 59 * 60 + 59) * TICKS_PER_SECOND + (TICKS_PER_SECOND - 1);

    private OGEClockTime() {
    }

    /** 刻数里的小时部分。 */
    public static int hours(int ticks) {
        return ticks / TICKS_PER_HOUR;
    }

    /** 刻数里的分钟部分（0~59）。 */
    public static int minutes(int ticks) {
        return (ticks % TICKS_PER_HOUR) / TICKS_PER_MINUTE;
    }

    /** 刻数里的整秒部分（0~59）。 */
    public static int seconds(int ticks) {
        return (ticks % TICKS_PER_MINUTE) / TICKS_PER_SECOND;
    }

    /** 刻数里的十毫秒部分（0、5、10 …… 95）。 */
    public static int centis(int ticks) {
        return (ticks % TICKS_PER_SECOND) * CENTIS_PER_TICK;
    }

    /**
     * 解析一个输入框里的数字：空串、只有一个小数点、非法输入一律当作 0。
     * 输入框本身已经限制了字符，这里再兜一次底。
     */
    public static double parseDouble(String text) {
        if (text == null) {
            return 0.0;
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return 0.0;
        }
        try {
            return Double.parseDouble(trimmed);
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    /**
     * 把时、分、秒三个数值折算成游戏刻。
     *
     * <p>小数会自然折算（1.5 小时 = 1:30:00、1.5 分钟 = 0:01:30），分/秒超过 60
     * 会自动进位；结果按最近的刻四舍五入（即最近的 0.05 秒）。返回 0 表示
     * “停止计时”，正数最少 1 刻。</p>
     */
    public static int toTicks(double hours, double minutes, double seconds) {
        double totalSeconds = hours * 3600.0 + minutes * 60.0 + seconds;
        if (!(totalSeconds > 0.0)) {
            return 0;
        }
        long ticks = Math.round(totalSeconds * TICKS_PER_SECOND);
        if (ticks < 1L) {
            ticks = 1L;
        }
        return (int) Math.min((long) MAX_TICKS, ticks);
    }

    /** 秒框的规范文本：整秒时没有小数，否则保留两位（一定是 0.05 的倍数）。 */
    public static String formatSecondsField(int ticks) {
        int wholeSeconds = seconds(ticks);
        int hundredths = centis(ticks);
        if (hundredths == 0) {
            return Integer.toString(wholeSeconds);
        }
        return String.format(Locale.ROOT, "%d.%02d", wholeSeconds, hundredths);
    }

    /** “hh:mm:ss”，小时不截断（最大 999）。 */
    public static String formatHms(int ticks) {
        return String.format(Locale.ROOT, "%02d:%02d:%02d", hours(ticks), minutes(ticks), seconds(ticks));
    }

    /** 十毫秒部分，两位数字。 */
    public static String formatCentis(int ticks) {
        return String.format(Locale.ROOT, "%02d", centis(ticks));
    }
}
