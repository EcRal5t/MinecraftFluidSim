# Archived proposal: M3 多液体、可变密度与连通器设计方案

> 本文是进入 M3 实施前的技术方案与执行计划，归档于 `docs/archive/proposals/`。实施阶段的系统契约以 [`../../architecture/fluid-solver.md`](../../architecture/fluid-solver.md) 为准，阶段总表以 [`../../roadmap/product-roadmap.md`](../../roadmap/product-roadmap.md) 为准，测试路由以 [`../../maintenance/routing.md`](../../maintenance/routing.md) 为准。

---

## 一、背景与当前状态

当前工程处于 `26.2` 分支，最新提交基线为 `a4eafea Restore omitted roadmap specifications`，前序里程碑 M0（世界恢复事务）、M1（GPU 能力探测与 1D 变密度系数门）、M2（多区域管理、控制器闭合扫描、`region-state v1` 原子持久化与休眠）以及 M2.5（几何自由液面、PLIC-Ghost Fluid、runtime cut-cell 与守恒重采样）已全部关闭并通过实机和自动化验收。

本阶段自主接棒进入 **M3（多液体、可变密度与连通器）**。

### 必须坚守的物理与架构红线

1. **物理真值绝对独立**：
   - Marker 的 `particleVolume` 及其精确和是液体体积的唯一真值；
   - 本阶段引入 `particleMass`（或由 `materialId` 与 `particleVolume` 严格派生），作为惯性与静水压力的质量真值；
   - 平滑 density、`volume[]`、截断界面分数 $\theta$、PLIC 重建面与渲染等值面均属数值离散或视觉呈现，**严禁从平滑密度反向结算、猜测、扣减或虚构质量与水量**。
2. **压力相与固体边界**：
   - 流体压力相仍由 `markerCount > 0` 决定；
   - 固体碰撞仅在命中轴截断法向速度与位移，切向沿墙、沿角滑移保持绝对自由；
   - 严禁恢复吸墙、墙角偏置、受支撑强制找平或其他经验规则。
3. **渲染隔离**：
   - 客户端基于只读快照中的 density + marker + solid 提取多材料外表面与液液界面；
   - Marker 是浅水可见的必要内部种子；
   - 严禁用渲染补丁掩盖 marker 结构退化，渲染不得反写物理状态。
4. **事务与持久化隔离（暂不接入世界与存档）**：
   - M3 初期**严格限定在 CPU 独立 3D 实验门**内推进；
   - 在多材料数据模型、迁移方案与兼容性评审通过前，**严禁修改 `region-state v1` 存储格式、`FluidProtocol` 网络 payload 以及原版世界方块交互**；
   - 所有现有控制器、takeover 事务与持久化保持 M2.5 纯水基线不变。
5. **最小充分验证与 KISS**：
   - 不把历史测试累积为每次必跑清单；
   - 每一个小增量默认只运行 `docs/maintenance/routing.md` 中最贴近改动的一项定向门；
   - 优先扩展现有小测试，不为同一不变量重复建立冗余套件。

---

## 二、M3 阶段分解与技术实施路线

```text
                        ┌─────────────────────────────────────────────────────────┐
                        │                   M3 核心推进路线                       │
                        └────────────────────────────┬────────────────────────────┘
                                                     │
              ┌──────────────────────────────────────┴──────────────────────────────────────┐
              ▼                                                                             ▼
 ┌─────────────────────────────┐                                             ┌─────────────────────────────┐
 │  Phase 1: 最小材料与质量账本│                                             │ Phase 2: 独立 3D 连通器退化门│
 │ (Material & Mass Ledger)    │                                             │ (Degeneracy Gate & U-Tube)  │
 ├─────────────────────────────┤                                             ├─────────────────────────────┤
 │ 1. 冻结最小 Material 定义   │                                             │ 1. 构造 3D U-Tube/变截面拓扑│
 │ 2. Marker 携带 materialId   │                                             │ 2. 1:1 同密度两材料注入     │
 │ 3. 质量+体积双账本闭合      │                                             │ 3. 严格退化校验 (误差 <1e-8) │
 │ 4. 纯水严格退化保证         │                                             │ 4. 无假界面流动与体积守恒   │
 └────────────┬────────────────┘                                             └──────────────┬──────────────┘
              │                                                                             │
              └──────────────────────────────────────┬──────────────────────────────────────┘
                                                     │
                                                     ▼
                                ┌─────────────────────────────────────────┐
                                │   Phase 3: 3D 界面距离加权变密度算子    │
                                │ (3D Variable-Density Poisson Solver)    │
                                ├─────────────────────────────────────────┤
                                │ 1. 3D MAC 界面距离保守 1/rho 面系数     │
                                │ 2. 融合 Cut-Cell 开口与 PLIC Ghost-Fluid│
                                │ 3. 覆盖 1.25:1 (水-油) 与 13.6:1 (水-汞)│
                                │ 4. 消除界面寄生流动，验证静水解析解     │
                                └────────────────────┬────────────────────┘
                                                     │
                                                     ▼
                                ┌─────────────────────────────────────────┐
                                │   Phase 4: 多相动力学、分层与守恒重采样 │
                                │ (Multi-Material Reseeding & Dynamics)   │
                                ├─────────────────────────────────────────┤
                                │ 1. 质量加权 P2G 面速度与混合物求解      │
                                │ 2. 同相 Merge / 分裂 Split 质量动量守恒 │
                                │ 3. 重力反转分层稳定性 (Rayleigh-Taylor) │
                                │ 4. 互溶连续混合与不可互溶相分离         │
                                └────────────────────┬────────────────────┘
                                                     │
                                                     ▼
                                ┌─────────────────────────────────────────┐
                                │   Phase 5: 活动核心接入与架构安全评审   │
                                │ (`FlipFluidDomain` Integration & RFC)   │
                                ├─────────────────────────────────────────┤
                                │ 1. 活动核心平滑支持单相/多相模式        │
                                │ 2. 维护路由与构建脚本定向 task 登记     │
                                │ 3. 撰写网络协议与 region-state 迁移 RFC │
                                └─────────────────────────────────────────┘
```

