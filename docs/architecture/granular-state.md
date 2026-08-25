# 颗粒与沉积状态契约

## Registry 与功能门

颗粒门默认关闭并依赖材料门；沉淀还依赖化学门。world granular registry snapshot 保存 stable material/carrier/reaction ID、exact extent/SVQ batch、化学依赖和 fingerprint。未知、损坏或 identity mismatch 保留并 LOCKED。

区域载荷中的 granular 与 chemistry presence 必须与启动时冻结的门一致；不从其他布局构造空颗粒层。

## 独立真值

- solid volume 使用整数 SVQ；每种材料质量独立保存。
- reactive solid composition/species 与机械材料身份分开保存。
- active parcel、deposit group 和 coherent condensed phase 是不同载体，不互相替代。
- deposit 保存 cell、材料、SVQ、质量、组成、porosity 和孔隙水相关状态。
- external source、escaped sink、boundary impulse 与反应审计独立闭合。

显示高度、颜色、active sample、bulk fraction 和 porosity tooltip 都是只读派生，不能结算库存。

## 生产模型

生产模型是低成本 active parcel + deposit cell：

- active parcel 接受重力、浮力、阻力、压力和 cut-cell 碰撞；
- solid occupancy 与 liquid marker occupancy 共享统一容量、排水和压力耦合；
- 沉积使用材料安息角、邻域支撑与确定性转移；
- deposit porosity 决定孔隙水空间，但不会隐式创建或删除液体；
- 平静库存可休眠，局部碰撞、液流、边界变化或反应只唤醒受影响邻域。

不预建 MPM 或通用刚体/弹性框架。只有量化证明当前模型越过既定成本或质量停止门，才单独评审替代模型。

## 固液相转移

- precipitation/dissolution 由 registry 的 exact reaction extent 规划，同时验证 liquid species、solid composition、质量和体积容量。
- commit 必须联合更新 chemistry、solid inventory、source/sink、热能和相变 volume/work；任一候选失败则整批不落地。
- 连续凝固相由 coherent store 保存；熔化后恢复到液体 marker。它不借用 active/deposit 表示。
- pending phase volume/work 由下一次 pressure projection exactly-once 消费；未消费时拒绝 `empty` 和不安全 checkpoint 操作。

## 容器与清空

- 干颗粒容器一次只保存一个可完整表示的 SVQ 库存，包含逐材料质量和可选固相组成。
- 玩家和发射器只作用于 controller 内实际目标格，使用与区域 checkpoint 联合的容器 WAL。
- `empty` 先联合预检液体、化学、coherent、active 和 deposit sink candidate，再一次提交；气体库存不属于凝聚相清空。
