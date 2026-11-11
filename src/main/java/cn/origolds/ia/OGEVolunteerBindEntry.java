package cn.origolds.ia;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * 志愿者观察员的一条"绑定锯片台"记录：只含锯片台坐标。
 *
 * <p>自 1.0.1 起，刃槽的自定义名不再逐志愿者保存，而是集中存在锯片台方块实体上
 * （{@link OGESawbladeMountBlockEntity#getVolunteerBindName()}）：绑同一台的所有志愿者
 * 共用一个名字，改名即时同步；记录本身因此只剩坐标，绑定了同一批锯片台、其它数据一致的
 * 志愿者物品就能堆叠。{@code legacyName} 只为兼容 1.0.1 之前"名字随记录走"的存档与
 * 物品而保留，实体载入/放置时会把它迁移到锯片台上
 * （{@code OGEVolunteerWatcherEntity#adoptLegacyBindNames()}），迁移完随即清除。</p>
 *
 * <p>{@link #equals(Object)} 与 {@link #hashCode()} 只认坐标——组件堆叠比较列表元素时
 * 落到单条记录比较，旧名字的残留差异不会拆散本应堆叠的物品。网络走
 * {@link #STREAM_CODEC}（只传坐标，压成 long），存档随实体或物品组件
 * （{@link ModDataComponents#VOLUNTEER_BINDS}）以 {@link #CODEC} 列表形式序列化。</p>
 */
public record OGEVolunteerBindEntry(BlockPos pos, String legacyName) {

    public static final Codec<OGEVolunteerBindEntry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            BlockPos.CODEC.fieldOf("pos").forGetter(OGEVolunteerBindEntry::pos),
            Codec.STRING.optionalFieldOf("name", "").forGetter(OGEVolunteerBindEntry::legacyName))
            .apply(instance, OGEVolunteerBindEntry::new));

    /** 1.21.1 实体 NBT 的字段名（Pos/Name），用于迁移旧世界中的绑定表。 */
    public static final Codec<OGEVolunteerBindEntry> LEGACY_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            BlockPos.CODEC.fieldOf("Pos").forGetter(OGEVolunteerBindEntry::pos),
            Codec.STRING.optionalFieldOf("Name", "").forGetter(OGEVolunteerBindEntry::legacyName))
            .apply(instance, OGEVolunteerBindEntry::new));

    /** 只传坐标：名字由锯片台方块实体集中提供，过渡期的 legacyName 没有同步价值。 */
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

    /** 是否还挂着旧版名字（等待迁移到锯片台上）。 */
    public boolean hasLegacyName() {
        return !this.legacyName.isBlank();
    }

    /** 相等只看坐标：这是"同绑定的志愿者可堆叠"的直接保证。 */
    @Override
    public boolean equals(Object other) {
        return other instanceof OGEVolunteerBindEntry entry && entry.pos.equals(this.pos);
    }

    @Override
    public int hashCode() {
        return this.pos.hashCode();
    }
}
