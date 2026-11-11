package cn.origolds.ia;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type;
import net.minecraft.resources.Identifier;

/**
 * S2C：锯片台界面打开期间同步储经验读数 {@code storedExperience}
 * （由 {@link OGESawbladeMountMenu#broadcastChanges()} 在数值变化时推送）。
 * 客户端处理器写入菜单缓存并刷新界面读数。
 */
public record OGESawbladeMountSyncPayload(int storedExperience) implements CustomPacketPayload {

    public static final Type<OGESawbladeMountSyncPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(OriGoldsIndustrializedAge.MOD_ID, "oge_sawblade_mount_sync"));
    public static final StreamCodec<RegistryFriendlyByteBuf, OGESawbladeMountSyncPayload> STREAM_CODEC =
            new StreamCodec<RegistryFriendlyByteBuf, OGESawbladeMountSyncPayload>() {
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
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
