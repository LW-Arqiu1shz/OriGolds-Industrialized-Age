package cn.origolds.ia;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端→服务器：配置界面里对绑定表的三类改动——
 * {@code rename}（{@code pos} 那条改名为 {@code name}，空串回默认名）、
 * {@code reorder}（按 {@code positions} 发来的顺序整表重排）与
 * {@code delete}（删掉 {@code pos} 那条绑定）。
 * 报文都带志愿者实体 id，服务端复核类型与距离，见 {@link OGEVolunteerBindManager}。
 */
public record OGEVolunteerBindEditPayload(int entityId, int action, long pos, String name,
                                          List<Long> positions) implements CustomPacketPayload {

    /** 改显示名。 */
    public static final int ACTION_RENAME = 0;
    /** 整表重排。 */
    public static final int ACTION_REORDER = 1;
    /** 删除一条绑定。 */
    public static final int ACTION_DELETE = 2;

    public static final CustomPacketPayload.Type<OGEVolunteerBindEditPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
                    OriGoldsIndustrializedAge.MOD_ID, "oge_volunteer_bind_edit"));

    public static OGEVolunteerBindEditPayload ofRename(int entityId, long pos, String name) {
        return new OGEVolunteerBindEditPayload(entityId, ACTION_RENAME, pos, name, List.of());
    }

    public static OGEVolunteerBindEditPayload ofReorder(int entityId, List<Long> positions) {
        return new OGEVolunteerBindEditPayload(entityId, ACTION_REORDER, 0L, "", positions);
    }

    public static OGEVolunteerBindEditPayload ofDelete(int entityId, long pos) {
        return new OGEVolunteerBindEditPayload(entityId, ACTION_DELETE, pos, "", List.of());
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, OGEVolunteerBindEditPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public OGEVolunteerBindEditPayload decode(RegistryFriendlyByteBuf buf) {
                    int entityId = buf.readVarInt();
                    int action = buf.readVarInt();
                    if (action == ACTION_RENAME) {
                        return ofRename(entityId, buf.readLong(), buf.readUtf(128));
                    }
                    if (action == ACTION_DELETE) {
                        return ofDelete(entityId, buf.readLong());
                    }
                    int count = buf.readVarInt();
                    List<Long> positions = new ArrayList<>(count);
                    for (int i = 0; i < count; i++) {
                        positions.add(buf.readLong());
                    }
                    return ofReorder(entityId, positions);
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, OGEVolunteerBindEditPayload payload) {
                    buf.writeVarInt(payload.entityId());
                    buf.writeVarInt(payload.action());
                    if (payload.action() == ACTION_RENAME) {
                        buf.writeLong(payload.pos());
                        buf.writeUtf(payload.name(), 128);
                    } else if (payload.action() == ACTION_DELETE) {
                        buf.writeLong(payload.pos());
                    } else {
                        buf.writeVarInt(payload.positions().size());
                        for (long pos : payload.positions()) {
                            buf.writeLong(pos);
                        }
                    }
                }
            };

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
