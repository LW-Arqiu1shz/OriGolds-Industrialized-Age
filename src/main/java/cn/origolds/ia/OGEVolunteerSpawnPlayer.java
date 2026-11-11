package cn.origolds.ia;

import com.mojang.authlib.GameProfile;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.common.util.FakePlayer;

/**
 * 玩家模式挂机志愿者在服务端使用的不可见代理玩家。
 *
 * <p>把代理正式加入 {@link ServerLevel} 的玩家/区块距离系统后，原版会像对待站在该处的
 * 玩家一样加载区块并计算自然生成；创造模式保证敌对生物不会把代理本身当作目标。
 * 代理不向任何真实客户端广播，玩家只会看到对应的挂机志愿者实体。</p>
 */
final class OGEVolunteerSpawnPlayer extends FakePlayer {

    OGEVolunteerSpawnPlayer(ServerLevel level, GameProfile profile) {
        super(level, profile);
        setGameMode(GameType.CREATIVE);
        setInvisible(true);
        setSilent(true);
        setInvulnerable(true);
    }

    /** 代理采用服务器视距，不受 FakePlayer 默认客户端视距 2 的限制。 */
    @Override
    public int requestedViewDistance() {
        return server.getPlayerList().getViewDistance();
    }

    @Override
    public boolean broadcastToPlayer(ServerPlayer player) {
        return false;
    }

    /**
     * 是否是本模组的隐藏代理。
     *
     * <p>代理虽然进了原版玩家距离系统，但它不是真人：经验球不能把它当成吸附目标，
     * 也不能让它吃掉经验。判断写在这里，{@link OGEVolunteerXpEvents} 只认这一个口径。</p>
     */
    static boolean isProxy(Entity entity) {
        return entity instanceof OGEVolunteerSpawnPlayer;
    }
}
