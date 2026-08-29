# pytool 登记表

可复用 Python 工具脚本；使用 uv python 环境运行。

| 脚本 | 用途 | 运行 |
|---|---|---|
| `arch_baseline.py` | 扫描生产 Java 源，输出超过 600/1000 行的类表与模块规模，用于更新 [`architecture-baseline.md`](architecture-baseline.md) | `uv run pytool/arch_baseline.py` |
