# Real Water 产品路线

本文件只列尚未实现且已经决定继续推进的产品工作。已生效行为见 [`../architecture/`](../README.md)，代码与验证入口见 [`../maintenance/routing.md`](../maintenance/routing.md)。

## 下一阶段：原版行为与外部 Mod API

先冻结 API 的单位、事务、线程和失败语义，再按小增量实现：

1. 只读查询：dimension + region + world/cell position → 有效性、材料、体积分数、派生温度和相态。
2. 观察适配：为 Jade/WAILA/HWYLA 提供接口；查询不得唤醒、加载或回写区域，主 Mod 不增加准星 HUD。
3. 受控转移 API：stable material/species ID、纳体积/SVQ、质量和总内能必须显式，复用现有区域/容器事务。
4. 事件 API：checkpoint、ownership、phase transition 和安全暂停只发布已提交事件，不暴露半代次。
5. 原版交互：灭火、湿润、岩浆反应、红石、生物和寻路逐项定义并验收，不以“一格水”假设直接映射连续库存。

该阶段不扩大开放世界 ownership，也不允许外部 Mod 直接修改平滑 density、pressure、runtime handle 或客户端 mesh。

## 热力学后续

### 压力相关固液平衡

当前气液相变已经使用绝对压力和 water-vapor 分压；固液相变只把绝对压力作为有效域安全门，平衡温度仍为常量。

后续可将 solid-liquid transition 扩展为 registry 数据化的 `T_eq(p)`：

- 先支持单调分段曲线或经过验证的 Clapeyron 关系；
- pressure 来自同一 logical step 的绝对压力场；
- transition fingerprint 进入 world registry identity；
- 相变规划同时闭合 latent heat、质量、SVQ、体积功和组成；
- 无曲线的定义保持常温常压模型，不引入材料特判。

这项扩展不需要重构权威内能或相变提交顺序，但必须新增物性有效域和压力曲线量化 fixture。

### 更完整的化学热力学

弱酸、缓冲液、活度、有限速率动力学、可逆平衡和气相化学暂不承诺。若进入实现，先冻结 solver 规模、exact inventory 与近似的适用范围，不能逐分子粒子化。

## 性能与后端评审

1. 建立代表性 controller profile，分别量化 solver、世界交互、网络快照和 readback。
2. 占比未知前不修改 40 ms pacing 与连续 3 步 200 ms 暂停阈值。
3. 只有 profile 证明 CPU 路径达到明确停止门后，才评审生产 GPU backend；CPU 继续作为规范和恢复后端。
4. 活动成本目标随扰动表面积/活动 section 增长；代表性静态域至少 80% 体积可守恒休眠后才扩大范围。

详细停止门见 [`performance-and-backends.md`](performance-and-backends.md)。

## 视觉与界面质量

- 为开放域和密闭域分别建立最小黑色三角面 fixture。
- 分开评估 mesh 拓扑缺陷、液液物理锐化和逐顶点法线平滑。
- 不得用 renderer 增厚、强制找平或隐藏三角形掩盖 marker、相输运或 snapshot 问题。

## 世界范围

天然洞穴、河流、海洋和开放世界接管仍在范围外。进入评审前必须满足：

- 有界 controller 的 ownership、checkpoint 和容器恢复在异常退出下稳定；
- 活动 section、气腔搜索和 chunk 生命周期有明确硬预算；
- 原版/其他 Mod 流体交互具有显式 source/sink 和冲突语义；
- 不依赖永久 chunk ticket 或无界空气 flood fill。

## 模型停止条件

- 颗粒继续使用 active parcel + deposit cell；只有量化证明它无法跨过既定质量/成本门，才评审 MPM 或其他重模型。
- 若扩大范围失败，依次缩小活动区、降低更新率、采用更稀疏的宏观状态，最后才降低空间分辨率。
- 不先牺牲守恒，也不以经验特判或渲染修补替代物理机制。
