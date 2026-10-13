<div align="center">

# mini-sync

**轻量级异构数据源同步工具**

*支持插件化数据源扩展 · 增量水位管理 · 断点回写 · 可视化控制台*

</div>

---

## 项目介绍

**核心功能**：在不同的异构数据源之间进行数据同步，并通过 Web 控制台统一管理数据源与同步任务。

**意义**：只要公司规模较大、部门与部门之间有数据协作，就应该有类似的基础设施。比如业务组的同事需要把订单库的变更定时同步到数仓组的库里，爬虫组要把采集到的数据落到分析库。同步服务把这类散落的需求集中管理，收拢同步日志，避免每个人各写一套脚本。

**这个项目是什么定位**：参考主流数据同步工具的设计思路，自主实现核心同步链路。规模上不做大而全，而是把三件事做透：

1. **数据源抽象** —— 读写差异全部收敛到驱动层，新增数据源不改执行器一行代码
2. **增量同步正确性** —— 复合游标解决边界丢数据，失败时回写目标端真实水位
3. **插件化扩展** —— 驱动以独立 jar 形式存在，放入 `plugins/` 目录即被识别

---

## 项目特性

- **插件化加载数据源** —— 按固定契约开发 driver，打成 jar 放入 `plugins/` 目录即可使用，主工程零改动、零重新编译
- **异构互相同步** —— MySQL 与 PostgreSQL 可任意组合（双向验证通过），并已用 SQLite 作为独立第三方插件跑通 10 万条
- **增量同步不重不漏** —— 采用 `(水位字段, 主键)` 复合游标，严格在全序上单调推进
- **失败回写目标端水位** —— 保证 at-least-once，宁可重复也不丢失；配合 upsert 幂等，重跑无副作用
- **游标分页性能优化** —— 10 万条同步总耗时由 6.5 秒降至 2.2 秒，有实测数据与原理分析
- **可视化控制台** —— 数据源管理、同步任务、执行同步、执行历史、运行日志，数据源类型下拉由引擎动态提供

---

## 项目地址

| 平台 | 地址 |
|---|---|
| GitHub | https://github.com/SWD123-Arch/mini-sync |

---

## 技术栈

| 依赖 | 版本 | 用途 |
|---|---|---|
| Java | 21 | 主语言 |
| Maven | 3.9 | 包管理与多模块构建 |
| MySQL | 5.7 | 主库（源/目标/管理库） |
| PostgreSQL | 16 | 异构数据源（Docker） |
| SQLite | 3.45 | 第三方插件验证 |
| JDBC | — | 数据访问（无 ORM，刻意保持轻量） |
| SLF4J + Logback | 2.0 / 1.5 | 日志 |
| Lombok | 1.18 | 样板代码简化 |
| JUnit 5 | 5.10 | 单元测试 |
| Node.js | 16 | 控制台服务 |
| Docker | — | PostgreSQL 容器 |

**未使用（有意取舍）**：Spring Boot、MyBatis/JPA、Redis、xxl-job、Flink。
这些在这个规模下只会增加复杂度，不增加可讲的技术深度。

---

## 快速开始

### 环境要求

- JDK 21+
- Maven 3.9+
- MySQL 5.7+
- Docker（可选，用于 PostgreSQL）
- Node.js 16+（可选，用于控制台）

### 1. 准备测试数据（首次）

```powershell
# MySQL 建库 + 造 10 万行
Get-Content D:\mini-sync\sql\init.sql -Raw | & "C:\Program Files\MySQL\MySQL Server 5.7\bin\mysql.exe" -uroot -p<密码>

# 启动 PostgreSQL 容器
docker run -d --name mini-sync-pg -e POSTGRES_PASSWORD=<密码> -p 5432:5432 postgres:16

# PG 建表造数据
Get-Content D:\mini-sync\sql\pg_init.sql -Raw | docker exec -i mini-sync-pg psql -U postgres -d mini_sync_pg

# 建管理库（数据源配置 / 任务配置 / 执行日志 / 用户）
Get-Content D:\mini-sync\sql\admin.sql -Raw | & "C:\Program Files\MySQL\MySQL Server 5.7\bin\mysql.exe" -uroot -p<密码>
```

### 2. 编译（含插件打包）

```powershell
$env:JAVA_HOME = "D:\jdk"
cd D:\mini-sync
mvn -B clean install -DskipTests
```

构建产物会自动拷到 `plugins/` 目录：

```
plugins\
├── minisync-mysql-plugin-1.0.0.jar
├── minisync-postgresql-plugin-1.0.0.jar
└── minisync-sqlite-plugin-1.0.0.jar
```

### 3. 跑一次同步

