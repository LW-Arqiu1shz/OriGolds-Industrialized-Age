package cn.origolds.ia;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.common.util.FakePlayer;

/**
 * 志愿者观察员的“玩家模式”投影：一个隐形、静默、无敌的假玩家，
 * 只负责在世界上呈现出监工的身形，不接收任何真实玩家数据包以外的东西。
 */
final class OGEVolunteerSpawnPlayer extends FakePlayer {

    OGEVolunteerSpawnPlayer(ServerLevel level, GameProfile profile) {
        super(level, profile);
        this.setGameMode(GameType.CREATIVE);
        this.setInvisible(true);
        this.setSilent(true);
        this.setInvulnerable(true);
    }

    /** 与主世界玩家列表一致的视距（假玩家不需要独立视距）。 */
    @Override
    public int requestedViewDistance() {
        return this.level().getServer().getPlayerList().getViewDistance();
    }

    /** 不向任何真实玩家广播自己。 */
    @Override
    public boolean broadcastToPlayer(ServerPlayer player) {
        return false;
    }
}
