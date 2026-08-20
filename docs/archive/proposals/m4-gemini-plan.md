# Archived proposal: M4 表面张力与固体润湿设计方案

> 本文是进入 M4 实施前的技术方案与执行计划，归档于 `docs/archive/proposals/`。实施阶段的系统契约以 [`../../architecture/fluid-solver.md`](../../architecture/fluid-solver.md) 为准，阶段总表以 [`../../roadmap/product-roadmap.md`](../../roadmap/product-roadmap.md) 为准，测试路由以 [`../../maintenance/routing.md`](../../maintenance/routing.md) 为准。

---

## 一、背景与当前状态

当前工程处于 `26.2` 分支，最新提交基线为 `e2aae61 Close M3 multi-fluid milestone`，主 Mod 版本为 `0.9.1`。
前序里程碑 M0（世界恢复事务）、M1（GPU 探测与变密度探针）、M2（多区域管理与原子持久化）、M2.5（自由表面与守恒重采样）及 M3（多液体、变密度、材料账本、液液界面与门控持久化）已全部关闭并通过实机与自动化验证。

本阶段自主接棒进入 **M4（表面张力与固体润湿）**。

### M3 遗留与 M4 核心物理目标

1. **解决静止水滴与薄水层的非物理发散**：
   在 M3 及之前的版本中，自由表面边界条件为简单的大气压 $p=0$。微小水滴或薄水层仅受重力和数值散度约束，缺乏物理内聚力，容易在无重力或微弱扰动下弥散或无法自然收缩成球形水滴。
2. **解决液液界面的亚格混相与表面粗糙度**：
   M3 的分层与多材料实验证实：在缺乏界面张力的情况下，亚格尺度下的孤立少数相 marker 会滞留在对侧主体中（重力/浮力被整格质量平均稀释）；液液界面呈阶梯分片状。M4 通过物理界面张力 $\sigma_{12}$ 引入曲率驱动的界面收缩与平滑，使不相溶液体自然凝聚并消除界面锯齿。
3. **消除吸墙、受支撑找平等经验伪规则，实现真实固体润湿**：
   原版及早期实验中容易依赖经验规则伪造“水往低处平铺”或“水附着在墙上”。M4 严格通过 **材料接触角 $\theta_c$（Young-Dupré 方程）** 与固体边界法向投影驱动接触线移动，实现亲水铺展、疏水收缩、毛细上升与毛细下降的物理自然呈现。

### 必须坚守的物理与架构红线

1. **物理真值与守恒绝对性**：
   - Marker `particleVolume` 与逐材料 `particleMass` 仍是体积与质量的唯一真值；
   - 表面张力与润湿是**体积力与压力跳跃条件**，严禁通过修改 marker 体积、强行合并不同相、直接吸附 marker 坐标或渲染欺骗来伪造表面张力效果。
2. **平衡力离散（Balanced-Force Formulation）**：
   - 表面张力引起的压力跳跃必须与压力梯度在 MAC 错位网格上保持精确代数平衡，严禁产生持续无界的寄生流动（Spurious / Parasitic Currents）。
3. **切向自由与法向约束**：
   - 固体碰撞继续维持法向单轴截断、切向绝对自由；
   - 润湿效应仅通过调整界面法向 $\mathbf{n}$ 作用于压力方程，不阻碍粒子切向真实流动。
4. **渐进式 CPU 独立验证与 KISS 原则**：
   - 遵循“先独立几何曲率与静态 Laplace 压力跳跃门 $\to$ 接触角与毛细门 $\to$ 接入活动求解器”的阶梯路线；
   - 单小步只跑一个最贴近改动的定向测试，不累积重复测试，不提前改动网络与世界交互。

---

## 二、M4 核心任务分解与实施路线

