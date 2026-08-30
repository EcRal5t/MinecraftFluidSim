# pytool 登记表

可复用 Python 工具脚本；使用 uv python 环境运行。

| 脚本 | 用途 | 运行 |
|---|---|---|
| `arch_baseline.py` | 扫描生产 Java 源，输出超过 600/1000 行的类表与模块规模，用于更新 [`architecture-baseline.md`](architecture-baseline.md) | `uv run pytool/arch_baseline.py` |
| `unused_imports.py` | 报告结构搬移后残留的未使用 import；不依赖编译器，只做正则匹配 | `uv run pytool/unused_imports.py <file.java> [...]` |