```powershell
# 同库同步（游标分页）
mvn -B -q -pl mini-sync-job exec:java "-Dexec.mainClass=com.minisync.job.SyncBootstrap" "-Dexec.args=full 1000"

# 跨库同步（MySQL -> PostgreSQL）
mvn -B -q -pl mini-sync-job exec:java "-Dexec.mainClass=com.minisync.job.CrossDbBootstrap" "-Dexec.args=mysql2pg"

# 插件化验证（MySQL -> SQLite，两端驱动均来自插件 jar）
mvn -B -pl mini-sync-job exec:java "-Dexec.mainClass=com.minisync.job.PluginSyncBootstrap"

# 查看当前注册了哪些驱动类型
mvn -B -q -pl mini-sync-job exec:java "-Dexec.mainClass=com.minisync.job.DriverTypeLister"
```

### 4. 启动控制台

```powershell
cd D:\mini-sync\web
node server.js
# 打开 http://localhost:8090，默认账号 admin / admin123
```

控制台五个功能区：

| 菜单 | 能做什么 |
|---|---|
| 数据源管理 | 列表 + 实时行数 + 驱动来源；支持新建/修改/测试/预览/删除 |
| 同步任务 | 选源数据源与目标数据源，配源表/目标表/批次大小 |
| 执行同步 | 选任务一键触发，输出结果摘要 |
| 执行历史 | 每次同步的条数、耗时、成败 |
| 运行日志 | 同步引擎的实时输出，可开/关自动滚动 |

---

## 架构设计

### 模块划分

```
mini-sync/
├── mini-sync-spi\                插件契约（零依赖，只含接口）
│   ├── DataSourceDriver               驱动接口：6 个方法
│   ├── DataSourceConfig               连接配置
│   └── DataSourceDriverProvider       插件注册契约
│
├── mini-sync-plugin-loader\      插件加载器
│   ├── PluginDriverRegistry           扫目录 + 独立 ClassLoader + SPI 注册
│   └── LoadedPlugin                   已加载插件信息（类型/来源 jar）
│
├── mini-sync-connector\          驱动工厂与连接缓存（不含具体驱动）
│   ├── factory\DataSourceDriverFactory    插件优先，classpath 兜底
│   └── pool\DataSourcePool               按数据源 ID 缓存连接
│
├── mini-sync-job\                执行器与入口
│   ├── executor\SyncJobExecutor          同步核心（面试密集区）
│   ├── DynamicSyncBootstrap              从管理库读配置执行
│   ├── PluginSyncBootstrap               插件化验证入口
│   ├── DriverTypeLister                  列出已注册驱动类型
│   └── SqlQueryBootstrap                 控制台取数入口
│
├── mini-sync-plugins\            驱动插件（各自自包含）
│   ├── mysql-plugin\
│   ├── postgresql-plugin\
│   └── sqlite-plugin\                注意：不在主 reactor 中
│
├── plugins\                      打包产物落点（运行时扫描）
├── sql\                          建库脚本与测试数据
└── web\                          可视化控制台（Node + 单页 HTML）
```

### 为什么这样分层

关键问题是：**新增一种数据源，要改多少代码？**

```
DataSourceDriver         ← 接口，6 个方法，插件实现方唯一需要面对的契约
    ↑
DataSourceDriverProvider ← 插件用它声明"我支持哪些类型"
    ↑
PluginDriverRegistry     ← 启动时扫 plugins/，按 SPI 注册
    ↑
DataSourceDriverFactory  ← create(type)：插件优先，classpath 兜底
    ↑
DataSourcePool           ← 按数据源 ID 缓存连接
    ↑
SyncJobExecutor          ← 只依赖接口，不知道对面是 MySQL 还是 SQLite
```

答案：**实现一个 driver + 写一个 provider + 加一行 SPI 声明**，主工程零改动。

### 工厂与连接池的职责边界

这一对是面试高频问题，因为它们长得像但维度不同：

| | 工厂 Factory | 连接池 Pool |
|---|---|---|
| 缓存 | **不缓存** | 按**数据源 ID** 缓存 |
| 回答的问题 | "这种类型怎么造？" | "这一个具体库的连接在哪？" |
| 维度 | 类型 | 数据源实例 |

工厂必须每次返回新实例。原因是一个 driver 内部持有唯一一个 Connection ——
如果工厂返回共享单例，当源库与目标库恰好同为 MySQL 时，两端会拿到同一个实例、
共用同一条连接，数据写到自己身上。**这是本项目早期真实踩过的坑。**

---

## 使用姿势

### 1. 登录系统

打开 http://localhost:8090，默认账号 `admin` / 密码 `admin123`。

