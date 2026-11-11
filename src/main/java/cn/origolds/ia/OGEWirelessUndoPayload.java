package cn.origolds.ia;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端→服务器：撤销 / 重做最近一次装置操作。
 * <p>{@code undo=true} 为撤销（Ctrl+Z），{@code undo=false} 为重做（Ctrl+Y）。</p>
 * <p>{@code scope} 指定作用于哪一类历史：
 * 0=金源能量线路（仅能量显示模式开启时）、1=无线物流管道（仅物流显示模式开启时）、
 * 2=两个模式都开启时——撤销/重做最近一次操作，不分类别。</p>
 */
public record OGEWirelessUndoPayload(boolean undo, int scope) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<OGEWirelessUndoPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(OriGoldsIndustrializedAge.MOD_ID, "oge_wireless_undo"));

    public static final StreamCodec<RegistryFriendlyByteBuf, OGEWirelessUndoPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public OGEWirelessUndoPayload decode(RegistryFriendlyByteBuf buf) {
                    return new OGEWirelessUndoPayload(buf.readBoolean(), buf.readVarInt());
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, OGEWirelessUndoPayload payload) {
                    buf.writeBoolean(payload.undo());
                    buf.writeVarInt(payload.scope());
                }
            };

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
