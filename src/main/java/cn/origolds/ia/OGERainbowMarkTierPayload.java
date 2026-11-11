package cn.origolds.ia;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端→服务器：切换手上虹彩印记的档位（5x / 20x / 100x）。
 *
 * <p>按键是 Ctrl，它不是原版会随交互包一起上传的修饰键，所以判定只能在客户端做：
 * 客户端“手持虹彩印记、准星对着空气”按住 Ctrl 左键 / 右键时发这个包，服务端
 * {@link OGERainbowMarkItem#adjustTier} 再校验主手物品与耐久。</p>
 *
 * <ul>
 *   <li>{@code delta = -1}：Ctrl+左键，降一档；</li>
 *   <li>{@code delta = +1}：Ctrl+右键，升一档。</li>
 * </ul>
 *
 * <p>报文不携带物品或坐标：服务端只认玩家自己的主手物品，客户端无法借这个包
 * 去改别人的物品。</p>
 *
 * @param delta 档位变化量，只会是 -1 / +1
 */
public record OGERainbowMarkTierPayload(int delta) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<OGERainbowMarkTierPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
                    OriGoldsIndustrializedAge.MOD_ID, "oge_rainbow_mark_tier"));

    public static final StreamCodec<RegistryFriendlyByteBuf, OGERainbowMarkTierPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public OGERainbowMarkTierPayload decode(RegistryFriendlyByteBuf buf) {
                    return new OGERainbowMarkTierPayload(buf.readVarInt());
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, OGERainbowMarkTierPayload payload) {
                    buf.writeVarInt(payload.delta());
                }
            };

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