```text
                        ┌─────────────────────────────────────────────────────────┐
                        │                   M4 核心推进路线                       │
                        └────────────────────────────┬────────────────────────────┘
                                                     │
              ┌──────────────────────────────────────┴──────────────────────────────────────┐
              ▼                                                                             ▼
 ┌─────────────────────────────┐                                             ┌─────────────────────────────┐
 │ Phase 1: 数据契约与曲率引擎 │                                             │ Phase 2: 平衡力压力跳跃与   │
 │ (Data Contract & Curvature) │                                             │          静态液滴 Laplace 门│
 ├─────────────────────────────┤                                             ├─────────────────────────────┤
 │ 1. 扩展材料张力与接触角属性 │                                             │ 1. Ghost-Fluid 表面张力跳跃 │
 │ 2. 连续相指示函数与梯度法向 │                                             │ 2. 0G 静滴 Laplace 压强校验 │
 │ 3. 散度曲率 κ = -∇·n 离散   │                                             │ 3. 寄生流抑制 (u < 1e-4)    │
 │ 4. 球形液滴解析曲率 2/R 门  │                                             │ 4. 双液滴碰撞合并体积闭合   │
 └────────────┬────────────────┘                                             └──────────────┬──────────────┘
              │                                                                             │
              └──────────────────────────────────────┬──────────────────────────────────────┘
                                                     │
                                                     ▼
                                ┌─────────────────────────────────────────┐
                                │   Phase 3: 固体接触角与毛细作用门       │
                                │ (Contact Angle, Wetting & Capillarity)  │
                                ├─────────────────────────────────────────┤
                                │ 1. 固体壁面法向 Young-Dupré 边界条件    │
                                │ 2. 亲水/疏水液滴在平板上的铺展与收缩    │
                                │ 3. 竖直细管毛细上升/下降 (Jurin 定律)   │
                                │ 4. 无经验吸附规则，切向滑移保持自由     │
                                └────────────────────┬────────────────────┘
                                                     │
                                                     ▼
                                ┌─────────────────────────────────────────┐
                                │   Phase 4: 液液界面张力与相滞留消除     │
                                │ (Liquid-Liquid Interfacial Tension)     │
                                ├─────────────────────────────────────────┤
                                │ 1. 双相界面张力 σ₁₂ 与跳跃通量耦合      │
                                │ 2. 油水混合微滴自发聚并与相分离         │
                                │ 3. 消除 M3 亚格孤立 marker 悬浮滞留     │
                                │ 4. 界面物理平滑与客户端渲染法向增强     │
                                └────────────────────┬────────────────────┘
                                                     │
                                                     ▼
                                ┌─────────────────────────────────────────┐
                                │   Phase 5: 活动核心接入与全套回归       │
                                │ (`FlipFluidDomain` Integration & Gate)  │
                                ├─────────────────────────────────────────┤
                                │ 1. 整合至 PIC/FLIP 主循环与 substep 控制│
                                │ 2. σ=0 严格向后退化为 M3/M2.5 基线      │
                                │ 3. 登记 Gradle 任务与更新维护路由       │
                                └─────────────────────────────────────────┘
```

---

## 三、数学模型与数值离散设计

### 1. 物理控制方程与界面跳跃条件

在不可压缩纳维-斯托克斯框架下，表面张力表现为界面处的法向压力跳跃（Laplace-Young 条件）：
$$[p]_{\Gamma} = p_{\text{liquid}} - p_{\text{gas}} = \sigma \kappa$$
其中 $\sigma$ 为表面张力系数（$\text{N/m}$ 或模拟单位），$\kappa = \nabla \cdot \mathbf{n}_{\Gamma}$ 为界面的局部平均曲率。

对于两相不溶流体（如水-油界面）：
$$[p]_{1\to 2} = p_2 - p_1 = \sigma_{12} \kappa_{12}$$

### 2. 连续相指示场与曲率计算 (Curvature Estimation)

