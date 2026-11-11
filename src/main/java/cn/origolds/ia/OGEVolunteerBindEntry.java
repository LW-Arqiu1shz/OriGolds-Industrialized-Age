package cn.origolds.ia;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * 挂机志愿者身上的一条圆锯刃槽绑定：本质<b>只有坐标</b>。
 *
 * <p>顺序就是列表顺序（配置界面里拖出来的顺序）。</p>
 *
 * <p><b>显示名不在这里</b>：名字唯一存在 {@link OGESawbladeMountBlockEntity} 那份方块实体上，
 * 下发界面时现取。这样所有绑定了同一个刃槽的志愿者天然共用同一个名字，改一次名互相同步；
 * 也保证两个"绑定了同一批刃槽、其余数据一致"的志愿者收回物品后组件完全相同，能够正常堆叠
 * （名字若各存一份，只要有一边改过名就永远堆不到一起）。</p>
 *
 * <p>唯一保留的 {@code legacyName} 字段是给旧存档搬家用的：改名搬到刃槽那边之前，
 * 名字记在条目里（实体 NBT 的 {@code Name}、数据组件的 {@code name}）。读进来后由
 * {@link OGEVolunteerWatcherEntity} 在实体读档与放置还原时写进刃槽，迁移完就丢掉。</p>
 *
 * <p>{@link #equals(Object)} / {@link #hashCode()} 只看坐标：旧条目带着的历史名字
 * 不参与身份判定，免得它影响物品堆叠。</p>
 */
public record OGEVolunteerBindEntry(BlockPos pos, String legacyName) {

    public static final Codec<OGEVolunteerBindEntry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            BlockPos.CODEC.fieldOf("pos").forGetter(OGEVolunteerBindEntry::pos),
            Codec.STRING.optionalFieldOf("name", "").forGetter(OGEVolunteerBindEntry::legacyName)
    ).apply(instance, OGEVolunteerBindEntry::new));

    /** 线上只走坐标：历史名字对客户端没有意义，迁移在服务端做完即可。 */
    public static final StreamCodec<RegistryFriendlyByteBuf, OGEVolunteerBindEntry> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_LONG, entry -> entry.pos().asLong(),
                    packedPos -> new OGEVolunteerBindEntry(BlockPos.of(packedPos)));

    public OGEVolunteerBindEntry(BlockPos pos) {
        this(pos, "");
    }

    public OGEVolunteerBindEntry {
        pos = pos.immutable();
        legacyName = legacyName == null ? "" : legacyName;
    }

    /** 还没搬到刃槽那边的旧名字。 */
    public boolean hasLegacyName() {
        return !legacyName.isBlank();
    }

    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        tag.putLong("Pos", pos.asLong());
        // 只在还没迁移成功时保留旧名（刃槽区块当时没加载），迁移完就不再写回。
        if (hasLegacyName()) {
            tag.putString("Name", legacyName);
        }
        return tag;
    }

    public static OGEVolunteerBindEntry fromTag(CompoundTag tag) {
        return new OGEVolunteerBindEntry(BlockPos.of(tag.getLong("Pos")), tag.getString("Name"));
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof OGEVolunteerBindEntry entry && entry.pos.equals(pos);
    }

    @Override
    public int hashCode() {
        return pos.hashCode();
    }
}
