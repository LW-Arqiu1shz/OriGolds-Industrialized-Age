package cn.origolds.ia;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端→服务器：金源虚空抽屉 / 金源引力匣 / 金源超级漏斗的“绑定过滤物品”操作。
 *
 * <p>绑定键是 Alt+中键，而 Alt 不是原版交互包会携带的修饰键，所以判定只能在客户端做
 * （见 {@link OGEItemBindClient}）；服务端收到本包后按坐标重新校验方块种类、距离，
 * 再读取玩家主手决定是“绑定主手物品”还是“空手清空绑定”
 * （见 {@link OGEItemBind}）。</p>
 *
 * @param pos 目标方块坐标（{@link net.minecraft.core.BlockPos#asLong()} 打包）
 */
public record OGEItemBindPayload(long pos) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<OGEItemBindPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
                    OriGoldsIndustrializedAge.MOD_ID, "oge_item_bind"));

    public static final StreamCodec<RegistryFriendlyByteBuf, OGEItemBindPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public OGEItemBindPayload decode(RegistryFriendlyByteBuf buf) {
                    return new OGEItemBindPayload(buf.readLong());
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, OGEItemBindPayload payload) {
                    buf.writeLong(payload.pos());
                }
            };

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
