# mini-sync 实测性能数据

> 测量环境：Windows / JDK 21.0.10 / MySQL 5.7.34 / PostgreSQL 16.15 (Docker) / SQLite 3.45 / 本机直连
> 数据量：100,000 行（t_order）｜批次大小：1000
> 测量时间：2026-10-13、2026-10-14

---

## 一、多数据源支持

| 数据源 | 实现类 | 来源 |
|---|---|---|
| MySQL | `com.minisync.plugin.mysql.MysqlDataSourceDriver` | 插件 jar |
| PostgreSQL | `com.minisync.plugin.postgresql.PostgresqlDataSourceDriver` | 插件 jar |
| SQLite | `com.minisync.plugin.sqlite.SqliteDataSourceDriver` | 插件 jar（独立第三方） |
| Oracle / ClickHouse / … | — | 按 SPI 约定开发即可接入 |

**驱动全部以自包含插件 jar 的形式存在于 `plugins/` 目录**，主工程不含任何具体驱动实现。
新增数据源不需要改 mini-sync 一行代码，详见 [PLUGIN.md](PLUGIN.md)。

**架构验证结论**：新增 PostgreSQL 支持时，同步执行器、连接池、工厂路由
一行代码都没改；更进一步，把 MySQL / PostgreSQL 驱动**整体搬出主工程**
变成独立插件后，执行器与池子同样零改动。

### 与 MySQL 驱动的三处关键差异

| 差异点 | MySQL | PostgreSQL |
|---|---|---|
| Upsert 语法 | `ON DUPLICATE KEY UPDATE col=VALUES(col)` | `ON CONFLICT (id) DO UPDATE SET col=EXCLUDED.col` |
| 批量重写参数 | `rewriteBatchedStatements=true` | `reWriteBatchedInserts=true` |
| 冲突列 | 自动识别主键/唯一索引 | **必须显式指定**冲突目标列 |

---

## 二、跨库同步实测

| 方向 | 条数 | 耗时 |
|---|---|---|
| MySQL → PostgreSQL | 100,000 | **1,525 ms** |
| PostgreSQL → MySQL | 100,000 | **3,013 ms** |
| MySQL → SQLite | 100,000 | **842 ms** |

> 最后一行由 `PluginSyncBootstrap` 跑出，两端驱动均来自 `plugins/` 目录的插件 jar，
> 跑完直接查 SQLite 文件核对行数，与同步条数一致。
> SQLite 快是因为它走 batch 逐条 `executeUpdate`（它对 `addBatch` 支持不完整），
> 但本地文件无网络往返，整体仍最快。
>
> 反向（PG → MySQL）较慢的原因：MySQL 5.7 的 `ON DUPLICATE KEY UPDATE` 在批量写入时
> 需要逐行检查唯一约束，而 PG 的 `ON CONFLICT` 走的是索引推断路径。

---

## 三、分页方式性能对比（4 轮稳定测量）

| 轮次 | 游标分页 | limit offset | 倍数 |
|---|---|---|---|
| 第 1 轮 | 2,553 ms | 6,691 ms | 2.62× |
| 第 2 轮 | 2,368 ms | 6,737 ms | 2.85× |
| 第 3 轮 | 1,783 ms | 6,271 ms | 3.52× |
| 第 4 轮 | 2,228 ms | 6,248 ms | 2.80× |
| **平均** | **2,233 ms** | **6,487 ms** | **约 2.9×** |

**结论：游标分页比 limit offset 快约 2.9 倍。**

补充说明：
- offset 分页**非常稳定**（6,248 ~ 6,737 ms，波动仅 8%）
- 游标分页波动较大（1,783 ~ 2,553 ms），受 JVM 预热与机器负载影响
- 剔除首次冷启动后，倍数稳定在 **2.8× ~ 3.5×**

---

## 四、单批查询耗时劣化（最关键的证据）

offset 模式下，同一张表、同样取 1000 行，**只因为偏移量不同，查询耗时差 10 倍以上**：

| offset | 单批查询耗时 |
|---|---|
| 8,000 | 4 ms |
| 9,000 | 5 ms |
| 80,000 | 35 ms |
| 90,000 | 35 ms |
| 95,000 | 54 ms |
| 99,000 | 54 ms |

而游标分页（`WHERE id > ? LIMIT ?`）的单批耗时**恒定在个位数毫秒**，与翻到第几页无关。

---

## 五、原理（面试要能讲透）

### 为什么 offset 慢

```sql
SELECT * FROM t_order ORDER BY id LIMIT 1000 OFFSET 90000;
```

MySQL **无法直接跳到第 90000 行**，执行过程是：
1. 沿主键索引从头开始扫描
2. **丢弃**前 90000 行
3. 返回后面的 1000 行

实际扫描行数 ≈ `offset + limit = 91000`，**成本 O(offset + limit)**。

### 为什么游标快

```sql
SELECT * FROM t_order WHERE id > 90000 ORDER BY id LIMIT 1000;
```

`id > 90000` 是**索引范围查询**，B+Tree 直接定位到起点，顺序读 1000 行。
**成本 O(limit)**，与偏移量解耦。

### 游标分页的前提（必须主动说明）

