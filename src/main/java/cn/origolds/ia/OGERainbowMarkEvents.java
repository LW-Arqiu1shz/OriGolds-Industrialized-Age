package cn.origolds.ia;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.TriState;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent.ItemCraftedEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.LeftClickBlock;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickBlock;

/**
 * 彩虹标记的上/清标记交互：
 * <ul>
 *   <li>Shift+右键（手持彩虹标记对可标记机器）→ 消耗 1 点标记耐久，把当前档位
 *       与剩余耐久写进机器（{@link IRainbowMarked#applyRainbowMark}）；</li>
 *   <li>Shift+左键 → 清掉标记并返还那颗投入的魔金之核；</li>
 *   <li>合成回调 → 处理充能配方里“核按堆吃、零头留在格里”的收尾（防白嫖）。</li>
 * </ul>
 */
@EventBusSubscriber(modid = OriGoldsIndustrializedAge.MOD_ID)
public final class OGERainbowMarkEvents {

    private OGERainbowMarkEvents() {
    }

    /** Shift+右键：上彩虹标记（已有点亮 / 耐久用尽时给出行动栏提示）。 */
    @SubscribeEvent
    public static void onRightClickBlock(RightClickBlock event) {
        Player player = event.getEntity();
        ItemStack stack = event.getItemStack();
        if (!player.isShiftKeyDown() || !(stack.getItem() instanceof OGERainbowMarkItem)) {
            return;
        }
        Level level = event.getLevel();
        BlockPos pos = event.getPos();
        BlockState state = level.getBlockState(pos);
        if (!OGERainbowMarks.isAttachable(state)) {
            return;
        }
        if (!(level.getBlockEntity(pos) instanceof IRainbowMarked marked)) {
            return;
        }
        // 吃掉原版“对机器按键”路径，标记操作不走机器界面
        event.setUseBlock(TriState.FALSE);
        event.setUseItem(TriState.FALSE);
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        if (level.isClientSide()) {
            return;
        }
        if (marked.getRainbowTier() >= 0) {
            player.sendOverlayMessage(Component.translatable("message.origolds_ia.rainbow_mark_already"));
        } else if (!OGERainbowMarkItem.isUsable(stack)) {
            player.sendOverlayMessage(Component.translatable("message.origolds_ia.rainbow_mark_broken"));
        } else {
            int tier = OGERainbowMarkItem.getTier(stack);
            int remaining = Math.max(0, OGERainbowMarkItem.getDurability(stack) - 1);
            marked.applyRainbowMark(tier, remaining);
            stack.setDamageValue(stack.getDamageValue() + 1);
            player.inventoryMenu.broadcastChanges();
            player.sendOverlayMessage(
                    Component.translatable("message.origolds_ia.rainbow_mark_applied", OGERainbowMarks.label(tier)));
        }
    }

    /** Shift+左键：清掉机器的彩虹标记，返还投入的魔金之核。 */
    @SubscribeEvent
    public static void onLeftClickBlock(LeftClickBlock event) {
        Player player = event.getEntity();
        ItemStack stack = event.getItemStack();
        if (!player.isShiftKeyDown() || !(stack.getItem() instanceof OGERainbowMarkItem)) {
            return;
        }
        Level level = event.getLevel();
        BlockPos pos = event.getPos();
        BlockState state = level.getBlockState(pos);
        if (!OGERainbowMarks.isAttachable(state)) {
            return;
        }
        if (!(level.getBlockEntity(pos) instanceof IRainbowMarked marked) || marked.getRainbowTier() < 0) {
            return;
        }
        event.setUseBlock(TriState.FALSE);
        event.setUseItem(TriState.FALSE);
        event.setCanceled(true);
        if (!level.isClientSide()) {
            int tier = marked.getRainbowTier();
            marked.clearRainbowMark();
            Item core = OGEMagicCoreRef.get();
            if (core != null) {
                Block.popResource(level, pos, new ItemStack(core));
            }
            player.sendOverlayMessage(
                    Component.translatable("message.origolds_ia.rainbow_mark_removed", OGERainbowMarks.label(tier)));
        }
    }

    /**
     * 彩虹标记充成后收尾：若网格里的核不够“每堆至少留 1 个零头”的口径
     * （堆数 &lt; 应吃核数），把留在格里的核整堆收缩到应保留总数，
     * 保证玩家不会多拿返还。
     */
    @SubscribeEvent
    public static void onCrafted(ItemCraftedEvent event) {
        if (event.getEntity().level().isClientSide()
                || !(event.getCrafting().getItem() instanceof OGERainbowMarkItem)) {
            return;
        }
        Container grid = event.getInventory();
        if (grid == null) {
            return;
        }
        List<ItemStack> items = new ArrayList<>(grid.getContainerSize());
        for (int i = 0; i < grid.getContainerSize(); i++) {
            items.add(grid.getItem(i));
        }
        if (!RainbowMarkRechargeRecipe.isRechargeGrid(items)) {
            return;
        }
        int consume = RainbowMarkRechargeRecipe.coresToConsume(items);
        int stacks = RainbowMarkRechargeRecipe.coreStacks(items);
        if (stacks >= consume) {
            return;
        }
        int extra = Math.max(0, RainbowMarkRechargeRecipe.coreCount(items) - consume);
        for (int i = 0; i < grid.getContainerSize(); i++) {
            ItemStack stack = grid.getItem(i);
            if (!stack.isEmpty() && OGEMagicCoreRef.isCore(stack)) {
                int count = 1 + Math.min(extra, 63);
                extra -= count - 1;
                stack.setCount(count);
            }
        }
        grid.setChanged();
    }
}
