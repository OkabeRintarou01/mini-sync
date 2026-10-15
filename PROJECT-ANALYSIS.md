# PROJECT-ANALYSIS.md

> 项目现状分析报告 · 生成时间：2025 年 · 分析范围：全仓库代码、Git 状态、文档体系

---

## 一、项目定位

mini-sync 是一个**轻量级异构数据源同步工具**，核心价值在三个方向：

1. **数据源抽象**：读写差异收敛到插件驱动层，新增数据源不改执行器代码
2. **增量同步正确性**：`(水位字段, 主键)` 复合游标保证不重不漏，失败回写目标端真实水位
3. **插件化扩展**：驱动为独立 jar，放入 `plugins/` 即被识别，主工程零改动

---

## 二、技术栈

| 依赖 | 版本 | 用途 |
|---|---|---|
| Java | 21 | 主语言 |
| Maven | 3.9 | 多模块构建 |
| Spring Boot | 3.3.5 | 常驻 Web 控制台服务 |
| MyBatis-Plus | 3.5.7 | 管理库持久层 |
| Redis | 7 | 任务锁 / 运行进度 / 登录会话 |
| MySQL | 5.7 | 主库（源 / 目标 / 管理库） |
| PostgreSQL | 16 | 异构数据源（Docker） |
| SQLite | 3.45 | 第三方插件验证 |
| JDBC | — | 同步引擎数据访问（不经过 ORM） |
| SLF4J + Logback | — | 日志 |
| Lombok | 1.18 | 样板代码简化 |
| JUnit 5 | 5.10 | 单元测试 |
| Docker | — | PostgreSQL / Redis 容器 |

---

## 三、模块结构

| 模块 | 职责 | 关键类 |
|---|---|---|
| `mini-sync-spi` | 插件契约（零依赖） | `DataSourceDriver`、`DataSourceDriverProvider` |
| `mini-sync-plugin-loader` | 扫目录 + 独立 ClassLoader + SPI 注册 | `PluginDriverRegistry` |
| `mini-sync-connector` | 驱动工厂与连接缓存 | `DataSourceDriverFactory`、`DataSourcePool` |
| `mini-sync-job` | 同步引擎（纯 Java 无框架） | `SyncExecutionService`、`SyncJobExecutor` |
| `mini-sync-server` | Spring Boot 常驻 Web 控制台 | 15 个 REST 接口、Store 抽象层 |
| `mini-sync-plugins/*` | MySQL / PostgreSQL / SQLite 驱动 | 各自自包含，SQLite 不在主 reactor |

### 设计要点

- 新增一种数据源 = 实现 1 个 driver + 1 个 provider + 1 行 SPI 声明，主工程零改动
- 工厂（类型维度、不缓存）与连接池（数据源实例维度、按 ID 缓存）职责分离，避免 MySQL→MySQL 场景两端共用同一条连接
- 运行状态抽象 `RunStateStore` / `SessionStore`，各有内存与 Redis 两套实现，可配置切换

---

## 四、三条存储边界

| 数据 | 存哪 | 理由 |
|---|---|---|
| 数据源配置 / 任务 / 历史 / 水位 | **MySQL** | 持久业务数据，可审计 |
| 任务锁 / 运行进度 / 登录会话 | **Redis** | 多实例共享 + 可容忍过期 |
| 源库 / 目标库 | **插件驱动 + JDBC** | 运行时按类型加载任意驱动，不能经 ORM |

Redis Key 设计：锁 30 分钟 TTL、运行状态/日志 24h、会话 8h；释放锁用 Lua「值匹配才删」保证原子性。

---

## 五、实测性能

| 指标 | 数据 |
|---|---|
| MySQL → PostgreSQL 10 万条 | 1,525 ms |
| PostgreSQL → MySQL 10 万条 | 3,013 ms |
| MySQL → SQLite 10 万条 | 842 ms |
| 游标分页 vs limit offset（4 轮均值） | 2,233 ms vs 6,487 ms（约 2.9×） |
| 数据源列表行数探测 | 17.2 s → **0.2 s** |
| 触发同步 HTTP 返回 | 阻塞几十秒 → **9 ms**（异步） |

---

## 六、Git 现状

### 已提交、远端同步正常

`main` 分支与 `origin/main` 一致，提交链完整记录了功能演进：增量同步闭环 → Spring Boot 化 → Redis 引入跨实例状态。

### 本地改动（未提交）

`DynamicSyncBootstrap.java` 与 `start.bat` 有本地调试用改动（数据库口令改由本地配置直填）。口令属于敏感信息，这两处**保持本地不提交**。

### 被合理忽略的内容

`.env`（真实凭据）、`.idea/`、`target/`、`plugins/*.jar`（可由 `mvn package` 重建）、`data/`（SQLite 演示数据）。`.gitignore` 规则完整，没有遗漏需要补交的文件。

---

## 七、已知限制与改进方向

| 限制 | 改进方向 |
|---|---|
| 插件不支持热加载 | 常驻配置中心 + 监听变更 |
| 全量同步无断点续传 | 按主键范围分段持久化位点 |
| 同步条数为乐观计数 | 累加 `executeBatch()` 返回值 |
| 用户口令明文比对 | BCrypt 哈希存储 + JWT + 拦截器鉴权 |
| 数据源口令明文存储 | 加密存储 + 密钥管理 |
| 管理库连接未池化 | 引入 HikariCP |
| 无失败重试 | 重试工具类 + `sync_error_log` 表 |
| Redis 无降级 | 熔断 + 本地兜底缓存 |

---

## 八、文档体系

| 文档 | 定位 |
|---|---|
| README.md | 项目总览（架构 / 快速开始 / 实测数据 / 已知限制） |
| PLUGIN.md | 插件化机制详解与踩坑 |
| PERFORMANCE.md | 性能实测与原理 |
| PHASE1-INCREMENT.md / PHASE1-开发过程记录.md / PHASE2-开发过程记录.md | 分阶段实施与调试过程 |
| PROJECT-ANALYSIS.md | 本文件：项目现状分析 |
