package cn.origolds.ia;

/**
 * 金源能量（OGE）存储接口。
 *
 * <p>这是一种独立于 Forge 能量（FE）的自定义能量类型。发生装置通过
 * {@link #getOGEProduceRate()} 暴露每秒产率，储能装置（如电池）通过
 * {@link #getOGECapacity()} 暴露最大容量。</p>
 */
public interface IGoldSourceEnergyStorage {

    /** 向内部注入 OGE，返回实际接受量。 */
    int insertOGE(int amount, boolean simulate);

    /** 从内部提取 OGE，返回实际提取量。 */
    int extractOGE(int amount, boolean simulate);

    /** 当前已储存的 OGE。 */
    int getOGEStored();

    /** 最大可储存的 OGE。 */
    int getOGECapacity();

    /** 每秒产生的 OGE；0 表示纯储能装置（如电池）。 */
    int getOGEProduceRate();
}
