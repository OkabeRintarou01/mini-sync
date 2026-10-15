# mini-sync

轻量级异构数据源同步工具。在不同类型的数据库之间搬数据（MySQL / PostgreSQL / SQLite 任意组合），驱动以独立 jar 形式放入 `plugins/` 目录即被识别；增量同步用「水位 + 主键」复合游标保证不重不漏，失败时回写目标端真实水位，并配一个可视化控制台统一管理数据源与同步任务。

## 安装

**前置**：JDK 21+、Maven 3.9+、一个可用的 MySQL 5.7+（管理库）。PostgreSQL / Redis 用 Docker 起即可，均非必需。首次同步还需一个**已登录**的 MySQL 实例来建管理库与测试数据。

**凭据**：数据库账号口令从环境变量或 `.env` 文件注入，仓库不提供可用密码。先复制模板并填写：

```powershell
Copy-Item .env.example .env
notepad .env
# 至少填写 MINISYNC_DB_PWD（MySQL 密码）
# 用 PG 时还需填写 MINISYNC_PG_PWD
```

> **推送到公开仓库前务必检查**：`mini-sync-server/src/main/resources/application.properties` 与 `DynamicSyncBootstrap.java` 为了本地开发便利，当前内联了管理库口令的默认值。这两个文件**在版本控制中**，公开发布前必须改回 `${MINISYNC_DB_PWD}` / 环境变量形式，并**轮换掉已暴露的口令**（改 Git 历史救不回已泄露的凭据）。

**推荐：把安装交给本机已有的 Agent**——任何具备终端与文件操作能力的助手（Codex、Claude Code、Cursor 等）都可以，把下面这段话原样发给它：

```text
请帮我安装并跑通 mini-sync（项目地址：https://github.com/SWR-Arch/mini-sync）：
先阅读项目 README，确认我的 JDK 与 Maven 实际路径，并按 .env.example 复制出 .env、等我填写数据库口令；
再依次执行：① 用 sql/init.sql 建源库与目标库并造 10 万行测试数据，② 用 sql/admin.sql 建管理库 schema 与演示用户，③ mvn -B clean install -DskipTests 编译并把驱动插件打进 plugins/ 目录，④ 启动 mini-sync-server；
任何本地文件或配置改动，先征得我同意并做好备份；
装完后打开 http://localhost:8081，用 admin / admin123 登录，并在控制台新建一个数据源、一个同步任务，陪我跑通一次全量同步，确认目标表行数与源表一致；
若插件目录没被识别（表现为「未实现的数据源类型」），请如实报告并检查 plugins/ 下是否已有三个插件 jar，不要通过硬编码绕过。
```

环境与版本速查（完整对应关系见文末「版本兼容与历史版本」）：

| 组件 | 版本要求 |
|---|---|
| JDK | 21+（编译目标 21） |
| Maven | 3.9+ |
| Spring Boot | 3.3.x（要求 JDK 17+） |
| MySQL | 5.7+（管理库与 MySQL 数据源） |
| Redis | 7（可选，缺省走内存实现） |

<details>
<summary>手动安装（命令行）</summary>

```powershell
# 1. 准备测试数据（先按上文建好并填写 .env）
Get-Content D:\mini-sync\sql\init.sql  -Raw | & $env:MINISYNC_MYSQL_BIN -u$env:MINISYNC_DB_USER -p
Get-Content D:\mini-sync\sql\admin.sql -Raw | & $env:MINISYNC_MYSQL_BIN -u$env:MINISYNC_DB_USER -p

# 2. 编译（会在打包驱动插件的同时把 jar 拷到 plugins/）
$env:JAVA_HOME = "D:\jdk"
mvn -B clean install -DskipTests

# 3. 启动常驻服务（会自动读取项目根目录的 .env）
mvn -B -pl mini-sync-server spring-boot:run
```

</details>

> **Redis 是可选的**：`minisync.redis.enabled` 默认 `true`，改为 `false` 即退回进程内存实现，零外部依赖即可运行。区别只在多实例与重启场景——内存版重启丢进度、多实例互相看不见。

> **插件目录**：服务端启动时会自动定位 `plugins/`（优先系统属性 `-Dminisync.plugin.dir`，其次工作目录，再从 class 位置逐级向上找）。若用 IDE 或 `mvn -pl mini-sync-server` 启动而看到「未实现的数据源类型: SQLITE，已注册类型: []」，说明插件目录解析到了模块目录——把工作目录设到项目根，或用 `-Dminisync.plugin.dir` 显式指定。

