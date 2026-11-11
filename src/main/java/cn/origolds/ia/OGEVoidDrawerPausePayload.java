package cn.origolds.ia;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * 客户端→服务器：同步 Shift / Ctrl / Alt 是否被按住（虚空抽屉排序循环的开关）。
 *
 * <p>由 {@link OGEVoidDrawerAutoSortSync} 在玩家按住这三个键任一时每 tick 上报。
 * 服务端只作用于发送者<b>当前打开的抽屉菜单</b>（菜单类型即校验，位置由服务端自持，
 * 客户端无法指定任意方块）；按住期间抽屉排序循环整体跳过，松开立即恢复整理。
 * 按住状态带短超时自愈——即使松开包丢失（掉线、崩溃），抽屉最多 3 秒后也会自行恢复。</p>
 */
public record OGEVoidDrawerPausePayload(boolean held) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<OGEVoidDrawerPausePayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(OriGoldsIndustrializedAge.MOD_ID, "oge_void_drawer_pause"));

    public static final StreamCodec<RegistryFriendlyByteBuf, OGEVoidDrawerPausePayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public OGEVoidDrawerPausePayload decode(RegistryFriendlyByteBuf buf) {
                    return new OGEVoidDrawerPausePayload(buf.readBoolean());
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, OGEVoidDrawerPausePayload payload) {
                    buf.writeBoolean(payload.held());
                }
            };

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