> 演示级会话：token 存于服务内存，进程重启即失效；密码为明文比对。
> 生产实现应换 JWT + Redis + BCrypt。

### 2. 数据源管理

列出全部数据源，含实时行数、连接状态、**驱动来源**（来自哪个插件 jar）。

「+ 新建数据源」的表单按类型自动变形：

| 字段 | MySQL / PostgreSQL | SQLite |
|---|---|---|
| 主机地址 / 端口 | 显示 | 隐藏（本地文件无服务端概念） |
| 用户名 / 密码 | 显示 | 隐藏 |
| 数据库名 | 逻辑库名 `mini_sync_src` | **文件路径** `D:\...\demo.db` |

### 3. 同步任务

选源数据源与目标数据源，配源表、目标表、批次大小。
源与目标可任意组合，执行时由工厂按两端 `ds_type` 分别选驱动。

### 4. 执行同步

「执行同步」页选任务点开始，「运行日志」页看实时输出，「执行历史」页看汇总。

---

## 核心设计

### 1. 复合游标：增量同步为什么不重不漏

**问题**：若只按 `update_time > 水位` 翻页，当同一时间戳存在多行且跨批次时，
要么因 `>` 跳过尚未读取的同行，要么因 `>=` 重复读取同一批。

**解法**：把主键作为水位的第二排序键与第二个过滤条件：

```sql
WHERE (update_time > ?)
   OR (update_time = ? AND id > ?)
ORDER BY update_time ASC, id ASC
LIMIT ?
```

任意两行的 `(update_time, id)` 组合不可能完全相同（id 唯一），构成严格全序；
游标 `(water, pk)` 单调前进，因此不重不漏。

### 2. 失败回写目标端水位

**问题**：增量批次写入失败时，位点该记哪里？

**错答**：记源端读到的位置。但这批数据在目标端根本没写进去，下次任务会跳过它，造成**永久丢失**。

**本项目做法**：回查目标端真实写入进度，从那里继续。

```java
} catch (Exception e) {
    String targetWater = safeQueryTargetWater(target, job, waterField);
    throw new IncrementSyncException("已回写目标端水位=" + targetWater, ...);
}
```

这保证了 **at-least-once**；配合驱动层的 upsert 幂等，重复写也不会产生脏数据。

### 3. 游标分页 vs limit offset

```sql
-- 慢：MySQL 不能直接跳到第 90000 行，必须扫描并丢弃前 9 万行
SELECT * FROM t_order ORDER BY id LIMIT 1000 OFFSET 90000;

-- 快：索引范围查询，B+Tree 直接定位起点
SELECT * FROM t_order WHERE id > 90000 ORDER BY id LIMIT 1000;
```

实测数据见 [PERFORMANCE.md](PERFORMANCE.md)：10 万条总耗时 6.5s → 2.2s（约 2.9 倍）。

### 4. 各库 upsert 语法差异

| 数据库 | 写法 |
|---|---|
| MySQL | `ON DUPLICATE KEY UPDATE col=VALUES(col)` |
| PostgreSQL | `ON CONFLICT (id) DO UPDATE SET col=EXCLUDED.col` |
| SQLite | `ON CONFLICT(id) DO UPDATE SET col=excluded.col` |

**注意 PG 必须显式指定冲突列**，不能像 MySQL 那样省略。
这类"看似相同、实则语义有差异"的细节，正是数据源抽象必须逐个驱动验证、
而不能假定 JDBC 行为一致的原因。

---

## 插件化机制

**这是本项目最有辨识度的设计**，完整文档（含开发步骤、踩坑记录、与 datalinkx 的做法对比）见
[PLUGIN.md](PLUGIN.md)。

已注册驱动：

| 类型 | 显示名 | 插件 jar |
|---|---|---|
| `MYSQL` | MySQL | `minisync-mysql-plugin-1.0.0.jar` |
| `POSTGRESQL` | PostgreSQL | `minisync-postgresql-plugin-1.0.0.jar` |
| `SQLITE` | SQLite | `minisync-sqlite-plugin-1.0.0.jar` |

第三行是关键证据：**SQLite 驱动不在 mini-sync 的 Maven reactor 里**，
主工程没有任何一行 import 它的代码，但它能真实同步 10 万条数据。

### 开发一个新驱动

```text
第 1 步：建 Maven 工程，只依赖 mini-sync-spi 这一个 jar
第 2 步：实现 DataSourceDriver（把 JDBC 读写细节写进去）
第 3 步：实现 DataSourceDriverProvider（声明 type()，create() 每次返回新实例）
第 4 步：写 src/main/resources/META-INF/services/com.minisync.spi.DataSourceDriverProvider
第 5 步：mvn package，把 jar 丢进 plugins/，重启即生效
```

