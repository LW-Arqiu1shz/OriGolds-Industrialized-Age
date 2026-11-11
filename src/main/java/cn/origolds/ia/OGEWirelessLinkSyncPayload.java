package cn.origolds.ia;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 服务器→客户端：同步所有金源能量无线连线，用于客户端绘制金色的连线与高亮。
 */
public record OGEWirelessLinkSyncPayload(List<LinkView> links) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<OGEWirelessLinkSyncPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(OriGoldsIndustrializedAge.MOD_ID, "oge_wireless_link_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, OGEWirelessLinkSyncPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public OGEWirelessLinkSyncPayload decode(RegistryFriendlyByteBuf buf) {
                    int size = buf.readVarInt();
                    List<LinkView> links = new ArrayList<>(size);
                    for (int i = 0; i < size; ++i) {
                        UUID id = buf.readUUID();
                        ResourceKey<Level> dimA = OGEWirelessData.dimKey(buf.readIdentifier());
                        long posA = buf.readLong();
                        ResourceKey<Level> dimB = OGEWirelessData.dimKey(buf.readIdentifier());
                        long posB = buf.readLong();
                        links.add(new LinkView(id, dimA, posA, dimB, posB));
                    }
                    return new OGEWirelessLinkSyncPayload(links);
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, OGEWirelessLinkSyncPayload payload) {
                    buf.writeVarInt(payload.links().size());
                    for (LinkView link : payload.links()) {
                        buf.writeUUID(link.id());
                        buf.writeIdentifier(link.dimA().identifier());
                        buf.writeLong(link.posA());
                        buf.writeIdentifier(link.dimB().identifier());
                        buf.writeLong(link.posB());
                    }
                }
            };

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public record LinkView(UUID id, ResourceKey<Level> dimA, long posA,
                           ResourceKey<Level> dimB, long posB) {
    }
}