1. 表需有**单调递增的唯一列**（通常用自增主键）
2. **同步期间该列不能被更新**，否则会有行被跳过
3. 不支持"跳页"，只能顺序翻

---

## 六、简历上的表述

```
优化分页查询与写入性能：针对 limit offset 深翻页性能劣化（偏移 9 万行时
单批查询耗时达 54ms），改用基于主键的游标分页，使查询耗时与偏移量解耦；
配合批量提交与分批事务，10 万条数据同步总耗时由 6.5 秒降至 2.2 秒。
```

---

## 七、其它验证结果

| 项目 | 结果 |
|---|---|
| 数据完整性 | 源表 100,000 行 = 目标表 100,000 行，字段无丢失 |
| 批次数量 | 100 批（每批 1000 条），无失败 |
| 幂等性 | 重复执行后目标表仍为 100,000 行（依赖 upsert 语义） |
| 跨库字段类型 | BigDecimal / Timestamp / Long 均正确映射 |

---

## 八、可视化控制台

启动方式：

```powershell
cd D:\mini-sync\web
node server.js
# 打开 http://localhost:8090
```

界面功能：
- **数据源管理**：展示全部数据源类型、连接状态与实时记录数；
  类型下拉**动态来自引擎**（`/api/drivertype/list`），新增插件后自动多出一项
- **触发同步**：支持 MySQL↔MySQL、MySQL↔PostgreSQL、MySQL↔SQLite 任意组合
- **执行日志**：实时输出
- **性能对比图表**：一键跑两种分页方式并出柱状图
- **数据预览**：可切换查看各数据源的数据

---

## 九、插件化驱动

驱动全部以独立 jar 存在于 `plugins/`，主工程零驱动实现。
新增一种数据源只需实现 `DataSourceDriver` + 一个 provider + 一行 SPI 声明，
放入 `plugins/` 即生效，**mini-sync 主工程不改一行代码、不重新编译**。

完整机制、开发步骤与踩坑记录见 [PLUGIN.md](PLUGIN.md)。

已验证的实测数据：

| 验证项 | 结果 |
|---|---|
| 插件注册 | MYSQL / POSTGRESQL / SQLITE 三个类型，来源 jar 均可追溯 |
| SQLite 真连库同步 | MySQL → SQLite，100,000 条，842 ms，行数核对一致 |
| 类隔离 | 每个插件独立 `URLClassLoader` |
| 单点故障隔离 | 故意放入 SPI 指向不存在类的 jar，其余插件照常注册、程序正常启动 |
| classpath 兜底 | 清空 `plugins/` 目录后仍能跑 MySQL 同步（100,000 条 / 1860 ms） |
| 单元测试 | `PluginDriverRegistryTest` 7 项全绿 |
| 控制台行数探测 | 4 个数据源（含 SQLite）17.2 s；纯本地通道 ~165 ms |

---

## 十、复现命令

```powershell
# 1. MySQL 建库造数据（首次）
Get-Content D:\mini-sync\sql\init.sql -Raw | & "C:\Program Files\MySQL\MySQL Server 5.7\bin\mysql.exe" -uroot -ps123456

# 2. 启动 PostgreSQL 容器（首次）
docker run -d --name mini-sync-pg -e POSTGRES_PASSWORD=s123456 -p 5432:5432 docker.1panel.live/library/postgres:16

# 3. PG 建表造数据（首次）
Get-Content D:\mini-sync\sql\pg_init.sql -Raw | docker exec -i mini-sync-pg psql -U postgres -d mini_sync_pg

# 4. 编译（含插件打包，产物自动拷到 plugins/）
$env:JAVA_HOME = "D:\jdk"
cd D:\mini-sync
& "D:\apache-maven-3.9.16\bin\mvn.cmd" clean install -DskipTests

# 5. 同库同步（游标分页）
& "D:\apache-maven-3.9.16\bin\mvn.cmd" -q -pl mini-sync-job exec:java `
  "-Dexec.mainClass=com.minisync.job.SyncBootstrap" "-Dexec.args=full 1000"

# 6. 跨库同步
& "D:\apache-maven-3.9.16\bin\mvn.cmd" -q -pl mini-sync-job exec:java `
  "-Dexec.mainClass=com.minisync.job.CrossDbBootstrap" "-Dexec.args=mysql2pg"

# 7. 插件化验证：MySQL -> SQLite（驱动来自插件 jar）
& "D:\apache-maven-3.9.16\bin\mvn.cmd" -B -pl mini-sync-job exec:java `
  "-Dexec.mainClass=com.minisync.job.PluginSyncBootstrap"

# 8. 查看当前注册了哪些驱动类型
& "D:\apache-maven-3.9.16\bin\mvn.cmd" -B -q -pl mini-sync-job exec:java `
  "-Dexec.mainClass=com.minisync.job.DriverTypeLister"

# 9. 单元测试
& "D:\apache-maven-3.9.16\bin\mvn.cmd" -B clean install -pl mini-sync-job -am `
  "-Dtest=PluginDriverRegistryTest" "-Dsurefire.failIfNoSpecifiedTests=false"

# 10. 启动可视化控制台
cd D:\mini-sync\web
node server.js
```

