package cn.origolds.ia;

/**
 * “会缓存 OGE 的机器”方块实体共同实现的接口：挖掘保留电量、
 * 创造模式破坏掉落与放置回填电量都通过它读取内部存储，
 * 便于把该机制模块化地推广到未来新增的储能机器。
 */
public interface IOGEEnergyCacheHolder {

    /** 机器的内部 OGE 存储（不为 null）。 */
    IGoldSourceEnergyStorage getOGECachedStorage();
}
