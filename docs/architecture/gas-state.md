# 气体状态契约

## 模型边界

- 开放空间连接无限环境节点；密闭空间保存有限气体库存。
- controller 的已验证闭壳 footprint 可声明 sealed domain boundary。命令临时域保持环境开放。
- `AirCavityScanner` 只在当前网格预算内 flood fill，不读取未加载世界；部分充液格的剩余空间参与 runtime available volume。
- 节点 pressure、density、temperature、孔流速度和边是派生运行态，不是独立库存。

## 权威真值

- 每个密闭节点以 canonical minimum-cell key 和完整 cell membership 标识。
- 每个气体组分保存稳定 component ID、标准体积 nanos、独立质量、可选 species/残差。
- source、sink、质量 roundoff 和 residual roundoff 是独立审计列。
- ambient 节点不持有有限库存；新密闭腔从环境建立时明确记 external source。

## 拓扑变化

拓扑重扫以重扫前后节点的实际 cell lineage 规划 merge、split、vent 和新腔初始化：

- merge 汇总完整库存和能量；
- split 按几何份额分配，精确余数只落入一个确定节点；
- 打开或消失的腔体把实际库存记入环境 sink；
- 新腔体按环境组成和当前可用体积记 source；
- 相变产生或消耗的 water vapor 通过同一组件库存与 source/sink 审计提交。

部分充液界面的短时拓扑抖动可使用 runtime lineage 减少虚假空气周转，但不能改变持久 membership 或物质账本。

## 压力与热耦合

- 无热状态时使用等温状态方程和有限速率孔流。
- 热状态存在时，气体总内能与节点库存共同派生温度、绝对压力和密度；孔流同步搬运组分与能量。
- 气液相变用 water-vapor 分压计算饱和条件，受绝对压力、潜热、库存、容量和批预算共同限制。
- 所有 candidate 温度和压力必须落在 world thermal registry 的有效域；越界安全暂停。

## 持久化

气体只作为完整热区域 generation 的 required payload 发布。恢复时从已提交 carrier geometry 重新扫描拓扑，并要求节点数、minimum-cell key、逐格 membership、库存和能量 ordinal 全部一致；任何差异都锁定，不猜测 remap。
