package cn.origolds.ia;

import net.minecraft.client.resources.language.I18n;
import net.neoforged.neoforgespi.language.IModInfo;

/**
 * 模组列表里的双语名称（与三叉戟强无敌 Awesome Tridents 同一套做法）。
 *
 * <p>这一版 NeoForge 的模组列表会按 {@code fml.menu.mods.info.description.<modid>} 翻译简介，
 * 却只显示 neoforge.mods.toml 里写死的 displayName。{@code mixin.client} 包里的几个客户端 Mixin
 * 在读取名称处调用这里：只对本模组、且当前语言有 {@value #NAME_KEY} 译文时换成翻译名。</p>
 *
 * <p>此类必须位于 Mixin 配置包之外，供 Mixin 安全引用。</p>
 */
public final class OGEModListNames {
    public static final String NAME_KEY = "fml.menu.mods.info.displayname." + OriGoldsIndustrializedAge.MOD_ID;

    private OGEModListNames() {
    }

    /** 显示用：翻译名（没有译文时为原名）。 */
    public static String displayName(IModInfo info, String original) {
        if (OriGoldsIndustrializedAge.MOD_ID.equals(info.getModId()) && I18n.exists(NAME_KEY)) {
            return I18n.get(NAME_KEY);
        }
        return original;
    }

    /**
     * 搜索用：翻译名 + 原名。搜索框只拿这个字符串做包含匹配，所以中文界面下搜
     * "Industrialized Age" 或 "工业时代" 都能找到本模组；它不会显示在界面上。
     */
    public static String searchName(IModInfo info, String original) {
        String shown = displayName(info, original);
        return shown.equals(original) ? original : shown + " " + original;
    }
}
