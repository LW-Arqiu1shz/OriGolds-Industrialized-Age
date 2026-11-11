package cn.origolds.ia.mixin.client;

import cn.origolds.ia.OGEModListNames;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.neoforged.neoforge.client.gui.ModListScreen;
import net.neoforged.neoforgespi.language.IModInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** 模组列表界面：右侧信息面板标题、列表宽度计算用翻译名；搜索过滤同时匹配翻译名与原名。 */
@Mixin(ModListScreen.class)
public abstract class ModListScreenMixin {
    @WrapOperation(method = {"init", "updateCache"},
            at = @At(value = "INVOKE", target = "Lnet/neoforged/neoforgespi/language/IModInfo;getDisplayName()Ljava/lang/String;"))
    private String origoldsIa$translatedName(IModInfo info, Operation<String> original) {
        return OGEModListNames.displayName(info, original.call(info));
    }

    /** reloadMods 里的搜索过滤 lambda：中文界面下搜英文原名也能命中。
     * require = 0：其它 NeoForge 小版本的 lambda 编号可能不同，挂不上时只会少掉"双名搜索"，不会让游戏启动失败。 */
    @WrapOperation(method = "lambda$reloadMods$0", require = 0,
            at = @At(value = "INVOKE", target = "Lnet/neoforged/neoforgespi/language/IModInfo;getDisplayName()Ljava/lang/String;"))
    private String origoldsIa$searchBothNames(IModInfo info, Operation<String> original) {
        return OGEModListNames.searchName(info, original.call(info));
    }
}