---

## 三、各阶段详细设计与实施规范

### Phase 1: 最小材料与质量账本 (Material & Mass Ledger)

#### 1.1 数据结构设计
为避免过早引入复杂的化学和热力学开销，M3 仅定义满足变密度力学所需的最小材料元数据：

```java
public record FluidMaterial(
    byte id,
    String name,
    float density,        // 密度 (如 水: 1000.0 kg/m^3, 油: 800.0, 汞: 13600.0)
    float kinematicViscosity, // 动力粘度 (初始为 0 或微小阻尼)
    int miscibleGroup,    // 互溶组标识: 同组连续混合，异组保持界面
    int defaultColorRgb   // 客户端渲染基色
) {
    public static final FluidMaterial WATER = new FluidMaterial((byte) 0, "water", 1000.0F, 1.0E-6F, 0, 0x3F76E4);
    public static final FluidMaterial OIL   = new FluidMaterial((byte) 1, "oil",    800.0F,  1.0E-5F, 1, 0xC8A642);
    public static final FluidMaterial MERCURY = new FluidMaterial((byte) 2, "mercury", 13600.0F, 1.0E-7F, 2, 0xD0D5DD);
}
```

#### 1.2 Marker 结构扩充
Marker 保持紧凑扁平数组布局：
- 坐标：`particleX`, `particleY`, `particleZ` (float)
- 速度：`particleVelocityX`, `particleVelocityY`, `particleVelocityZ` (float)
- 体积真值：`particleVolume` (float，标准满格 1/8 = 0.125 BV)
- 材料标识：`particleMaterial` (byte[])
- 单粒子质量：$m_i = V_i \cdot \rho(\text{material}_i)$

#### 1.3 双守恒账本
- **体积账本**：$\sum_{i} V_i = V_{\text{total}}$（几何与空间填充）；
- **质量账本**：$\sum_{i} m_i = \sum_{i} (V_i \cdot \rho_i) = M_{\text{total}}$（惯性与重力）；
- **退化不变量**：当所有 marker 均为 `WATER` ($\rho=1000.0$) 时，$M_{\text{total}} \equiv 1000.0 \times V_{\text{total}}$，数值行为与 M2.5 纯水完全同构。

---

### Phase 2: 独立 3D 连通器与 1:1 同密度退化门 (Degeneracy Gate)

#### 2.1 目标与拓扑
不直接修改 Minecraft 世界或主求解器，首先在 `src/test/java/com/example/fluid/` 建立独立的 3D 连通器实验环境（如 `ConnectedVesselPressureExperiment.java` / `ConnectedVesselValidation.java`）。

连通器拓扑包含：
1. **等截面 U 形管**：左管 $2 \times 2 \times 8$、底部通道 $6 \times 2 \times 2$、右管 $2 \times 2 \times 8$；
2. **非对称截面连通器**：左管 $1 \times 1 \times 8$（细管），右箱 $4 \times 4 \times 8$（大水箱），底部相连。

