package cn.origolds.ia;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * 金源能量无线网络与无线物流管道的事件：每服务器刻执行能量与物品传输，
 * 加入/登出时同步连线、管道与选点状态。
 */
@EventBusSubscriber(modid = OriGoldsIndustrializedAge.MOD_ID)
public final class OGEWirelessEvents {

    private OGEWirelessEvents() {
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        OGEWirelessManager.tick(event.getServer());
        OGELogisticsManager.tick(event.getServer());
        // 撤销/重做结果延迟一 tick 播报（覆盖父模组同文案的失败提示）
        OGEWirelessHistory.drainMessages(event.getServer());
    }

    @SubscribeEvent
    public static void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        Player player = event.getEntity();
        if (player instanceof ServerPlayer serverPlayer) {
            OGEWirelessManager.syncTo(serverPlayer);
            OGEWirelessManager.syncSelection(serverPlayer);
            OGELogisticsManager.syncTo(serverPlayer);
            OGELogisticsManager.syncSelection(serverPlayer);
        }
    }

    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        OGEWirelessManager.clearPending(event.getEntity().getUUID());
        OGELogisticsManager.clearPending(event.getEntity().getUUID());
        OGEWirelessHistory.clearPlayer(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        OGEWirelessManager.clearPendingAll();
        OGELogisticsManager.clearPendingAll();
        OGEWirelessHistory.clearAll();
    }
}