**验证**：访问 `http://localhost:8081`，左侧「数据源管理」能看到已建数据源及其**实时行数与驱动来源**（来自哪个插件 jar），「同步任务」里新建一个任务并点「执行」，在「运行日志」页能看到逐批推进的日志、最终输出同步条数与耗时，且目标表行数与源表一致，才算安装成功。

## 功能

- **插件化加载数据源**：按固定契约开发 driver，打成 jar 放入 `plugins/` 目录即被识别，**主工程零改动、零重新编译**。驱动之间用独立 ClassLoader 隔离，各自的 JDBC 版本互不冲突。

- **异构互相同步**：MySQL 与 PostgreSQL 可任意组合（双向验证通过），并已用 SQLite 作为**独立第三方插件**跑通 10 万条。

- **增量同步不重不漏**：采用 `(水位字段, 主键)` 复合游标，严格在全序上单调推进。单字段水位在「同一时间戳多行且跨批次」时必然要么丢数据、要么重复读。

- **失败回写目标端水位**：批次写入失败时回查目标端真实进度，而不是沿用源端水位。这保证 **at-least-once**——宁可重复也不丢失，配合 upsert 幂等，重跑无副作用。

- **游标分页性能优化**：10 万条同步总耗时由 6.5 秒降至 2.2 秒；单批查询耗时恒定在个位数毫秒，不随翻页位置劣化。

- **可视化控制台**：数据源管理（含实时行数探测与测试连接）、同步任务、一键执行、执行历史、运行日志。数据源类型下拉**不写死**，由引擎动态提供——新增插件类型后下拉自动多一项。

- **可切换的共享状态**：运行状态与登录会话都有内存 / Redis 两套实现，由配置切换。单机开发免装 Redis，多实例部署改一行配置即获得跨实例能力（重启不丢状态、任何实例都能读进度、并发触发返回 409）。

## 复合游标为什么这样设计

增量同步的正确性是这个项目最需要想清楚的地方。

若只按 `update_time > 水位` 翻页，当同一时间戳存在多行、并且跨批次时，用 `>` 会跳过尚未读取的同行，用 `>=` 又会重复读取同一批——两条路都是错的。这类问题不会立刻报错，往往表现为「任务卡死」或「数据错乱」，排查成本很高。

因此把主键作为水位的**第二排序键**与**第二过滤条件**：

```sql
WHERE (update_time > ?) OR (update_time = ? AND id > ?)
ORDER BY update_time ASC, id ASC LIMIT ?
```

因为 `id` 唯一，任意两行的 `(update_time, id)` 组合不可能完全相同，这构成一个**严格全序**；游标 `(water, pk)` 在其上单调前进，所以既不重也不漏。

配套还有两个细节：**水位值必须显式格式化**——MySQL Connector/J 8+ 对 DATETIME 列默认返回 `LocalDateTime`，其 `toString()` 会产出 `2026-01-01T00:00`（带 ISO 的 `T`，且**秒被截掉**），直接回填进 SQL 会让同一分钟内的数据被算作水位相同，跨分钟即出错；**水位的两个分量类型不能互换**——`last_offset` 存时间戳字符串（VARCHAR），`last_pk` 存主键（BIGINT）。

失败处理上，本项目**回查目标端 `MAX(水位字段)`** 作为下次起点。若沿用源端水位，本批「已从源端读出、但写入目标端失败」的数据会在下次被直接跳过，造成**永久丢失**。回写目标端进度后，本批被重新读取、重新写入，配合 upsert 幂等即是 at-least-once。

## 命令行

命令行与网页走的是**同一份同步逻辑**（`SyncExecutionService`），区别只在触发方式与水位落库的实现（JDBC vs MyBatis-Plus）。

```powershell
# 先加载 .env（命令行进程不会自动读 .env）
Get-Content D:\mini-sync\.env | ForEach-Object {
  if ($_ -match '^\s*([^#=]+)\s*=\s*(.*)$') {
    [Environment]::SetEnvironmentVariable($matches[1].Trim(), $matches[2].Trim(), 'Process')
  }
}

# 按 jobId 跑一次同步（jobId 在控制台任务列表里看）
mvn -B -pl mini-sync-job exec:java "-Dexec.mainClass=com.minisync.job.DynamicSyncBootstrap" "-Dexec.args=6"

# 查看当前注册了哪些驱动类型
mvn -B -q -pl mini-sync-job exec:java "-Dexec.mainClass=com.minisync.job.DriverTypeLister"

# 其它验证入口
mvn -B -q -pl mini-sync-job exec:java "-Dexec.mainClass=com.minisync.job.SyncBootstrap" "-Dexec.args=full 1000"
mvn -B -q -pl mini-sync-job exec:java "-Dexec.mainClass=com.minisync.job.CrossDbBootstrap" "-Dexec.args=mysql2pg"
mvn -B -pl mini-sync-job exec:java "-Dexec.mainClass=com.minisync.job.PluginSyncBootstrap"
```

