package cn.origolds.ia;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
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
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * 虹彩印记的贴 / 揭交互与工作台配方的收尾。
 *
 * <p>贴：手持虹彩印记 <b>Shift+右键</b>一台可贴的机器（三种资源发生器、火力发电机、
 * 魔力发电机），印记扣 1 点耐久后留在手上，机器按印记档位超频，六个面出现 25% 透明度的
 * 虹彩叠加层。</p>
 *
 * <p>揭：手持虹彩印记 <b>Shift+左键</b>已贴印记的机器，印记被扣掉并掉落 1 个魔金之核，
 * 机器恢复原样（方块不会被挖掉）。</p>
 *
 * <p>两种情况都在事件里吃掉这次交互：右键要拦在机器打开自己的 UI 之前，左键要拦在
 * 原版开始破坏方块之前，所以两边都设 {@code FALSE} 并取消事件。</p>
 *
 * <p>另外这里补一刀工作台：{@link RainbowMarkRechargeRecipe 充能配方}一次要按“补满耐久
 * 需要多少核”扣材料，可原版每个格子只会扣 1 个，于是核堆不够多时在合成完成事件里先把
 * 核的堆叠数压到合适大小，多扣的部分则由配方的退料补回（见
 * {@link RainbowMarkRechargeRecipe#getRemainingItems}）。</p>
 */
@EventBusSubscriber(modid = OriGoldsIndustrializedAge.MOD_ID, bus = EventBusSubscriber.Bus.GAME)
public final class OGERainbowMarkEvents {

    private OGERainbowMarkEvents() {
    }

    /** Shift+右键：把印记贴到机器上。 */
    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
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

        // 吃掉这次交互：别让机器把自己的 UI 打开，也别顺手放下手持物品。
        event.setUseBlock(TriState.FALSE);
        event.setUseItem(TriState.FALSE);
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        if (level.isClientSide) {
            return;
        }
        if (marked.getRainbowTier() >= 0) {
            player.displayClientMessage(
                    Component.translatable("message.origolds_ia.rainbow_mark_already"), true);
            return;
        }
        if (!OGERainbowMarkItem.isUsable(stack)) {
            player.displayClientMessage(
                    Component.translatable("message.origolds_ia.rainbow_mark_broken"), true);
            return;
        }
        int tier = OGERainbowMarkItem.getTier(stack);
        // 贴一次只扣 1 点耐久，印记本身留在手上：耐久 0 后不能再贴，但不会损毁。
        int remaining = Math.max(0, OGERainbowMarkItem.getDurability(stack) - 1);
        marked.applyRainbowMark(tier, remaining);
        stack.setDamageValue(stack.getDamageValue() + 1);
        player.inventoryMenu.broadcastChanges();
        player.displayClientMessage(Component.translatable(
                "message.origolds_ia.rainbow_mark_applied", OGERainbowMarks.label(tier)), true);
    }

    /** Shift+左键：把已贴的印记扣下来，换成 1 个魔金之核。 */
    @SubscribeEvent
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
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

        // 吃掉这次交互：别让原版开始挖方块。
        event.setUseBlock(TriState.FALSE);
        event.setUseItem(TriState.FALSE);
        event.setCanceled(true);
        if (level.isClientSide) {
            return;
        }
        int tier = marked.getRainbowTier();
        marked.clearRainbowMark();
        Item core = OGEMagicCoreRef.get();
        if (core != null) {
            Block.popResource(level, pos, new ItemStack(core));
        }
        player.displayClientMessage(Component.translatable(
                "message.origolds_ia.rainbow_mark_removed", OGERainbowMarks.label(tier)), true);
    }

    /**
     * 工作台合成充能，按“补满耐久真正需要多少核”来扣材料。
     *
     * <p>原版 {@code ResultSlot#onTake} 的顺序是：先触发本事件、再查一次配方退料
     * （{@link RainbowMarkRechargeRecipe#getRemainingItems}）、最后给每个非空格子各扣 1 个。
     * 这里只处理“核堆数不够扣”的那一半：盘里核堆少、要消耗的核多时，直接把核的堆叠数
     * 压到 {@code 需求 + 堆数}，让原版每堆扣 1 之后正好剩够。核堆够多时本事件什么都不做，
     * 多扣的部分交给配方退料补回。</p>
     *
     * <p><b>为什么核堆够多时绝不能把盘面清空：</b>退料配方是按“事件之后的盘面”重新查出来的。
     * 若把核全清掉，盘面就只剩一枚印记，反而匹配上
     * {@link RainbowMarkRechargeRecipe 回收配方}，回收配方会把印记当退料放回来——凭空多一枚
     * 印记。所以只要退料需要补核，就保留原来的核堆，让盘面始终匹配充能配方（退料为空，
     * 印记正常被吃掉）。</p>
     */
    @SubscribeEvent
    public static void onCrafted(PlayerEvent.ItemCraftedEvent event) {
        // 客户端也会走一遍结果槽逻辑，扣核只在服务端做，避免两边各扣一次造成错位。
        if (event.getEntity().level().isClientSide()) {
            return;
        }
        if (!(event.getCrafting().getItem() instanceof OGERainbowMarkItem)) {
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
        // 核堆够多：多扣的部分由配方退料补齐（见 getRemainingItems），这里不动盘面。
        if (stacks >= consume) {
            return;
        }
        // 核堆不够：主动把核调少。每堆都保留至少 1 个，盘面仍匹配充能配方、退料算出来是 0；
        // 盘里先摆 keep + stacks 个，原版每堆扣 1，扣完就是 keep 个。
        int keep = RainbowMarkRechargeRecipe.coreCount(items) - consume;
        int extra = keep; // 每堆先记 1 个基础量再分摊，等价于总数 keep + stacks
        for (int i = 0; i < grid.getContainerSize(); i++) {
            ItemStack stack = grid.getItem(i);
            if (stack.isEmpty() || !OGEMagicCoreRef.isCore(stack)) {
                continue;
            }
            int count = 1 + Math.min(extra, 63);
            extra -= count - 1;
            stack.setCount(count);
        }
        grid.setChanged();
    }
}
