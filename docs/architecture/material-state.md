# 材料状态契约

## 权威真值

- 每个 marker 独立保存体积、质量和稳定材料身份。
- 区域总量与逐材料体积/质量账本必须由 marker 精确和闭合。
- `FluidMaterialRegistry` 是运行时材料定义入口；持久化和容器使用稳定 ID，不保存运行时 handle。
- 当前机械定义包含 stable ID、参考密度、miscibility group、液气表面张力和接触角；液液张力由 registry 的 phase 无序对表给出。
- 显示颜色来自独立只读 palette。当前没有把每液体黏度作为 `FluidMaterial` 的权威字段；颗粒阻力使用 granular carrier 自己声明的流体参数。
- 只有 marker 质量和体积参与库存结算；参考密度用于创建、校验和状态函数绑定，不能覆盖已有 marker 质量。

## Registry 与运行时 handle

定义按声明顺序建立稠密 material handle；stable ID 必须是合法 Minecraft identifier 且唯一。miscibility group 再映射为 phase handle：同组材料不生成内部液液界面，不同组材料保持 phase 分区。

handle 只为了数组索引。checkpoint 保存 canonical material palette，容器保存 stable ID；恢复时先验证完整 palette/registry identity，再建立当前进程 handle。不能把一次进程中的整数 handle 写入长期状态。

内置基线目前提供 water、oil 和 mercury。内置 palette 是生产默认定义集，不是存档的替代品；已提交区域仍由 checkpoint 中的 palette 与 registry identity 解释。

## 求解器边界

- 不可互溶材料共享 MAC 压力/速度框架，但保留独立相身份和液液界面。
- pressure density、平滑相分数、PLIC、曲率和客户端 mesh 都是数值或展示字段，不能改写 marker 体积或质量。
- reseed split/merge 必须保持体积、质量、动量和质心；不得跨材料或 chemical phase 合并。
- 单一纯水域必须退化为同一机械求解路径，不额外引入材料修正源汇。

格内机械密度只从实际 marker 重建：

```text
rho_cell = sum(marker_solver_mass) / sum(marker_normalized_volume)

mass_kg   = marker_solver_mass * cellSize^3
volume_m3 = marker_normalized_volume * cellSize^3
```

质量和体积跨 SI 边界时都乘同一个 `cellSize³`，所以密度比值中的尺度严格约去。chemical marker 的闭合式为 `particleMass * cellSize³ = exact_species_mass_kg + massResidual_kg`；溶质增加实际质量而体积不变时，机械密度自然上升，因此盐水下沉来自同一权威质量账本，不需要盐材料特判。pressure face 的 `1/rho` 系数按界面距离加权；不得从 material reference density、客户端颜色或平滑 fraction 反推存量质量。热学 Boussinesq 项是参考态附近的温差浮力近似，与这份组成质量密度正交，不能覆盖它。

同一 miscibility group 的材料共享 phase 和速度，但当前没有分子扩散或每 marker 连续浓度场：marker 仍各自保存一种机械材料库存，局部格聚合给出混合比例。不同材料不在 reseed 中互相吞并，因此未解析扩散不会丢失组分。不可互溶材料也共享一个压力/速度框架，不承诺双速度界面动力学。

## 表面与展示

- 外表面使用总 liquid occupancy；液液界面使用逐 phase fraction，两者必须分开裁剪。
- phase pair 只生成一份共享 mesh，归属和法向确定；互溶材料不生成内部面。
- material palette、ARGB、平滑 fraction 和 marker seed 都是展示数据。
- 当前内置材料的 surface/contact 参数可以退化为零张力/中性接触角；测试或数据定义只有在进入 runtime registry 后才影响 mechanics。

## 跨层表示

- 区域 checkpoint 保存稳定材料 palette、每个 marker 的 palette index、独立质量和总账。
- `FluidMixture` 保存容器内各稳定材料的纳体积和独立 kg 质量；tooltip 与耐久条只读。
- 玩家和发射器只向实际目标区域转移，单次物品计数必须为 1。
- 容器与区域之间的物质变化通过同一 WAL 事务提交；任何不能被物品表示的结果在修改 solver 前拒绝。

`FluidMixture` 最多保存 64 个按 stable ID 排序的 component；每项包含 `volumeNanos` 和 raw kg mass bits。构造时拒绝重复 ID、非正体积/质量和超过一桶的总量。物品中单材料可以使用紧凑 component 表示，多材料使用 canonical mixture 文本；进入 WAL 时两者都规范化为完整 mixture truth，reader 不现场合成缺失 component。

抽取先以 double 精确量预检，再按整 marker 与必要尾拆分生成实际 settlement；物品必须能表示实际 volume、每材料 mass 和所有附加化学/热侧列。请求量与实际可表示量不一致时，WAL 记录实际量，不用请求量伪造闭合。

## 功能门

材料门在启动时冻结。持久区域的载荷形状必须与当前门完全一致；不一致时保留 checkpoint 并锁定，不自动增删材料状态。