为了在欧拉 MAC 网格上稳健计算曲率，采用平滑指示函数 $\phi(\mathbf{x}) \in [0, 1]$（结合单元体积占有率 $f_v$ 与 marker 平滑核）：
1. **界面单位法向量**：
   $$\mathbf{n} = \frac{\nabla \phi}{\|\nabla \phi\|_\epsilon}, \quad \|\nabla \phi\|_\epsilon = \sqrt{|\nabla \phi|^2 + \epsilon^2} \quad (\epsilon = 10^{-6}/\Delta x)$$
   使用中心差分在网格节点/面上离散梯度算子 $\nabla$。
2. **平均曲率 $\kappa$**：
   $$\kappa = -\nabla \cdot \mathbf{n} = -\left( \frac{\partial n_x}{\partial x} + \frac{\partial n_y}{\partial y} + \frac{\partial n_z}{\partial z} \right)$$
   在网格中心处通过散度差分求得。对半径为 $R$ 的三维球体，解析曲率为 $\kappa = \frac{2}{R}$。

### 3. 平衡力 Ghost-Fluid 压力边界 (Balanced-Force GFM)

为彻底抑制传统 CSF（连续表面力）模型中的高频寄生涡流，将表面张力直接整合到 Ghost-Fluid 自由液面压力边界中：

1. **自由表面（液-气边界）**：
   在流体格 $i$ 与相邻空气格 $j$ 之间，自由液面处的等效 Dirichlet 压力不再是 0，而是包含 Laplace 压强的跳跃值：
   $$p_{\text{ghost}} = p_{\text{atm}} + \sigma \kappa_i = \sigma \kappa_i$$
   代入 M2.5 建立的分数距离 $\theta$ Ghost-Fluid 算子中，界面处的压力差为：
   $$\frac{\partial p}{\partial x} \approx \frac{p_{\text{ghost}} - p_i}{\theta \Delta x} = \frac{\sigma \kappa_i - p_i}{\theta \Delta x}$$
   - **静平衡自愈性**：当液滴处于静止平衡态时，泊松求解器自动得出 $p_i = \sigma \kappa_i$，使得驱动加速度 $\nabla p - \sigma \kappa \nabla \phi \equiv 0$，数值寄生流理论上精确归零。
2. **液-液内部界面（相 1 - 相 2 边界）**：
   在相邻的两相流体格之间，面通量项修正为：
   $$F_{f} = A_f \cdot \left(\frac{1}{\rho}\right)_f \left( \frac{p_2 - p_1 + \sigma_{12} \kappa_{12}}{\Delta x} \right)$$
   该附加常数项进入泊松方程右端项（RHS），保持左端离散系数矩阵严格对称正定（SPD），PCG 求解器收敛性不受影响。

### 4. 固体接触角与壁面润湿边界 (Young-Dupré Wall Condition)

在流体-固体接触线处，平衡接触角 $\theta_c$ 决定了界面法向在壁面处的边界条件：
$$\mathbf{n}_{\text{interface}} \cdot \mathbf{n}_{\text{wall}} = \cos \theta_c$$
$$\mathbf{n}_{\text{interface}} \times \mathbf{n}_{\text{wall}} = \sin \theta_c \cdot \mathbf{t}$$

- **算法实现**：在固体格（Cut-cell solid）内插值设置虚拟法向量 $\mathbf{n}_{\text{ghost}}$，使得在靠近壁面的流体格计算散度 $\kappa = -\nabla \cdot \mathbf{n}$ 时，曲率值自动包含由接触角偏离所引起的毛细弯曲；
- **动力学效应**：
  - 若当前接触角 $\theta > \theta_c$（例如亲水液体尚未完全铺展），计算出的曲率产生朝向壁面的毛细压力驱动液体向前铺展；
  - 若 $\theta < \theta_c$（疏水回缩），毛细力驱动液体向内收缩成珠；
  - 液体与壁面的法向碰撞仍由 CCD 截断，切向速度保持滑移，接触线以物理粘性阻尼自然移动。

---

## 四、各阶段详细设计与实施规范

### Phase 1: 数据契约扩展与独立曲率引擎

