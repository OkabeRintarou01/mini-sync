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
| Spring Boot | 3.3.5 | 常驻 Web 服务（控制台后端） |
| MyBatis-Plus | 3.5.7 | 管理库持久层（数据源/任务/日志/用户） |
| MySQL | 5.7 | 主库（源/目标/管理库） |
| PostgreSQL | 16 | 异构数据源（Docker） |
| SQLite | 3.45 | 第三方插件验证 |
| JDBC | — | 同步引擎的数据访问（不经过 ORM） |
| SLF4J + Logback | 2.0 / 1.5 | 日志 |
| Lombok | 1.18 | 样板代码简化 |
| JUnit 5 | 5.10 | 单元测试 |
| Docker | — | PostgreSQL 容器 |

**分层原则（重要）**：

- **管理库**（mini-sync 自己的配置表）→ 走 **MyBatis-Plus**
- **被同步的源库/目标库** → 走 **插件驱动 + JDBC**，不经过 ORM

两者职责不同：前者是单表 CRUD，后者需要在运行时按类型加载任意插件驱动。
把外部数据源也塞进 Mapper 会破坏插件化的核心价值。

**未使用（有意取舍）**：Redis、xxl-job、Flink。
Redis 计划用于多实例场景下的任务锁与进度共享（当前单实例用内存即可）；
后两者在这个规模下只会增加复杂度，不增加可讲的技术深度。

---

## 快速开始

### 环境要求

- JDK 21+
- Maven 3.9+
- MySQL 5.7+
- Docker（可选，用于 PostgreSQL）
- Node.js 16+（可选，用于控制台）

### 配置说明：凭据从环境变量读

数据库账号口令从环境变量或 `.env` 文件注入，**仓库不提供默认密码**。先复制模板并自行填写密码：

```powershell
# 1. 复制模板并编辑密码项（不要把 .env 提交到 Git）
Copy-Item .env.example .env
notepad .env

# 至少填写：
# MINISYNC_DB_PWD=<你的 MySQL 密码>
# MINISYNC_PG_PWD=<你的 PostgreSQL 密码>   （用 PG 时才需要）
```

| 变量 | 用途 | 是否必须 |
|---|---|---|
| `MINISYNC_DB_PWD` | MySQL 管理库/源库密码 | **必须** |
| `MINISYNC_DB_USER` | MySQL 用户名 | 否，默认 `root` |
| `MINISYNC_PG_PWD` | PostgreSQL 密码 | 使用 PG 时必须 |
| `MINISYNC_PG_USER` | PostgreSQL 用户名 | 否，默认 `postgres` |
| `MINISYNC_ADMIN_HOST` / `MINISYNC_ADMIN_PORT` / `MINISYNC_ADMIN_DB` | 管理库连接 | 否，有本地默认值 |
| `MINISYNC_SERVER_PORT` | Web 服务端口 | 否，默认 `8081` |
| `MINISYNC_MVN` / `MINISYNC_JAVA_HOME` | 本机 Maven / JDK 路径 | 否，按本机安装位置配置 |

> `.env` 已在 `.gitignore` 中排除；仓库只提交无密码值的 `.env.example`。
> `sql/admin.sql` 不再初始化含口令的数据源和同步任务，首次运行请在控制台创建。

### 启动服务

**方式一：一键脚本（推荐）**

```powershell
cd D:\mini-sync
.\start.bat            # 直接启动（自动加载 .env）
.\start.bat --build    # 先全量编译再启动
```

**方式二：手动启动**

```powershell
cd D:\mini-sync
$env:JAVA_HOME = "D:\jdk"

# 首次或改了 Java 代码后：编译（会同时把驱动打成插件 jar）
mvn -B clean install -DskipTests

# 启动 Spring Boot 常驻服务（会自动读取项目根目录的 .env）
mvn -B -pl mini-sync-server spring-boot:run
```

启动成功后访问 **http://localhost:8081**，默认账号 `admin` / `admin123`。

服务是**常驻**的：JVM 只启动一次，后续所有请求在同进程内处理，
所以数据源行数探测、同步触发都是毫秒级响应。

### 1. 准备测试数据（首次）

```powershell
# 先按"配置说明"创建并填写 D:\mini-sync\.env

# MySQL 建库 + 造 10 万行（-p 交互提示密码）
Get-Content D:\mini-sync\sql\init.sql -Raw | & $env:MINISYNC_MYSQL_BIN -u$env:MINISYNC_DB_USER -p

# 建管理库 schema 与演示登录用户（不预填任何数据库数据源口令）
Get-Content D:\mini-sync\sql\admin.sql -Raw | & $env:MINISYNC_MYSQL_BIN -u$env:MINISYNC_DB_USER -p

# 启动 PostgreSQL 容器：密码取自本地 .env，不要把真实密码写进仓库/命令历史
# 在已加载 .env 的 PowerShell 终端执行
docker run -d --name mini-sync-pg -e POSTGRES_PASSWORD="$env:MINISYNC_PG_PWD" -p 5432:5432 postgres:16

# PG 建表造数据（PGPASSWORD 从环境变量继承）
$env:PGPASSWORD = $env:MINISYNC_PG_PWD
Get-Content D:\mini-sync\sql\pg_init.sql -Raw | docker exec -e PGPASSWORD="$env:PGPASSWORD" -i mini-sync-pg psql -U $env:MINISYNC_PG_USER -d $env:MINISYNC_PG_DB
```

