package cn.origolds.ia;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * 客户端→服务器：给金源自动合成机的某个合成格锁定/清除物品。
 *
 * <p>报文不携带坐标：服务端只认玩家当前打开的 {@link OGEMechCrafterMenu}，
 * 客户端无法借这个包去操作任意坐标的方块。{@code stack} 为空表示右键清除锁定；
 * 非空表示拿鼠标上的物品左键锁定该格（只取 1 件的种类，锁上后鼠标上的物品不动）。</p>
 *
 * @param cell  合成格下标（0~8，按合成表从左到右、从上到下）
 * @param stack 要锁定的物品；空堆表示清除
 */
public record OGEMechCrafterLockPayload(int cell, ItemStack stack) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<OGEMechCrafterLockPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
                    OriGoldsIndustrializedAge.MOD_ID, "oge_mech_crafter_lock"));

    public static final StreamCodec<RegistryFriendlyByteBuf, OGEMechCrafterLockPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public OGEMechCrafterLockPayload decode(RegistryFriendlyByteBuf buf) {
                    int cell = buf.readVarInt();
                    ItemStack stack = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
                    return new OGEMechCrafterLockPayload(cell, stack);
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, OGEMechCrafterLockPayload payload) {
                    buf.writeVarInt(payload.cell());
                    ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, payload.stack());
                }
            };

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
