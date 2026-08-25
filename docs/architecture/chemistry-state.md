# 守恒化学状态契约

## Registry

化学门默认关闭并依赖材料门。首次启用时，数据包定义被规范化为 world-local registry snapshot；此后该 snapshot 的 stable ID、exact rational 定义、预算和 fingerprint 是世界权威。

当前数据包与 snapshot 不一致只报告 definition mismatch，不重解释已有库存。snapshot 未知、损坏或身份不符时区域和容器状态保留并 LOCKED。

## 独立真值

- marker 的材料质量/体积与 chemical parcel 分开保存。
- parcel 保存 stable species quanta、组成、净电荷、marker mass residual 和 indicator provenance。
- 区域保存逐 species 总量、组成/电荷审计、external source、escaped sink 与 residual ledger。
- `FluidMixture` 只保存机械材料库存；`ChemicalContents` 保存完整化学容器库存。两者不能同时作为同一物品的真值。

## 反应模型

- 当前只支持强电解质完整解离和同格、同 chemical phase 的瞬时计量反应。
- 定义使用 exact rational 化学计量；提交必须同时闭合 species、组成、电荷、质量和残差。
- 不实现弱酸、缓冲液、活度、有限速率动力学或通用平衡求解。
- 指示剂是 parcel provenance：随 marker/reseed/容器输运，不隔离反应，也不自动传播到后来混入的 marker。

## 求解顺序

每个 logical step 中，机械 advection 完成后按当前 cell 和 chemical phase 分组反应；反应热随后通过热状态的同 cell source/sink 提交。化学反应不得直接修改平滑 density、pressure 或客户端颜色场。

## 跨层边界

- region checkpoint 保存完整 parcel、registry identity 和审计账本。
- 容器转移先验证目标物品可完整表示 species、indicator、质量和 residual，再进入 WAL 事务。
- 创造模式放出是 external source；创造模式装取不能制造隐藏 sink。
- 客户端只接收 Top-N species、pH 和 indicator 等量化展示；这些字段不能恢复或结算库存。
- `chemstatus` 是管理员只读诊断并写 command transcript。

## 功能布局

区域载荷中的 chemistry presence 必须与本次启动的 chemistry 门完全一致。门变化不会向既有 checkpoint 添加或删除化学真值。