#### 2.2 1:1 退化验收门
- 左侧注入材料 A ($\rho=1000$)，右侧注入材料 B ($\rho=1000$)；
- 施加标准重力 $g = 9.81$，运行求解循环；
- **验收指标**：
  - 两侧自由液面高度最终严格等高（$\Delta h \le 1.0 \times 10^{-6}$）；
  - 矩阵对称性误差 $\le 1.0 \times 10^{-15}$，PCG 收敛且残差 $\le 1.0 \times 10^{-8}$；
  - 界面处无虚假寄生速度（$\|u_{\text{interface}}\| \le 1.0 \times 10^{-8}$）；
  - 体积与质量相对损失 $\equiv 0.0$。

---

### Phase 3: 3D 界面距离加权变密度算子 (3D Variable-Density Poisson Solver)

#### 3.1 离散方程构建
在 MAC 网格上，连续形式的变密度压力方程为：
$$\nabla \cdot \left( \frac{1}{\rho(\mathbf{x})} \nabla p \right) = \frac{\nabla \cdot \mathbf{u}^*}{\Delta t}$$

在 3D 离散网格上，单元 $i = (x, y, z)$ 沿轴向（以 X 轴正向邻居 $j = (x+1, y, z)$ 为例）的面通量为：
$$F_{x+1/2} = A_{x+1/2} \cdot \left( \frac{1}{\rho} \right)_{x+1/2} \cdot \frac{p_{j} - p_i}{\Delta x}$$

其中：
1. $A_{x+1/2} \in [0, 1]$ 为 Cut-Cell 共享面开口面积分数（来自 `FlipCutCellGeometry`）；
2. $\left( \frac{1}{\rho} \right)_{x+1/2}$ 为 M1 选定的**界面距离加权守恒面系数 (Interface-Distance-Weighted Conservative Candidate)**：
   - 若面两侧均为流体格：
     $$\left( \frac{1}{\rho} \right)_{x+1/2} = \frac{1}{\theta_m \rho_i + (1 - \theta_m) \rho_j}$$
     $\theta_m \in [0.05, 0.95]$ 为格内材料界面距网格中心的几何分数（无内部分辨时默认 $\theta_m = 0.5$ 得到调和倒数/逆平均密度 $\frac{1}{0.5(\rho_i + \rho_j)}$）；
   - 若邻居 $j$ 为空气（自由表面）：
     $$\left( \frac{1}{\rho} \right)_{x+1/2} = \frac{1}{\rho_i \cdot \theta_{\text{surface}}}$$
     $\theta_{\text{surface}} \ge 0.05$ 为 PLIC 测定的自由液面距离分数（Ghost-Fluid Method）。
3. 单元 $i$ 的右端项（Divergence RHS）：
   $$b_i = -\frac{1}{\Delta t} \left( \frac{A_{x+1/2} u^*_{x+1/2} - A_{x-1/2} u^*_{x-1/2} + A_{y+1/2} v^*_{y+1/2} - A_{y-1/2} v^*_{y-1/2} + A_{z+1/2} w^*_{z+1/2} - A_{z-1/2} w^*_{z-1/2}}{\Delta x} - \text{driftCorrection}_i \right)$$

#### 3.2 矩阵性质与投影速度更新
- **对称正定性 (SPD)**：由于面系数在单元 $i$ 与 $j$ 之间完全共享，离散 Laplace 矩阵保持精确对称，且由空气边界/压强针锚定正定性，PCG 保证单调收敛。
- **速度投影**：
  $$u_{x+1/2}^{n+1} = u_{x+1/2}^* - \Delta t \cdot \left( \frac{1}{\rho} \right)_{x+1/2} \cdot \frac{p_j - p_i}{\Delta x}$$

#### 3.3 变密度静水与界面测试
在 3D 连通器实验中验证：
1. **1.25:1 密度比（水 $\rho=1000$ vs 油 $\rho=800$）**：
   - 连通管中满足静水平衡：$\rho_1 g h_1 = \rho_2 g h_2 \implies \frac{h_{\text{oil}}}{h_{\text{water}}} = 1.25$；
   - 界面处垂直寄生流动速度 defect $\le 1.0 \times 10^{-8}$；
2. **13.6:1 大密度比（汞 $\rho=13600$ vs 水 $\rho=1000$）**：
   - 验证高密度差下 PCG 迭代次数在可控界内（$\le 48$ 次），无条件数溃散；
   - 汞柱与水柱平衡高度比严格符合 $1 : 13.6$。

---

### Phase 4: 多相动力学、分层与守恒重采样 (Multi-Material Reseeding & Dynamics)

#### 4.1 质量加权 P2G
在网格面动量与速度插值中，使用质量加权插值：
$$u_{\text{face}} = \frac{\sum_p m_p w_p u_p}{\sum_p m_p w_p} = \frac{\sum_p (\rho_p V_p) w_p u_p}{\sum_p (\rho_p V_p) w_p}$$
保证多相交界面附近动量守恒，避免轻流体带动重流体产生非物理暴走。

