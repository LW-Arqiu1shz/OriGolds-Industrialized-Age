package cn.origolds.ia;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.block.Block;

/**
 * 带统一悬浮提示的方块物品：浅蓝实用主内容 +（可选）按住 Ctrl 才展开的深灰详情
 * +（可选）浅灰斜体废话行。当前机型没有的段落传 null 即可。
 *
 * <p>这里的“资源发生器”只是它最初的用途，实际上它是本模组通用的方块物品壳：
 * 发生器、粉碎机、筛洗机、抽屉、引力匣、鼓风机、附魔机、坷垃机、放置器都用它，
 * 只是各自挂不同的提示翻译键。</p>
 *
 * <p>若物品上带有 {@code origolds_ia:void_drawer_contents} 组件（虚空抽屉
 * 装着东西被破坏掉落，或创造中键取色），额外显示一行"储存内容"，
 * 像潜影盒那样一眼看出里面装了什么、装了多少。</p>
 */
public final class OGEResourceGeneratorBlockItem extends BlockItem {

    private final String usefulKey;
    private final String ctrlDetailsKey;
    private final String flavorKey;

    public OGEResourceGeneratorBlockItem(Block block, Item.Properties properties, String usefulKey, String flavorKey) {
        this(block, properties, usefulKey, null, flavorKey);
    }

    public OGEResourceGeneratorBlockItem(Block block, Item.Properties properties,
                                         String usefulKey, String ctrlDetailsKey, String flavorKey) {
        super(block, properties);
        this.usefulKey = usefulKey;
        this.ctrlDetailsKey = ctrlDetailsKey;
        this.flavorKey = flavorKey;
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        OGETooltips.add(tooltip, flag, usefulKey, ctrlDetailsKey, flavorKey);
        OGEVoidDrawerContents contents = stack.get(ModDataComponents.VOID_DRAWER_CONTENTS.get());
        if (contents != null && !contents.item().isEmpty() && contents.count() > 0) {
            tooltip.add(Component.translatable(
                            "tooltip.origolds_ia.drawer.stored",
                            contents.item().getHoverName(),
                            contents.count())
                    .withStyle(ChatFormatting.YELLOW));
        }
    }
}