### 启动时扫描顺序

```
plugins/ 目录下所有 *.jar
    ↓ 每个 jar 一个独立 URLClassLoader（驱动版本互相隔离）
读 jar 内的 META-INF/services/...DataSourceDriverProvider
    ↓ 用本 jar 的 classloader 显式 Class.forName
按 type() 注册进工厂
    ↓
工厂 create(type)：插件查不到 → classpath 兜底 → 都没有才抛错
```

**单个坏插件不影响启动**：SPI 指向不存在的类时，记入 `failures` 并继续。
已用"故意造一个坏 jar"验证过，其余插件照常注册。

---

## 实测数据

详细报告见 [PERFORMANCE.md](PERFORMANCE.md)。

### 跨库同步

| 方向 | 条数 | 耗时 |
|---|---|---|
| MySQL → PostgreSQL | 100,000 | 1,525 ms |
| PostgreSQL → MySQL | 100,000 | 3,013 ms |
| MySQL → SQLite | 100,000 | 842 ms |

### 分页方式对比（4 轮取平均）

| 轮次 | 游标分页 | limit offset | 倍数 |
|---|---|---|---|
| 第 1 轮 | 2,553 ms | 6,691 ms | 2.62× |
| 第 2 轮 | 2,368 ms | 6,737 ms | 2.85× |
| 第 3 轮 | 1,783 ms | 6,271 ms | 3.52× |
| 第 4 轮 | 2,228 ms | 6,248 ms | 2.80× |
| **平均** | **2,233 ms** | **6,487 ms** | **约 2.9×** |

单批查询耗时随 offset 增大而劣化：offset 8,000 → 4 ms，offset 99,000 → 54 ms；
游标分页恒定在个位数毫秒，与翻到第几页无关。

### 数据完整性

- 源表 100,000 行 = 目标表 100,000 行，字段无丢失
- 幂等性：重复执行后目标表仍为 100,000 行（依赖 upsert 语义）
- 插件化同步后直接查 SQLite 文件核对行数，与同步条数一致

---

## 已知限制

诚实列出来，避免面试被问到时措手不及。

| 限制 | 说明 | 生产方案 |
|---|---|---|
| 新增插件需重启进程 | 未做热加载。JDK 9+ 起 `URLClassLoader` 不再是 `ClassLoader` 的公开子类型，且 classloader 卸载要求"类、实例、加载器三者均不可达"，中途卸载易造成连接泄漏 | 常驻配置中心 + 监听变更 |
| 控制台取数对插件类型较慢 | ~17 s。根因是 `Node → mvn.cmd → JVM` 每次查询都要起一个 Maven，不是 SQLite 本身慢 | 引擎改为常驻 Java 服务，Node 走 HTTP 反代（毫秒级） |
| 全量同步无断点续传 | 全量从头跑到尾，进程被杀要重来（增量已有水位记录） | 按主键范围分段持久化位点 |
| 同步条数为乐观计数 | `total += batch.size()`，未校验目标端实际写入行数 | 累加 `executeBatch()` 返回值，或比对写入前后行数差 |
| 密码明文存储 | 管理库中 `datasource_config.password` 与 `sys_user.password` 均为明文 | 加密存储 + 密钥管理 |
| 登录为演示级 | token 存服务内存，无过期机制 | JWT + Redis |

---

## 单元测试

```powershell
mvn -B clean install -pl mini-sync-job -am "-Dtest=PluginDriverRegistryTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

覆盖 7 项：SPI 发现、按类型创建、大小写与空白容忍、工厂每次返回新实例、
未知类型的可读报错、空类型拒绝、provider 无参构造器实例化。

---

## 目录速查

| 文件 | 说明 |
|---|---|
| [README.md](README.md) | 本文件：项目总览 |
| [PLUGIN.md](PLUGIN.md) | 插件化机制详解、开发新驱动步骤、踩坑记录 |
| [PERFORMANCE.md](PERFORMANCE.md) | 实测性能数据、分页原理分析、复现命令 |
| `sql/init.sql` | MySQL 建库 + 10 万行测试数据 |
| `sql/pg_init.sql` | PostgreSQL 建表 + 测试数据 |
| `sql/admin.sql` | 管理库四张表 + 初始数据 |
| `web/server.js` | 控制台接口服务 |
| `web/login.html` | 登录页 |
| `web/index.html` | 控制台主页面 |

---

<div align="center">

**JDK 21** · **Maven 3.9** · **MySQL 5.7** · **PostgreSQL 16** · **SQLite 3.45**

</div>
