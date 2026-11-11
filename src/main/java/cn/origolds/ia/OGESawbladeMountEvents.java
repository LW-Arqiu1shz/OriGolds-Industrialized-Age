package cn.origolds.ia;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * 金源圆锯刃槽的装载与拆除交互。
 *
 * <h2>装：手持刃片 Shift+右键刃槽的面</h2>
 * <p>在那个面外侧那一格生成一片 {@link OGESawbladeEntity 刀刃实体}，并扣掉刃片 1 点耐久
 * （刃片不消失，耐久见底就装不上，回工作台用铁块补）。刀刃是<b>实体</b>不是方块，
 * 所以那一格原来是空气、是别的方块都无所谓，只要玩家点得到刃槽的那个面就能装；
 * 同一个格子因此可以同时堆好几片刀刃。顶面与底面的刀刃在安装那一下
 * 按“离玩家最近的四向之一”定朝向，正面朝玩家；四个侧面的刀刃恒平行于地面。</p>
 *
 * <p><b>为什么写在事件里而不是方块的 {@code useItemOn}</b>：潜行且手上拿着东西时，
 * {@code ServerPlayerGameMode#useItemOn} 会直接跳过方块的 {@code useItemOn}
 * （原版“潜行放方块不打扰方块本身”的那套判定），所以只能在
 * {@link PlayerInteractEvent.RightClickBlock} 里接管这次交互。写法与
 * {@link OGERainbowMarkEvents#onRightClickBlock} 一致：两边都把交互吃掉，
 * 真正的改动只在服务端做。</p>
 *
 * <h2>拆：手持刃片 Shift+左键刃槽的面</h2>
 * <p>刀刃变成实体之后既打不中也点不着（{@link OGESawbladeEntity#isPickable()} 为
 * {@code false}），所以拆除改成点它附着的那个刃槽面：Shift+左键刃槽的某个面，
 * 就把挂在这个面上的刀刃实体全部拆掉，每片掉 1 个铁块。拆除刃槽本身也会把
 * 挂在它上面（不分面）的刀刃一并清掉，见 {@link OGESawbladeMountBlock#onRemove}。</p>
 */
@EventBusSubscriber(modid = OriGoldsIndustrializedAge.MOD_ID, bus = EventBusSubscriber.Bus.GAME)
public final class OGESawbladeMountEvents {

    private OGESawbladeMountEvents() {
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        Player player = event.getEntity();
        ItemStack stack = event.getItemStack();
        if (!player.isShiftKeyDown() || !(stack.getItem() instanceof OGECircularSawbladeItem)) {
            return;
        }
        Level level = event.getLevel();
        BlockPos pos = event.getPos();
        BlockState mount = level.getBlockState(pos);
        if (!(mount.getBlock() instanceof OGESawbladeMountBlock)) {
            return;
        }
        Direction face = event.getHitVec().getDirection();

        // 吃掉这次交互：别打开刃槽自身的用途，也别把手上的刃片放下去。
        event.setUseBlock(TriState.FALSE);
        event.setUseItem(TriState.FALSE);
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        if (level.isClientSide) {
            return;
        }
        if (!OGECircularSawbladeItem.isUsable(stack)) {
            player.displayClientMessage(
                    Component.translatable("message.origolds_ia.sawblade_worn"), true);
            return;
        }

        // 刀刃是实体：位置钉在这格的脚底中心，朝向与挂载关系一起写进实体。
        // 目标格是什么方块都不管，也不检查那格是不是空的——同格可以叠好几片。
        BlockPos bladePos = pos.relative(face);
        Direction bladeFacing = face.getAxis() == Direction.Axis.Y
                ? player.getDirection().getOpposite()
                : Direction.NORTH;
        OGESawbladeEntity blade = ModEntities.SAWBLADE.get().create(level);
        if (blade == null) {
            return;
        }
        blade.setPos(bladePos.getX() + 0.5D, bladePos.getY(), bladePos.getZ() + 0.5D);
        blade.setMount(pos, face, bladeFacing);
        blade.setOldPosAndRot();
        level.addFreshEntity(blade);
        OGECircularSawbladeItem.consumeOne(stack, player);

        SoundType soundType = mount.getSoundType();
        level.playSound(null, bladePos, soundType.getPlaceSound(), SoundSource.BLOCKS,
                (soundType.getVolume() + 1.0F) / 2.0F, soundType.getPitch() * 0.8F);
        player.displayClientMessage(
                Component.translatable("message.origolds_ia.sawblade_installed",
                        OGECircularSawbladeItem.getDurability(stack)), true);
    }

    /**
     * Shift+左键（手持圆锯刃片）：拆掉被点到的那个刃槽面上的刀刃实体。
     *
     * <p>写法同 {@link OGERainbowMarkEvents#onLeftClickBlock}：两边都取消事件，
     * 不让原版开始破坏方块；掉落只在服务端做一次，不会掉双份。</p>
     *
     * <p>只在破坏动作<b>开始</b>（{@code START}）那一次生效：松手或中断时原版还会补发
     * {@code STOP}/{@code ABORT}，同一个事件会二次到达，若不挡掉就会在拆完之后
     * 再报一次“这个面上没有装圆锯刀刃”。</p>
     */
    @SubscribeEvent
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (event.getAction() != PlayerInteractEvent.LeftClickBlock.Action.START) {
            return;
        }
        Player player = event.getEntity();
        if (!player.isShiftKeyDown() || !(event.getItemStack().getItem() instanceof OGECircularSawbladeItem)) {
            return;
        }
        Level level = event.getLevel();
        BlockPos pos = event.getPos();
        Direction face = event.getFace();
        if (face == null || !(level.getBlockState(pos).getBlock() instanceof OGESawbladeMountBlock)) {
            return;
        }
        event.setUseBlock(TriState.FALSE);
        event.setUseItem(TriState.FALSE);
        event.setCanceled(true);
        if (level.isClientSide || !(level instanceof ServerLevel serverLevel)) {
            return;
        }

        List<OGESawbladeEntity> blades = OGESawbladeEntity.findMountedOn(serverLevel, pos, face);
        if (blades.isEmpty()) {
            player.displayClientMessage(
                    Component.translatable("message.origolds_ia.sawblade_none"), true);
            return;
        }
        for (OGESawbladeEntity blade : blades) {
            blade.dropAndDiscard(serverLevel);
        }
        player.displayClientMessage(
                Component.translatable("message.origolds_ia.sawblade_removed", blades.size()), true);
    }

    /**
     * 被<b>绑定到挂机志愿者的</b>刃槽上的刀刃击杀的生物（玩家也算）：
     * 经验不落地，直接存进刃槽。
     *
     * <p>{@code LivingDeathEvent} 在 {@code die()} 开头触发，早于掉落物与经验结算，
     * 所以先 {@link LivingEntity#skipDropExperience()} 掐掉原版经验球，
     * 再把这份经验交给刃槽记账。</p>
     *
     * <p>没绑定志愿者的刀刃击杀<b>不做任何拦截</b>：伤害来源是刀刃实体而非玩家，
     * 按原版规则等同摔死、烧死这类非玩家伤害——死者 100 刻内没被玩家
     * （或召唤物、驯服生物）打过的话，经验照原版直接不掉。</p>
     */
    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event) {
        if (!(event.getSource().getDirectEntity() instanceof OGESawbladeEntity blade)) {
            return;
        }
        if (!(blade.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        if (!(blade.mountBlockEntity() instanceof OGESawbladeMountBlockEntity mount)) {
            return;
        }
        if (mount.getBoundVolunteer() == null) {
            return;
        }
        LivingEntity victim = event.getEntity();
        victim.skipDropExperience();
        mount.collectKillExperience(serverLevel, victim, blade);
    }
}
