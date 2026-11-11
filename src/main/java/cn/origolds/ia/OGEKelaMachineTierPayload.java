package cn.origolds.ia;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端→服务器：调整金源坷垃机的工作档位（3×3×3 / 5×5×5 / 7×7×7 / 9×9×9）。
 *
 * <p>按键是 Ctrl，它不是原版会随交互包一起上传的修饰键，所以判定只能在客户端做：
 * 客户端按住 Ctrl 左键/右键指向坷垃机时发这个包，服务端
 * {@link OGEKelaMachineBlock#adjustTier} 再校验一次并改档。</p>
 *
 * <ul>
 *   <li>{@code delta = -1}：Ctrl+左键，档位 -1（降档）；</li>
 *   <li>{@code delta = +1}：Ctrl+右键，档位 +1（升档）。</li>
 * </ul>
 *
 * @param pos   坷垃机的方块坐标（{@link net.minecraft.core.BlockPos#asLong()} 打包）
 * @param delta 档位变化量，只会是 -1 / +1
 */
public record OGEKelaMachineTierPayload(long pos, int delta) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<OGEKelaMachineTierPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
                    OriGoldsIndustrializedAge.MOD_ID, "oge_kela_machine_tier"));

    public static final StreamCodec<RegistryFriendlyByteBuf, OGEKelaMachineTierPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public OGEKelaMachineTierPayload decode(RegistryFriendlyByteBuf buf) {
                    return new OGEKelaMachineTierPayload(buf.readLong(), buf.readVarInt());
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, OGEKelaMachineTierPayload payload) {
                    buf.writeLong(payload.pos());
                    buf.writeVarInt(payload.delta());
                }
            };

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
