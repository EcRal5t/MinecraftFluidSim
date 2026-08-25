# 世界 ownership 与容器事务契约

## Ownership 模型

controller 内部由空气和独立 ownership registry 表示。原版水方块不是 solver 库存的副本。

新激活区域只有在 world bridge 已生效时才接管内部纯 `Blocks.WATER`：

1. 只读扫描 controller footprint 和实际原版水；
2. 准备新的完整 region generation；
3. 发布 ownership WAL 与 WARNING；
4. 修改世界方块；
5. 发布 region HEAD；
6. 建立 committed ownership 记录并清理 WAL。

恢复根据 region HEAD 与 WAL 的 base/target generation 判定重放或回滚。未知文件、损坏 NBT、身份不符、generation 歧义或玩家已放置的非水方块一律保留并锁定，不猜测修复，也不覆盖玩家方块。

已加载 solver 在 step、empty、checkpoint 和转移前也必须检查自己的 ownership sidecar；锁只作用于对应区域，但无法识别 region 的 store issue 会形成全局创建锁。

## 区域生命周期

- controller 必须有完整闭壳和全部已加载 chunk。
- UNLOADED 区域不持 solver，但仍占 manager 的 cell/marker 预算。
- checkpoint 成功后才能释放 solver；恢复成功后先刷新 collision，再发送 snapshot。
- 删除区域前必须处理 ownership，并要求实际凝聚相库存为空。`remove` 不把未知状态解释成可删除数据。

## 容器真值

- 液体容量使用 `long` 纳体积，1 block volume = 1,000,000,000 nanos。
- 材料桶保存每个稳定材料的纳体积与独立质量。
- 化学桶另外保存 species quanta、indicator 和 mass residual。
- 干颗粒容器使用整数 SVQ、逐材料质量与可选 solid composition。
- 热液体另外保存 registry-bound 总内能。
- item count 必须为 1；damage、tooltip、durability bar、颜色和温度不参与结算。

## 玩家与发射器路由

- 玩家只按准星命中的 controller 壳面和其正后方实际内部格选择区域。
- 发射器只按发射方向正对的壳面中心路由；壳边、壳角、多个候选和未加载目标拒绝。
- 插入/抽取原语 all-or-nothing。insert 先规划 marker/parcel 和容量，extract 先做双精度预检，再按整 marker 与尾拆分结算。
- 创造模式放出是 external source；创造模式装取仍从 solver 删除实际库存，不能制造隐藏 sink。

## 跨持久域 WAL

容器事务覆盖 region generation、actor item 和可选 registry：

1. `PREPARED` 保存 transaction/region/dimension、base/target generation、operation、typed resource、actor 和 before item。
2. solver 执行 exact transfer。
3. stage 并回读完整 target generation。
4. `REGION_COMMITTED` 增加实际量、after item 和 target manifest checksum。
5. 原子发布 region HEAD。
6. 修改 actor item，并等待玩家存档或 dispenser chunk 完成 durable save。
7. 删除 WAL。

恢复必须核对 HEAD、checksum、actor、before/after item、resource kind、registry fingerprint 和 generation。任何歧义都保留 WAL 并锁定；不得因为 item 看起来相似就推断完成状态。

当前 WAL 有材料布局和含热布局。reader 只接受当前 writer 产生的两种完整形状，不补缺字段。
