package cn.origolds.ia;

import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.entity.XpOrbTargetingEvent;
import net.neoforged.neoforge.event.entity.player.PlayerXpEvent;

/**
 * 志愿者观察员“玩家模式”的隐藏假玩家代理与经验球的交互。
 *
 * <ul>
 *   <li>经验球选目标时跳过代理，改追最近的真实玩家（与 1.21.1 版行为一致）。
 *       26.x 的 {@code XpOrbTargetingEvent} 搬到了 {@code net.neoforged.neoforge.entity} 包，
 *       早先移植时没找到它，导致刷怪塔的经验球全被吸到志愿者身边堆着、玩家拿不到。</li>
 *   <li>代理本身永远不能真正拾取经验。</li>
 * </ul>
 */
@EventBusSubscriber(modid = OriGoldsIndustrializedAge.MOD_ID)
public final class OGEVolunteerXpEvents {

    private OGEVolunteerXpEvents() {
    }

    @SubscribeEvent
    public static void onXpOrbTargeting(XpOrbTargetingEvent event) {
        if (!(event.getFollowingPlayer() instanceof OGEVolunteerSpawnPlayer)) {
            return;
        }
        ExperienceOrb orb = event.getXpOrb();
        event.setFollowingPlayer(orb.level().getNearestPlayer(orb.getX(), orb.getY(), orb.getZ(),
                event.getScanDistance(),
                e -> !(e instanceof OGEVolunteerSpawnPlayer) && !(e instanceof Player player && player.isSpectator())));
    }

    @SubscribeEvent
    public static void onPickupXp(PlayerXpEvent.PickupXp event) {
        if (event.getEntity() instanceof OGEVolunteerSpawnPlayer) {
            event.setCanceled(true);
        }
    }
}
