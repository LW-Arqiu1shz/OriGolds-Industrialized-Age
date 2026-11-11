package cn.origolds.ia.mixin.client;

import cn.origolds.ia.OGEModListNames;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.neoforged.neoforgespi.language.IModInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** 模组列表按名称排序时也用翻译名，排序位置与显示的名字一致。 */
@Mixin(targets = "net.neoforged.neoforge.client.gui.ModListScreen$SortType")
public abstract class ModListSortMixin {
    @WrapOperation(method = "compare(Lnet/neoforged/fml/ModContainer;Lnet/neoforged/fml/ModContainer;)I",
            at = @At(value = "INVOKE", target = "Lnet/neoforged/neoforgespi/language/IModInfo;getDisplayName()Ljava/lang/String;"))
    private String origoldsIa$translatedName(IModInfo info, Operation<String> original) {
        return OGEModListNames.displayName(info, original.call(info));
    }
}
