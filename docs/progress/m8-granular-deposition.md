# M8 颗粒沉淀与低成本沉积进度

## M8.0 设计门（2026-08-24）

基线为主 Mod 0.13.0、提交前工作树干净。修改前 `runCpuValidation` 的 13 个 fast 领域全部通过，runner 2.958 秒。

正式设计冻结在 [`../architecture/granular-state.md`](../architecture/granular-state.md)：选用活动 parcel + 守恒
deposit cell，不预建 DEM/MPM；固体体积使用 `2^27 SVQ/BV` 的 checked-long 独立真值，质量与固相组成另行结算；
moving-solid capacity source 与 pressure/free-surface/air 共享同一耦合几何；孔隙只影响 bulk 形态和 Darcy 阻力，
孔隙水仍由液体 marker 拥有。

隔离原型未进入发布 source set，结果为：

| 项目 | 结果 |
|---|---|
| 轻/中性/重颗粒终速 | `+0.033391 / 0 / -0.076737 m/s`，半隐式积分匹配 Schiller--Naumann root oracle |
| 两向阻力动量 | fixture 前后总动量均为 0 |
| 跨格 occupancy | 5,592,422 SVQ 分配与 moving source exact re-sum |
| skeleton/bulk | 40% porosity 只按 skeleton 扣液体容量 |
| parcel↔deposit | 10,000 往返后 1 BV 仍为 134,217,728 SVQ |
| 安息角 | 32° 一维为 32.000°；四邻域朝向误差 11.027°，合法八邻域降为 3.930° |
| primitive arrays | 默认单 region 候选约 3.34 MiB |

版本决定遵守已有冻结边界：气体持久化仍保留独立 `region-state v4` 评审，M8 颗粒完整联合存档使用
`region-state v5`；protocol v4、container-transfer WAL v4 与 region 版本互不推断。

下一增量只实现无 Minecraft 依赖的 registry、SVQ 库存与 parcel/deposit 事务，并新增现有 runner 下唯一的
`granular` selector；在该门通过前不修改 region-state、protocol、item 或生产 solver。
