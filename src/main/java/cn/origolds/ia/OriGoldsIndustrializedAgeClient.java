package cn.origolds.ia;

import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.gui.modlist.DefaultModDisplayInfo;
import net.neoforged.neoforge.client.gui.modlist.ModDisplayInfo;

/**
 * 仅客户端加载的入口（专用服务器不会加载此类）：模组列表里的模组名称按当前语言显示
 * （{@value #NAME_KEY}），简介沿用 NeoForge 默认的
 * {@code neoforge.screen.mods.info.description.<modid>} 翻译键。与三叉戟强无敌同一套做法。
 */
@Mod(value = OriGoldsIndustrializedAge.MOD_ID, dist = Dist.CLIENT)
public final class OriGoldsIndustrializedAgeClient {
    private static final String NAME_KEY = "neoforge.screen.mods.info.displayname." + OriGoldsIndustrializedAge.MOD_ID;

    public OriGoldsIndustrializedAgeClient(ModContainer container) {
        container.registerExtensionPoint(ModDisplayInfo.class, new DefaultModDisplayInfo(container) {
            @Override
            public Component displayName() {
                return Component.translatableWithFallback(NAME_KEY, container.getModInfo().getDisplayName());
            }
        });
    }
}
