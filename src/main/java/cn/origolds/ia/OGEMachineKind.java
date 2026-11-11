package cn.origolds.ia;

/**
 * 金源能量机器的类型。
 *
 * <p>每种机器有不同的“是否在工作”条件：
 * <ul>
 *   <li>WIND：风驱，任何时候都在工作；</li>
 *   <li>WATER：水驱，六个面中至少有一个面接触水才工作；</li>
 *   <li>FIRE：火驱，需要放入燃料（普通熔炉燃料）才会燃烧产电；</li>
 *   <li>MAGIC：魔驱，需要放入“魔金之核”才会产电；</li>
 *   <li>CAPACITOR：金源能储电池，不产电，只储存能量。</li>
 * </ul>
 */
public enum OGEMachineKind {

    WIND,
    WATER,
    FIRE,
    MAGIC,
    CAPACITOR;

    /** 是否需要燃料/魔金之核来工作（FIRE/MAGIC 有物品槽与 UI）。 */
    public boolean isFueled() {
        return this == FIRE || this == MAGIC;
    }
}
