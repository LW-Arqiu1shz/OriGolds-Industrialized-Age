package cn.origolds.ia;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type;
import net.minecraft.resources.Identifier;

/**
 * 服务端 → 维度内全体玩家：某个锯片台的绑定名被改了（任何一个志愿者改名都会广播）。
 *
 * <p>名字自 1.0.1 起集中存在锯片台方块实体上、绑同一台的所有志愿者共享；本载荷让所有
 * 开着绑定管理界面的客户端原位刷新对应行，无需重新打开界面。</p>
 */
public record OGEVolunteerBindNamePayload(long pos, String name) implements CustomPacketPayload {

    public static final Type<OGEVolunteerBindNamePayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath("origolds_ia", "oge_volunteer_bind_name"));

    public static final StreamCodec<RegistryFriendlyByteBuf, OGEVolunteerBindNamePayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_LONG, OGEVolunteerBindNamePayload::pos,
                    ByteBufCodecs.stringUtf8(128), OGEVolunteerBindNamePayload::name,
                    OGEVolunteerBindNamePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
