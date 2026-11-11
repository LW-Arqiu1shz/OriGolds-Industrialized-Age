package cn.origolds.ia;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;

/**
 * 金源虚空抽屉的存放内容（数据组件 {@code origolds_ia:void_drawer_contents}）。
 *
 * <p>抽屉只存一种物品，所以组件只需记录"物品种类（数量恒写 1）+ 总数量"两项，
 * 挖掘掉落与创造取色时写入、放置时还原。原版 {@code minecraft:container} 组件
 * 的编解码上限为 256 个槽位，装不下 729 组的抽屉，故不使用。</p>
 */
public record OGEVoidDrawerContents(ItemStack item, int count) {

    public static final Codec<OGEVoidDrawerContents> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            ItemStack.OPTIONAL_CODEC.fieldOf("item").forGetter(OGEVoidDrawerContents::item),
            Codec.INT.fieldOf("count").forGetter(OGEVoidDrawerContents::count)
    ).apply(instance, OGEVoidDrawerContents::new));

    public static final StreamCodec<RegistryFriendlyByteBuf, OGEVoidDrawerContents> STREAM_CODEC =
            StreamCodec.composite(
                    ItemStack.OPTIONAL_STREAM_CODEC, OGEVoidDrawerContents::item,
                    ByteBufCodecs.VAR_INT, OGEVoidDrawerContents::count,
                    OGEVoidDrawerContents::new);

    public OGEVoidDrawerContents {
        item = item.isEmpty() ? ItemStack.EMPTY : item.copyWithCount(1);
    }

    /** 抽屉为空（无种类或数量 ≤0）时返回 null，调用方据此省略组件。 */
    public static OGEVoidDrawerContents of(ItemStack prototype, int total) {
        if (prototype.isEmpty() || total <= 0) {
            return null;
        }
        return new OGEVoidDrawerContents(prototype.copyWithCount(1), total);
    }
}
