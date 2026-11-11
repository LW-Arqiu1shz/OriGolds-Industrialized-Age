package cn.origolds.ia;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

/**
 * 金源能量机器方块物品：在悬浮提示中展示其 OGE 产率或储能容量，便于辨识能量关系。
 *
 * <p>并承载“挖掘保留电量”的放回逻辑：挖掘带电机器得到的物品带有
 * {@code origolds_ia:oge_stored} 组件（每个单位各存这么多，同值自然可堆叠），
 * 放置时把这份电量注回新方块；一叠里放下一个，其余单位仍各自带着电量。</p>
 */
public final class OGEMachineBlockItem extends BlockItem {

    private final int capacity;
    /** 悬浮提示翻译键的机型后缀（wind / water / fire / magic / capacitor）。 */
    private final String tooltipKey;
    /** 储能提示专用金色 #FFD700（比原版 GOLD 更亮，用于 ⚡️ 储能行）。 */
    private static final Style OGE_STORED_STYLE = Style.EMPTY.withColor(TextColor.fromRgb(0xFFD700));

    public OGEMachineBlockItem(Block block, Item.Properties properties, int capacity, String tooltipKey) {
        super(block, properties);
        this.capacity = capacity;
        this.tooltipKey = tooltipKey;
    }

    /**
     * 放置成功后（服务器端）：若这一叠物品带电量，把等量 OGE 注入刚放下的方块。
     * 生存与创造放置都走这里（创造不减数量，以点选位置的方块是否为我们为准）。
     */
    @Override
    public InteractionResult place(BlockPlaceContext context) {
        Level level = context.getLevel();
        ItemStack held = context.getItemInHand();
        Integer stored = held.get(ModDataComponents.OGE_STORED.get());
        InteractionResult result = super.place(context);
        if (stored == null || stored <= 0 || level.isClientSide) {
            return result;
        }
        BlockPos pos = context.getClickedPos();
        if (level.getBlockState(pos).is(getBlock())
                && level.getBlockEntity(pos) instanceof OGEMachineBlockEntity machine) {
            machine.applyPlacedCharge(Math.min(stored, capacity));
        }
        return result;
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        // 实用提示（浅蓝正体）+ 废话提示（浅灰斜体），与父模组样式一致
        OGETooltips.addUsefulAndFlavor(tooltip,
                "tooltip.origolds_ia.useful." + tooltipKey,
                "tooltip.origolds_ia.bs." + tooltipKey);
        // 带电的机器物品：额外显示单个单位的存电量（挖掘保留的结果），金色 #FFD700。
        Integer stored = stack.get(ModDataComponents.OGE_STORED.get());
        if (stored != null && stored > 0) {
            tooltip.add(Component.translatable("tooltip.origolds_ia.oge_stored", stored)
                    .withStyle(OGE_STORED_STYLE));
        }
    }
}
