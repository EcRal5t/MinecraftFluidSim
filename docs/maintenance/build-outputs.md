# 构建输出管理

## 保存位置

| 目录 | 内容 | 清理方式 |
|---|---|---|
| 根目录 `build/` | 临时实验、基准 CSV、JFR、诊断输出与根项目构建产物 | 实验结束后保留需要的证据，再删除该次输出 |
| 各模块 `build/` | Gradle 编译类、资源、测试工作目录、报告及 `libs/` 中的 jar | 按需运行对应模块的 `clean`，下次构建重新生成 |
| `.gradle/` | 当前仓库的 Gradle 与 Loom 缓存 | 排查缓存故障时清理，随后重新构建 |
| `artifacts/research/` | 需要长期保存的本地实验归档与校验清单 | 核对文档引用及备份后按需删除 |
| `run/`、`fabric/run/` | 开发客户端或服务器的运行数据，可能包含世界存档 | 按具体世界和运行配置管理 |

`build/` 中的临时 Java、patch、数值快照和研究报告可能只在本地存在。删除前检查其是否已进入 Git 或完整归档。长期维护的测试与工具分别放入对应模块的 `src/test/` 与 `pytool/`。

根项目使用 Gradle `base` 插件，根 `clean` 清理根 `build/`。执行不限定模块的 `clean` 还会选择子项目任务；清理单个模块使用 `:模块名:clean`。主模组与 GPU probe 的部署产物分别位于 `fabric/build/libs/` 和 `gpu-probe/build/libs/`。

新实验按主题使用一个独立输出目录；每轮运行重复使用该目录或在结束后清理。需要保存的原始记录、复现源码和结论压缩至 `artifacts/research/`，附校验清单，并在相应资料索引中登记。JFR 事件 JSON 可按需导出，长期保存原始 JFR。

## 本地历史资料

根 `build/` 的历史资料完整保存在：

- `artifacts/research/build-evidence-20261013.zip`：以原根 `build/` 的相对路径保存全部文件，包含旧归档、Git bundle、独立源码、patch、基准记录和诊断快照。
- `artifacts/research/build-evidence-20261013.files.csv`：各文件相对路径、原始字节数与 SHA-256。
- `artifacts/research/build-evidence-20261013.zip.sha256`：压缩包 SHA-256。

查找旧文档中的 `build/<路径>` 时，在 ZIP 中查找 `<路径>`。复现时将所需目录解压到独立检出的 `build/`，保持原有相对目录关系；具体入口见 [自由面探索索引](../archive/free-surface-unification-research.md)。

`artifacts/` 受 Git 忽略，迁移机器时一并备份。生产源码与世界存档按各自位置保存。
