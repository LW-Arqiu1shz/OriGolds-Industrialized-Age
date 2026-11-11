package cn.origolds.ia;

/**
 * 金源能量（OGE）的默认存储实现。
 *
 * <p>记录已存能量与容量，并按游戏刻累计产率（每秒 20 刻），以尽量精确地
 * 实现“每秒 X 单位 OGE”的要求。</p>
 */
public final class GoldSourceEnergyStorage implements IGoldSourceEnergyStorage {

    private int stored;
    private double productionAccumulator;
    private final int capacity;
    private final int producePerSecond;

    public GoldSourceEnergyStorage(int capacity, int producePerSecond) {
        this.capacity = Math.max(0, capacity);
        this.producePerSecond = Math.max(0, producePerSecond);
    }

    @Override
    public int insertOGE(int amount, boolean simulate) {
        int accepted = Math.min(Math.max(0, amount), capacity - stored);
        if (!simulate && accepted > 0) {
            stored += accepted;
        }
        return accepted;
    }

    @Override
    public int extractOGE(int amount, boolean simulate) {
        int extracted = Math.min(Math.max(0, amount), stored);
        if (!simulate && extracted > 0) {
            stored -= extracted;
        }
        return extracted;
    }

    @Override
    public int getOGEStored() {
        return stored;
    }

    @Override
    public int getOGECapacity() {
        return capacity;
    }

    @Override
    public int getOGEProduceRate() {
        return producePerSecond;
    }

    /**
     * 按一次游戏刻累计产量（每秒 20 刻）。返回本刻实际入账的整数 OGE。
     */
    public int tickProduction() {
        return tickProduction(producePerSecond);
    }

    /**
     * 以指定每秒产率（如彩虹标记放大后的有效产率）按一次游戏刻累计产量，
     * 返回本刻实际入账的整数 OGE。
     */
    public int tickProduction(int rate) {
        if (rate <= 0 || stored >= capacity) {
            return 0;
        }
        productionAccumulator += rate / 20.0D;
        int whole = (int) productionAccumulator;
        if (whole <= 0) {
            return 0;
        }
        productionAccumulator -= whole;
        int accepted = insertOGE(whole, false);
        if (accepted < whole) {
            productionAccumulator = 0.0D;
        }
        return accepted;
    }

    /** 直接设定已存能量（用于从存档读取）。 */
    public void setStored(int amount) {
        this.stored = Math.max(0, Math.min(amount, capacity));
    }

    /** 读取累计的小数产率（用于存档）。 */
    public double getProductionAccumulator() {
        return productionAccumulator;
    }

    /** 设定累计的小数产率（用于从存档读取）。 */
    public void setProductionAccumulator(double value) {
        this.productionAccumulator = Math.max(0.0D, value);
    }
}
