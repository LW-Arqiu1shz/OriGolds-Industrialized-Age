package cn.origolds.ia;

/**
 * “可以被贴上虹彩印记的机器方块实体”共同实现的接口。
 *
 * <p>档位与“贴上时印记还剩多少耐久”都存在方块实体里：档位要同步到客户端给叠加层
 * 选贴图（见 {@link OGERainbowOverlayRenderer}），耐久只在服务端用来在方块被挖掉时
 * 把印记原样还给玩家。</p>
 */
public interface IRainbowMarked {

    /** 虹彩印记档位：{@code -1} 表示没贴，{@code 0/1/2} 是 5x / 20x / 100x。 */
    int getRainbowTier();

    /** 贴上去时印记剩余的耐久；没贴时是 0。 */
    int getRainbowDurability();

    /** 服务端贴上印记（档位 + 耐久），并立刻同步客户端。 */
    void applyRainbowMark(int tier, int durability);

    /** 服务端揭下印记，并立刻同步客户端。 */
    void clearRainbowMark();
}
