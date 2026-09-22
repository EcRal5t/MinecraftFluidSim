# Python 开发工具

在仓库根目录使用 uv 环境运行。分析性能的代码入口见 [性能路由](routing.md#performance)，Java 静态检查见 [验证指南](validation.md#architecture)。

| 脚本 | 用途 | 运行方式 |
|---|---|---|
| [arch_baseline.py](../../pytool/arch_baseline.py) | 统计当前生产 Java 文件和行数，列出超过 600/1000 行的类 | `uv run pytool/arch_baseline.py` |
| [unused_imports.py](../../pytool/unused_imports.py) | 用正则检查指定文件中可能未使用的 import | `uv run pytool/unused_imports.py <file.java> [...]` |
| [jfr_hotspots.py](../../pytool/jfr_hotspots.py) | 汇总 JFR 执行采样，查看方法自身耗时、调用链采样和代表性调用栈 | `uv run pytool/jfr_hotspots.py <recording.jfr> [--top N] [--module cc.ecisr.mmm]` |
| [compare_step_profiles.py](../../pytool/compare_step_profiles.py) | 合并完整步 CSV 的独立重复测量，对比耗时、分配量和逐步粒子/子步/PCG 记录 | `uv run pytool/compare_step_profiles.py --before "build/hotspot-optimization/demo-before*.csv" --after "build/hotspot-optimization/demo-identity[12].csv" --check-trajectory` |
| [test_jfr_hotspots.py](../../pytool/test_jfr_hotspots.py) | 校验采样归属与调用方向 | `uv run python -m unittest pytool.test_jfr_hotspots` |
| [water_melting_curve.py](../../pytool/water_melting_curve.py) | 按 IAPWS 熔化压力公式更新内置水冰相图，并核对公式表列值 | `uv run pytool/water_melting_curve.py` |

`arch_baseline.py` 的输出反映运行时的源码。架构重整完成时的数字保存在 [历史基线](../archive/maintenance/architecture-baseline.md)。`unused_imports.py` 的结果供人工核对，Java 编译仍负责检查类型和依赖。
