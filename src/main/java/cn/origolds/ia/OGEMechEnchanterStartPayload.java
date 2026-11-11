package cn.origolds.ia;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端→服务器：按下金源自动附魔机界面里的箭头按钮，确认开始本次合并。
 *
 * <p>报文不带任何参数：服务端只认玩家当前打开的 {@link OGEMechEnchanterMenu}，
 * 因此客户端无法借这个包去操作任意坐标的方块。</p>
 */
public record OGEMechEnchanterStartPayload() implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<OGEMechEnchanterStartPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
                    OriGoldsIndustrializedAge.MOD_ID, "oge_mech_enchanter_start"));

    public static final StreamCodec<RegistryFriendlyByteBuf, OGEMechEnchanterStartPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public OGEMechEnchanterStartPayload decode(RegistryFriendlyByteBuf buf) {
                    return new OGEMechEnchanterStartPayload();
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, OGEMechEnchanterStartPayload payload) {
                    // 无字段
                }
            };

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
