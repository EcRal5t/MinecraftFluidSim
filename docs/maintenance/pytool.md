# Python 开发工具

在仓库根目录使用 uv 环境运行。分析性能的代码入口见 [性能索引](../architecture/system-overview.md#performance)，Java 静态检查见 [验证指南](validation.md#architecture)。

- **[arch_baseline.py](../../pytool/arch_baseline.py)**
  - 用途：统计当前生产 Java 文件和行数，列出超过 600/1000 行的类
  - 运行方式：`uv run pytool/arch_baseline.py`

- **[unused_imports.py](../../pytool/unused_imports.py)**
  - 用途：用正则检查指定文件中可能未使用的 import
  - 运行方式：`uv run pytool/unused_imports.py <file.java> [...]`

- **[jfr_hotspots.py](../../pytool/jfr_hotspots.py)**
  - 用途：按完整 JDK 事件名读取至多 64 层采样栈，汇总自身耗时、调用链和代表性调用栈
  - 运行方式：`uv run pytool/jfr_hotspots.py <recording.jfr> [--top N] [--module mmm]`

- **[compare_step_profiles.py](../../pytool/compare_step_profiles.py)**
  - 用途：合并完整步 CSV 的独立重复测量，对比耗时、分配量和逐步粒子/子步/PCG 记录
  - 运行方式：`uv run pytool/compare_step_profiles.py --before "build/hotspot-optimization/demo-before*.csv" --after "build/hotspot-optimization/demo-identity[12].csv" --check-trajectory`

- **[test_jfr_hotspots.py](../../pytool/test_jfr_hotspots.py)**
  - 用途：校验采样归属与调用方向
  - 运行方式：`uv run python -m unittest pytool.test_jfr_hotspots`

- **[water_melting_curve.py](../../pytool/water_melting_curve.py)**
  - 用途：按 IAPWS 熔化压力公式更新内置水冰相图，并核对公式表列值
  - 运行方式：`uv run pytool/water_melting_curve.py`

`arch_baseline.py` 的输出反映运行时的源码。架构重整完成时的数字保存在 [历史基线](../archive/maintenance/architecture-baseline.md)。`unused_imports.py` 的结果供人工核对，Java 编译仍负责检查类型和依赖。

示例中历史 `build/hotspot-optimization/` 数据的本地归档与恢复位置见 [构建输出管理](build-outputs.md#本地历史资料)。

`compare_step_profiles.py` 支持历史完整步程序及 `CompositeStepBenchmark` 的 CSV，分别列出每个独立 JVM 的均值。组合场景的 `--check-trajectory` 还比较调用次数、反应/沉积/接触计数、固体体积和精确内能等观测列；它不证明完整状态逐位一致。

- **[gas_caloric_materials.py](../../pytool/gas_caloric_materials.py)**
  - 用途：从 NIST Shomate 积分和 JANAF 气态水焓表生成 N₂、O₂、H₂O、CO₂ 的 100–1700 K 区间平均 Cv；统一采用模型摩尔质量 0.028/0.032/0.018/0.044 kg/mol。
  - 运行方式：`uv run pytool/gas_caloric_materials.py`
  - 导热率采用 NIST Huber 300 K 常数近似；CO₂ 在 298 K 以下沿用 298 K 的 Cp。同一生成过程按模型摩尔质量与相图参考点推导水蒸气批次体积，参考能保持常压潜焓 2256700 J/kg，CO₂ 参考能及原物种偏移保持。
