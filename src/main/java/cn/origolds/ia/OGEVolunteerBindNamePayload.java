package cn.origolds.ia;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 服务器→客户端：某个金源圆锯刃槽在绑定管理界面里的显示名变了（{@code pos} 处，
 * {@code name} 为新名，空串表示回到默认名）。
 *
 * <p>刃槽的显示名唯一存在刃槽方块实体上，所有绑定了它的志愿者共用。有人改名后服务端朝
 * 同一维度广播这一份，已经开着别的志愿者配置界面的客户端就地刷新对应那一行；
 * 没开界面的客户端直接忽略，不会因此弹出界面。</p>
 */
public record OGEVolunteerBindNamePayload(long pos, String name) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<OGEVolunteerBindNamePayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
                    OriGoldsIndustrializedAge.MOD_ID, "oge_volunteer_bind_name"));

    public static final StreamCodec<RegistryFriendlyByteBuf, OGEVolunteerBindNamePayload> STREAM_CODEC =
            StreamCodec.composite(
                    net.minecraft.network.codec.ByteBufCodecs.VAR_LONG, OGEVolunteerBindNamePayload::pos,
                    net.minecraft.network.codec.ByteBufCodecs.stringUtf8(128), OGEVolunteerBindNamePayload::name,
                    OGEVolunteerBindNamePayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
