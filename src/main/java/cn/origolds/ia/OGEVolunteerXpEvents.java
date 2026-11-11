package cn.origolds.ia;

import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.entity.XpOrbTargetingEvent;
import net.neoforged.neoforge.event.entity.player.PlayerXpEvent;

/**
 * 挂机志愿者的隐藏代理玩家不许碰经验球。
 *
 * <p>玩家模式下，志愿者会在原地放一个进入原版玩家距离系统的
 * {@link OGEVolunteerSpawnPlayer 隐藏代理}，好让区块加载、自然生成沿用原版规则。
 * 副作用是经验球也把它当成一名站在那里的玩家：{@code ExperienceOrb} 每刻扫描
 * {@code level.getNearestPlayer(orb, 8)} 时会把代理选为“跟随目标”，周围的经验球
 * 于是被一路吸到志愿者脚下吃掉——真人玩家反而捡不到。</p>
 *
 * <p>两个口子都堵住：</p>
 * <ul>
 *   <li>{@link XpOrbTargetingEvent} 是 NeoForge 给经验球挑跟随目标留的钩子。代理被选中时
 *   改指半径内最近的一位真人玩家（没有就干脆不吸附，经验球照原版落地静态等玩家）。</li>
 *   <li>{@link PlayerXpEvent.PickupXp} 兜底：经验球恰好滚到代理身上时直接取消拾取。</li>
 * </ul>
 *
 * <p>志愿者该拿的经验另有来路：绑定刃槽击杀的经验在
 * {@link OGESawbladeMountEvents#onLivingDeath} 里掐掉落地并记进刃槽账本，
 * 与这里的“不吸地上的经验球”互不冲突。</p>
 */
@EventBusSubscriber(modid = OriGoldsIndustrializedAge.MOD_ID, bus = EventBusSubscriber.Bus.GAME)
public final class OGEVolunteerXpEvents {

    private OGEVolunteerXpEvents() {
    }

    /** 经验球挑跟随目标时：跳过隐藏代理，改指附近的真人玩家。 */
    @SubscribeEvent
    public static void onXpOrbTargeting(XpOrbTargetingEvent event) {
        Player target = event.getFollowingPlayer();
        if (!OGEVolunteerSpawnPlayer.isProxy(target)) {
            return;
        }
        event.setFollowingPlayer(event.getXpOrb().level().getNearestPlayer(
                event.getXpOrb().getX(), event.getXpOrb().getY(), event.getXpOrb().getZ(),
                event.getScanDistance(),
                e -> !OGEVolunteerSpawnPlayer.isProxy(e) && !(e instanceof Player player && player.isSpectator())));
    }

    /** 兜底：经验球碰到隐藏代理时不许被拾取。 */
    @SubscribeEvent
    public static void onPickupXp(PlayerXpEvent.PickupXp event) {
        if (OGEVolunteerSpawnPlayer.isProxy(event.getEntity())) {
            event.setCanceled(true);
        }
    }
}
