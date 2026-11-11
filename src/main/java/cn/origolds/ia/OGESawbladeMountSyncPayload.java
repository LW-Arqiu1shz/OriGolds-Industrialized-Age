package cn.origolds.ia;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 服务器→客户端：把金源圆锯刃槽里存储的经验值同步给正在看界面的玩家。
 *
 * <p>经验值可以涨到几十万，远超 {@code ContainerData} 用的 short 上限，
 * 所以这里单独走一个 int 报文，由 {@link OGESawbladeMountMenu#broadcastChanges()}
 * 在数值变化时发出。</p>
 */
public record OGESawbladeMountSyncPayload(int storedExperience) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<OGESawbladeMountSyncPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
                    OriGoldsIndustrializedAge.MOD_ID, "oge_sawblade_mount_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, OGESawbladeMountSyncPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public OGESawbladeMountSyncPayload decode(RegistryFriendlyByteBuf buf) {
                    return new OGESawbladeMountSyncPayload(buf.readVarInt());
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, OGESawbladeMountSyncPayload payload) {
                    buf.writeVarInt(payload.storedExperience());
                }
            };

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