#### 1.1 材料定义扩展
在 `FluidMaterial` 中增加表面张力与接触角物理参数：
```java
public record FluidMaterial(
    byte id,
    String name,
    float density,
    float kinematicViscosity,
    int miscibleGroup,
    int defaultColorRgb,
    float surfaceTension,    // 液-气表面张力 σ (N/m 或 模拟单位，如 水: 0.0728)
    float contactAngleDeg    // 默认固体接触角 θc (度数，如 水: 45.0, 汞: 140.0)
) {
    public static final FluidMaterial WATER = new FluidMaterial(
        (byte) 0, "water", 1000.0F, 1.0E-6F, 0, 0x3F76E4, 0.0728F, 45.0F
    );
    public static final FluidMaterial OIL = new FluidMaterial(
        (byte) 1, "oil", 800.0F, 1.0E-5F, 1, 0xC8A642, 0.0300F, 30.0F
    );
    public static final FluidMaterial MERCURY = new FluidMaterial(
        (byte) 2, "mercury", 13600.0F, 1.0E-7F, 2, 0xD0D5DD, 0.4860F, 140.0F
    );
}
```

#### 1.2 独立曲率验证模块
创建 `src/test/java/com/example/fluid/SurfaceTensionCurvatureValidation.java`：
- 在 $16\times 16\times 16$ 网格中心放置半径 $R = 2.0, 3.0, 4.0$ 格的离散球体；
- 采样指示场 $\phi$ 并计算法向与曲率 $\kappa$；
- 验证球面上平均曲率与理论解 $\kappa = \frac{2}{R}$ 相对误差 $\le 3\%$。

---

### Phase 2: Balanced-Force 压力跳跃与静滴 Laplace 门

#### 2.1 零重力悬浮静滴门 (Zero-G Static Droplet Gate)
创建 `src/test/java/com/example/fluid/SurfaceTensionLaplaceExperiment.java`：
- 环境设为 $g = 0$；
- 初始化半径 $R=2.5$ 的静止水滴，施加表面张力 $\sigma = 0.0728$；
- 求解压力与速度投影循环 200 步；
- **验收判据**：
  1. 内部测得压力平均值与理论 Laplace 压强 $p = \frac{2\sigma}{R}$ 相对误差 $< 2.0\%$；
  2. 体系最大寄生流动速度 $\|u_{\text{parasitic}}\| \le 1.0 \times 10^{-4}$ 且长周期不发散；
  3. 总 Marker 体积与质量相对损失 $\equiv 0.0$。

#### 2.2 双液滴自发合并门 (Coalescence Gate)
- 初始化两个相邻的小水滴（轻微接触）；
- 表面张力产生的颈部高负曲率驱动内部高速流动，两液滴迅速颈缩并合并为一个大球形液滴；
- 伴随微弱阻尼衰减震荡后趋于稳定，总表面积自发减小，总体积精确闭合。

---

### Phase 3: 固体润湿与毛细作用门

#### 3.1 平板铺展与疏水收缩 (Droplet Wetting Test)
- **亲水铺展测试（水，$\theta_c = 45^\circ$）**：半球形液滴放置在水平底面上，自发铺展形成扁平球冠，接触角稳定在 $45^\circ \pm 3^\circ$；
- **疏水收缩测试（汞，$\theta_c = 140^\circ$）**：平铺的液体在底面上自发收缩滚成圆球，接触角维持钝角。

#### 3.2 毛细上升与下降 (Capillary Meniscus & Jurin's Law)
- 构建内径 $r = 1.0$ 格与 $r = 2.0$ 格的细管，下端浸入大水槽，施加标准重力 $g$；
- **毛细上升**：水柱在细管中自发上升，液面形成凹弯月面，上升高度精确符合 Jurin 定律 $h = \frac{2\sigma \cos \theta_c}{\rho g r}$；
- **毛细下降**：汞柱在细管中自发下降，液面形成凸弯月面。