### 2. 编译（含插件打包）

编译会在打包驱动插件的同时，把 jar 拷到 `plugins/` 目录：

```powershell
cd D:\mini-sync
$env:JAVA_HOME = "D:\jdk"
mvn -B clean install -DskipTests
```

```
plugins\
├── minisync-mysql-plugin-1.0.0.jar
├── minisync-postgresql-plugin-1.0.0.jar
└── minisync-sqlite-plugin-1.0.0.jar
```

### 3. 启动控制台

```powershell
cd D:\mini-sync
.\start.bat
# 打开 http://localhost:8081，默认账号 admin / admin123
```

### 4. 跑一次同步（两种方式）

**方式一：网页控制台（推荐）**

登录后左侧「同步任务」→ 选任务点「执行」，或到「执行同步」页选任务点「开始同步」。
提交后立刻返回，进度实时刷新。

**方式二：命令行（调试时日志最全）**

```powershell
# 先加载 .env（命令行进程不会自动读 .env）
Get-Content D:\mini-sync\.env | ForEach-Object {
  if ($_ -match '^\s*([^#=]+)\s*=\s*(.*)$') {
    [Environment]::SetEnvironmentVariable($matches[1].Trim(), $matches[2].Trim(), 'Process')
  }
}
$env:JAVA_HOME = "D:\jdk"

# 按 jobId 跑一次同步（jobId 在控制台任务列表里看）
mvn -B -pl mini-sync-job exec:java "-Dexec.mainClass=com.minisync.job.DynamicSyncBootstrap" "-Dexec.args=6"

# 查看当前注册了哪些驱动类型
mvn -B -q -pl mini-sync-job exec:java "-Dexec.mainClass=com.minisync.job.DriverTypeLister"

# 其它验证入口
mvn -B -q -pl mini-sync-job exec:java "-Dexec.mainClass=com.minisync.job.SyncBootstrap" "-Dexec.args=full 1000"
mvn -B -q -pl mini-sync-job exec:java "-Dexec.mainClass=com.minisync.job.CrossDbBootstrap" "-Dexec.args=mysql2pg"
mvn -B -pl mini-sync-job exec:java "-Dexec.mainClass=com.minisync.job.PluginSyncBootstrap"
```

> 命令行与网页走的是**同一份同步逻辑**（`SyncExecutionService`），
> 区别只在触发方式与水位落库的实现（JDBC vs MyBatis-Plus）。

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
│   ├── DataSourceDriver               驱动接口：7 个方法
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
├── mini-sync-job\                同步引擎（纯 Java，无框架依赖）
│   ├── service\SyncExecutionService      ★ 全量/增量执行 + 水位落库策略
│   ├── executor\SyncJobExecutor          同步核心（游标分页 / 复合游标）
│   ├── DynamicSyncBootstrap              命令行入口（按 jobId 执行）
│   ├── PluginSyncBootstrap               插件化验证入口
│   ├── DriverTypeLister                  列出已注册驱动类型
│   └── SqlQueryBootstrap                 通用取数入口
│
├── mini-sync-server\             ★ 常驻 Web 服务（Spring Boot）
│   ├── controller\                       15 个 REST 接口
│   │   ├── AuthController                    登录/登出
│   │   ├── DatasourceController              数据源 CRUD
│   │   ├── JobController                     任务 CRUD + 重置水位
│   │   ├── RunController                     触发同步/进度/历史/预览
│   │   ├── DriverTypeController              驱动类型列表
│   │   └── HealthController                  健康检查
│   ├── service\
│   │   ├── SyncTaskService                   异步执行编排
│   │   ├── SyncRunStateService               运行状态与进度（内存）
│   │   ├── DatasourceProbeService            按配置连任意库执行 SQL
│   │   ├── DriverRegistryService             暴露插件注册表
│   │   └── AuthService                       登录认证（演示级）
│   ├── mapper\                           4 个 MyBatis-Plus Mapper
│   ├── entity\                           4 个实体
│   └── resources\static\                 前端页面（登录 + 控制台）
│
├── mini-sync-plugins\            驱动插件（各自自包含）
│   ├── mysql-plugin\
│   ├── postgresql-plugin\
│   └── sqlite-plugin\                注意：不在主 reactor 中
│
├── plugins\                      打包产物落点（运行时扫描）
├── sql\                          建库脚本与测试数据
└── start.bat                     一键启动脚本
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

### 一次同步请求的完整链路