控制台提供 21 个 REST 接口，覆盖认证、数据源、任务、执行、历史与健康检查。统一响应结构为 `{code, msg, data}`。

## 已知限制

- **新增插件需重启进程**，未做热加载。JDK 9+ 起 `URLClassLoader` 不再是 `ClassLoader` 的公开子类型（21 仍可用），且 classloader 卸载要求「类、实例、加载器三者均不可达」，中途卸载易造成连接泄漏。现为启动时扫描一次。

- **全量同步无断点续传**。全量从头跑到尾，进程被杀要重来；增量已有逐批水位落库，不受影响。生产方案是按主键范围分段持久化位点。

- **同步条数为乐观计数**。`total += batch.size()`，未校验目标端实际写入行数。生产方案是累加 `executeBatch()` 返回值，或比对写入前后行数差。

- **口令为明文存储**。`sys_user.password` 明文比对，`datasource_config.password` 明文存插件连接口令（列表接口刻意不返回该字段，但库里仍是明文）。生产方案是 BCrypt 存用户口令 + 数据源口令加密与密钥管理。

- **登录为演示级**。接口本身**未做鉴权拦截**（前端靠 localStorage 判断），密码明文比对。生产方案是 JWT + 拦截器统一校验。

- **管理库连接未池化**。每次同步新建 `DataSourcePool`，执行完关闭。生产方案是复用 HikariCP。

- **无失败重试**。只有 upsert 幂等，没有自动重试与退避。生产方案是重试工具类 + `sync_error_log` 表。

- **Redis 未做降级**。Redis 挂了会让共享状态不可用（服务本身仍能启动）。生产方案是加熔断与本地兜底缓存。

- **第三方 upsert 语义需逐个验证**。MySQL / PG / SQLite 的 upsert 写法与批量重写参数各不相同（PG 必须显式指定冲突列，SQLite JDBC 的 `executeBatch` 历史支持不完整），不能假定 JDBC 行为一致。

- 已用 SQLite 插件在 Windows 本机跑通 10 万条，尚未在 Linux / macOS 上完成真机验证。

## 版本兼容与历史版本

主工程、插件与运行环境之间的版本需要匹配；编译会同时把驱动打成插件 jar 并拷到 `plugins/` 目录。

| 组件 | 版本 | 注意事项 |
|---|---|---|
| JDK | `21` | 编译目标 21；插件模块刻意不使用 Java 9+ API，JDK 8 下也可编译 |
| Spring Boot | `3.3.5` | 通过 `dependencyManagement` + `import` 引入，而非改父工程 |
| MyBatis-Plus | `3.5.7` | 仅用于管理库持久层，同步引擎不经 ORM |
| MySQL | `5.7`+ | 管理库 + MySQL 数据源；连接参数需 `rewriteBatchedStatements=true` |
| PostgreSQL | `16` | 异构数据源；连接参数需 `reWriteBatchedInserts=true` |
| SQLite | `3.45` | 第三方插件验证；`databaseName` 是 `.db` 文件路径而非库名 |
| Redis | `7` | 可选；`minisync.redis.enabled=false` 可完全不用 |

- **Redis 引入**：解决跨实例共享状态问题——任务锁、运行进度与日志、登录会话迁到 Redis，并保留内存实现可切换。
- **Spring Boot 3.3 + MyBatis-Plus**：控制台由 Node 外挂进程改为常驻 Web 服务，触发同步的 HTTP 返回由「阻塞几十秒」降到毫秒级，数据源列表（含 4 个库行数探测）由 17.2 秒降至 0.2 秒。
- **增量同步闭环**：水位落库 + 断点续传，复合游标替代单字段水位，失败回写目标端水位。
- **凭据外置**：数据库口令从硬编码改为环境变量注入，仓库只提交无密码值的 `.env.example`。
- **首个可用版本**：SPI 插件契约、驱动工厂与连接池、MySQL / PostgreSQL 驱动、命令行同步入口。