---

### Phase 4: 液液界面物理锐化与相滞留消除

#### 4.1 亚格少数相聚集与相分离
- 复现 M3 留下的水-油分层后油层中残留的少量悬浮水滴 marker 场景；
- 在界面张力 $\sigma_{12}$ 驱动下，分散的微小水滴自发聚并成团，并在浮力作用下彻底沉降到底部水相，油层上方残存水 marker 归零；
- 消除 M3 中因缺乏曲率张力导致的亚格分散滞留现象。

#### 4.2 客户端渲染法向平滑 (Mesh Normal Smoothing)
- 保持物理真值和 Partitioned Tetrahedra 界面拓扑不动；
- 在客户端网格生成器（`FluidMaterialSurfaceMesher`）中，对共享界面顶点根据相邻三角形面积加权平滑法向量；
- 彻底消除逐 tetrahedron 平面渲染产生的阶梯视觉锯齿，呈现光滑自然的液体曲面。

---

### Phase 5: 活动求解器整合与回归

#### 5.1 `FlipFluidDomain` 整合
- 在 `solvePressure(dt)` 中接入 Ghost-Fluid 自由表面曲率跳跃与液液内部界面跳跃；
- 在壁面碰撞前计算包含接触角修正的法向场；
- 当全局或材料配置 $\sigma = 0$ 时，代码分支严格逐位退化为 M3 现行逻辑，零额外性能损耗。

#### 5.2 维护路由与 Gradle 任务
- 在 `build.gradle` 中注册定向任务：
  ```groovy
  tasks.register("runSurfaceTensionValidation", JavaExec) {
      group = "verification"
      description = "Runs M4 surface tension, Laplace pressure jump, wetting and capillarity gates."
      dependsOn tasks.named("testClasses")
      classpath = sourceSets.test.output + sourceSets.main.output + configurations.testCompileClasspath
      mainClass = "com.example.fluid.SurfaceTensionValidation"
  }
  ```
- 更新 [`docs/maintenance/routing.md`](../../maintenance/routing.md)。

---

## 五、验证阶梯与验收判据 (Acceptance Criteria)

| 阶段 / 测试项 | 最小定向测试命令 | 验收指标与硬门禁 |
|---|---|---|
| **Phase 1: 几何曲率** | `./gradlew.bat runSurfaceTensionValidation --offline` (Curvature) | 球体曲率误差 $\le 3\%$，无异常几何奇异点 |
| **Phase 2: Laplace 压强** | `./gradlew.bat runSurfaceTensionValidation --offline` (Laplace) | $p = 2\sigma/R$ 相对误差 $\le 2.0\%$，寄生速度 $u < 10^{-4}$ |
| **Phase 3: 接触角与毛细** | `./gradlew.bat runSurfaceTensionValidation --offline` (Wetting) | 接触角误差 $\le 5^\circ$，Jurin 毛细高度误差 $\le 5\%$ |
| **Phase 4: 液液相分离** | `./gradlew.bat runSurfaceTensionValidation --offline` (Interfacial) | 4000 步油层上方残留水 marker 单调清零，$\Delta V \equiv 0, \Delta m \equiv 0$ |
| **Phase 5: 综合回归** | `./gradlew.bat runM3ConnectedVesselValidation runFlipFreeSurfaceValidation --offline` | M3 变密度连通器与 M2.5 自由面全套回归 100% 通过 |

---

## 六、总结与下一步动作

本方案严格落实了 Balanced-Force 理论框架、Young-Dupré 壁面边界与无经验补丁红线。
经评审确认后，下一步行动为：
1. 在 `FluidMaterial` 中添加表面张力与接触角元数据；
2. 新建 `SurfaceTensionValidation.java` 并构造 Phase 1 几何曲率与 Phase 2 静态悬浮液滴 Laplace 压强实验；
3. 配置 `build.gradle` 定向任务，跑通首个 CPU 物理门。