#### 4.2 多材料重采样规则 (Reseeding Rules)
1. **同格同材料合并 (Same-Material Merge)**：
   - 仅允许在同一网格内、具有**相同 `materialId`** 的 Marker 进行合并；
   - $V_{\text{merged}} = V_1 + V_2$；
   - $m_{\text{merged}} = m_1 + m_2$；
   - $\mathbf{u}_{\text{merged}} = \frac{m_1 \mathbf{u}_1 + m_2 \mathbf{u}_2}{m_{\text{merged}}}$；
   - $\mathbf{x}_{\text{merged}} = \frac{m_1 \mathbf{x}_1 + m_2 \mathbf{x}_2}{m_{\text{merged}}}$；
   - 严禁不可互溶相跨材料合并，防止物理成分污染。
2. **单粒子分裂 (Split)**：
   - 分裂出的子粒子继承母粒子的 `materialId`；
   - 体积与质量精确平分：$V_A = V_B = 0.5 V_p$，$m_A = m_B = 0.5 m_p$；
   - 动量与质心守恒。

#### 4.3 重力反转分层实验 (Rayleigh-Taylor Inversion)
- 在竖直封闭容器中，上方放置重液体（$\rho=1000$），下方放置轻液体（$\rho=800$）；
- 扰动后释放，重液体向下沉降、轻液体向上浮起；
- 最终在重力作用下自然反转并稳定分层，无持续非物理振荡，总质量与总体积闭合。

---

### Phase 5: 活动核心接入、维护路由与后续边界

#### 5.1 `FlipFluidDomain` 重构与单相兼容
- 将变密度压力算子与多材料 P2G 逻辑整合进 `FlipFluidDomain`；
- 提供重载构造与单相快照接口，保持向后兼容：当使用默认配置时，自动退化为纯水，现存所有单元测试及性能开销零回归。

#### 5.2 维护路由与 Gradle 任务更新
在 `build.gradle` 与 `docs/maintenance/routing.md` 中登记新任务：
- `tasks.register("runConnectedVesselValidation", JavaExec)`：定向执行 3D 连通器与 1:1、1.25:1、13.6:1 变密度静水及分层门。
- 遵循最小验证规则：单改动只跑定向门，不执行 clean build。

#### 5.3 跨阶段边界与后续隔离 (RFC Requirement)
在 Phase 5 结束并准备向 M4/M6 推进前，专门编写针对存储与网络的 RFC 文档：
1. **`region-state v2` 候选设计**：如何在两槽代次提交中以紧凑格式序列化 `particleMaterial`，并保持对 v1 纯水存档的原子向下兼容；
2. **`FluidProtocol` 多材质调色板 (Palette)**：服务端向客户端广播材料元数据与多相等值面快照的数据格式；
3. **世界容器交互与 Takeover 范围**：保持在 M6 评审通过前不扩大原版水替换逻辑。

---

## 四、验证阶梯与验收判据 (Acceptance Criteria)

| 阶段 | 最小定向测试命令 | 关键指标 / 验收门 |
|---|---|---|
| **Phase 1** | `./gradlew.bat runVariableDensityFaceCoefficientValidation --offline` | 1D 变密度系数在 1:1、1.25:1、13.6:1 下对称正定，误差 $< 1.0\times 10^{-10}$ |
| **Phase 2** | `./gradlew.bat runConnectedVesselValidation --offline` (1:1 模式) | 3D 连通器两材料等高平衡，$\Delta h \le 1.0\times 10^{-6}$，体积/质量损失 $\equiv 0$ |
| **Phase 3** | `./gradlew.bat runConnectedVesselValidation --offline` (变密度模式) | 1.25:1 与 13.6:1 液柱高度比完全符合 $\rho_1 h_1 = \rho_2 h_2$，界面寄生流 $< 1.0\times 10^{-8}$ |
| **Phase 4** | `./gradlew.bat runConnectedVesselValidation --offline` (动力学分层模式) | 重液下沉、轻液上浮稳定分层；重采样合并无跨相污染，百万步账本闭合 |
| **Phase 5** | `./gradlew.bat runFlipFreeSurfaceValidation runConnectedVesselValidation --offline` | 活动求解器单相纯水与变密度多相无缝兼容，现存 M2.5 门零回归 |

---

## 五、总结与下一步动作

本计划严格落实了 KISS 原则、状态真值边界与无回归策略。
经评审确认后，下一步行动为：
1. 创建 `src/test/java/com/example/fluid/ConnectedVesselPressureExperiment.java` 与 `ConnectedVesselValidation.java`；
2. 在 `build.gradle` 中注册 `runConnectedVesselValidation` task；
3. 实施 Phase 1 与 Phase 2，跑通 1:1 同密度两材料退化门。