```
浏览器
  │  GET /api/run?jobId=6
  ▼
RunController                        ① 校验任务、检查并发锁
  │  runStateService.tryLockJob()
  │  syncTaskService.executeAsync()  ② 提交到后台线程池 → 立即返回 runId
  ▼（HTTP 请求在此结束，约 10ms）
─────────────────────────────────────────────────────────────
后台线程 sync-<n>
  │  jobMapper / dsMapper            ③ 从管理库读任务与数据源（MyBatis-Plus）
  ▼
SyncExecutionService                ④ 与命令行入口【共用】的同步逻辑
  │  DataSourceDriverFactory.create()  按 ds_type 从 plugins/ 选驱动
  ▼
SyncJobExecutor                     ⑤ 全量：主键游标分页
  │                                     增量：复合游标 (水位, 主键)
  │  每批成功 → 回调落水位            ⑥ 进程被杀也能续传
  ▼
源库 ──读取──► 内存批次 ──upsert──► 目标库
  │
  ▼
写 sync_log + 更新内存运行状态
─────────────────────────────────────────────────────────────
浏览器
  ▲  GET /api/run/status?runId=...   ⑦ 每 700ms 轮询一次进度与日志
```

**三个关键设计点**：

1. **触发与执行分离**：HTTP 请求只负责"提交 + 返回 runId"，真正的同步在后台线程池跑。
   否则 10 万条的同步会让请求挂几十秒，网关还可能超时掐断。
2. **同步逻辑单一实现**：命令行入口和 Web 服务都调用 `SyncExecutionService`，
   只是水位落库的实现不同（JDBC vs MyBatis-Plus），避免"两条路径结果不一样"。
3. **同任务并发保护**：`tryLockJob` 用 `putIfAbsent` 保证原子性，
   重复点击返回 409，而不是把同一份数据搬两遍。

---

## 使用姿势

### 1. 登录系统

打开 http://localhost:8081，默认账号 `admin` / 密码 `admin123`。

> 演示级会话：token 存于服务内存，进程重启即失效。
> 账号口令从 `sys_user` 表读取（`sql/admin.sql` 初始化），
> 当前为明文比对，生产实现应换 BCrypt + JWT + Redis。

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
| 运行状态存内存 | 同步进度与日志存在 `SyncRunStateService` 的 `ConcurrentHashMap` 里：**服务重启后进度会丢**，多实例部署时读不到彼此的状态 | 换成 Redis（这一步已在计划中，届时只替换该类实现，Controller 与前端不用改） |
| 新增插件需重启进程 | 未做热加载。JDK 9+ 起 `URLClassLoader` 不再是 `ClassLoader` 的公开子类型，且 classloader 卸载要求"类、实例、加载器三者均不可达"，中途卸载易造成连接泄漏 | 常驻配置中心 + 监听变更 |
| 全量同步无断点续传 | 全量从头跑到尾，进程被杀要重来（增量已有逐批水位） | 按主键范围分段持久化位点 |
| 同步条数为乐观计数 | `total += batch.size()`，未校验目标端实际写入行数 | 累加 `executeBatch()` 返回值，或比对写入前后行数差 |
| 密码明文存储 | `sys_user.password` 明文比对；`datasource_config.password` 明文存插件连接口令 | BCrypt 存用户口令；数据源口令加密 + 密钥管理 |
| 登录为演示级 | token 存服务内存，无过期机制；接口本身<b>未做鉴权拦截</b>（前端靠 localStorage 判断） | JWT + 拦截器统一校验 |
| 管理库连接未池化 | 每次同步新建 `DataSourcePool`，执行完关闭 | 复用 HikariCP 连接池 |

### 已解决的性能问题（阶段二）

| 项目 | 之前 | 现在 |
|---|---|---|
| 数据源列表（含 4 个库行数探测） | 17.2 s | **0.2 s** |
| 触发同步的 HTTP 返回 | 阻塞几十秒 | **9 ms**（异步） |
| 控制台后端 | Node 外挂进程 | Spring Boot 常驻服务 |

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
| [PHASE1-INCREMENT.md](PHASE1-INCREMENT.md) | 阶段一实施文档：增量同步闭环 |
| `sql/init.sql` | MySQL 建库 + 10 万行测试数据 |
| `sql/pg_init.sql` | PostgreSQL 建表 + 测试数据 |
| `sql/admin.sql` | 管理库四张表 + 初始数据 |
| `sql/migration_increment.sql` | 增量同步所需字段的迁移脚本 |
| `.env.example` | 环境变量模板（含全部可配置项） |
| `start.bat` | 一键启动脚本（自动加载 .env） |
| `mini-sync-server/.../static/login.html` | 登录页 |
| `mini-sync-server/.../static/index.html` | 控制台主页面 |

---

<div align="center">

**JDK 21** · **Spring Boot 3.3** · **MyBatis-Plus 3.5** · **MySQL 5.7** · **PostgreSQL 16** · **SQLite 3.45**

</div>
