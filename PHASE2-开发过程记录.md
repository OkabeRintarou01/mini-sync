# 阶段二开发过程全记录（小白版）

> 记录把控制台从 **Node.js 外挂** 改造成 **Spring Boot 常驻服务** 的完整过程。
> 包含 5 个真实 bug 的排查思路，以及为什么这样设计的取舍。
> 配套实施细节见各模块代码注释，性能数据见 [PERFORMANCE.md](PERFORMANCE.md)。

---

## 一、开工前：先想清楚"为什么要改"

### 1.1 问题的根源

阶段一做完后，控制台能用，但有个体验硬伤：**点什么都慢**。

实测：

| 操作 | 耗时 |
|---|---|
| 数据源列表（4 个库查行数） | **17.2 秒** |
| 触发一次同步 | HTTP 请求挂几十秒 |

### 1.2 慢在哪：不是代码写得差，是进程模型不对

看 Node 版的调用方式：

```js
// web/server.js（旧）
execFile(MVN, [
    '-B', '-q', '-pl', 'mini-sync-job', 'exec:java',
    '-Dexec.mainClass=com.minisync.job.SqlQueryBootstrap',
    ...
]);
```

**每次查询都启动一个新的 Maven + JVM。** 这就像每次有人问你一个问题，
你都要先重新开一家公司、招人、装修办公室，然后才回答问题。

耗时构成：

```
Maven 启动        ~8 秒   ← 完全浪费
Maven 依赖解析    ~4 秒   ← 完全浪费
JVM 启动          ~3 秒   ← 完全浪费
加载插件          ~1 秒
真正执行 SQL      ~0.1 秒  ← 这才是有效工作
```

**有效工作只占不到 1%。**

### 1.3 解决思路：改成常驻服务

```
改之前：浏览器 → Node 进程 → 启动 Maven → 启动 JVM → 干活 → 退出
改之后：浏览器 → Spring Boot 进程（一直活着）→ 直接干活
```

JVM 只启动一次，后续所有请求在同进程内完成。

### 1.4 三个需要提前定的决策

开工前我和你确认了三件事，因为它们决定返工成本：

| 决策 | 选项 | 选择 | 理由 |
|---|---|---|---|
| 怎么替换 Node | A 逐步替换 / B 一次性 | **A** | 中途出错时仍有可用系统可演示 |
| MyBatis-Plus 管哪些表 | 只读 / 全部 CRUD | **全部 4 张** | 正好 4 个 Mapper，职责清晰 |
| 触发同步 | A 异步 / B 同步阻塞 | **A** | 顺手解决"点一下等几十秒"的老问题 |

> 💡 **小白要点**：动大手术前，先确认"出错时怎么退回去"。
> 我们保留了 Node 版直到 Spring Boot 验证通过，并且全程在
> `phase2-springboot` 分支开发，`main` 一直可用。

---

## 二、第 1 步：搭起 Spring Boot 骨架

### 2.1 一个关键的技术决策

接 Spring Boot 有两种常见做法：

**做法 A：把根 pom 的 parent 改成 spring-boot-starter-parent**

```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
</parent>
```

**做法 B：用 import 引入 Spring Boot 的依赖版本表**

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-dependencies</artifactId>
      <version>${spring-boot.version}</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
```

**我选了 B。** 原因：

你的根 pom **已经是 6 个子模块的父工程**（`com.minisync:mini-sync`）。
如果再插一层 Spring Boot 父工程，会出现两个问题：

1. 现有 `dependencyManagement` 与 Spring Boot 的版本表可能冲突
2. 插件模块的 `<parent>` 继承关系要跟着改，容易出错

**`import` 方式只借用"版本管理"，不改继承结构**，对多模块项目更安全。

> 💡 **小白要点**：`<parent>` 是"认爹"（改变继承链），
> `<scope>import</scope>` 是"借书"（只借内容不改变关系）。
> 已经有自己父工程的多模块项目，用"借书"更合适。

### 2.2 创建模块

```
mini-sync-server/
├── pom.xml
└── src/main/
    ├── java/com/minisync/server/
    │   └── MiniSyncServerApplication.java
    └── resources/
        └── application.properties
```

启动类：

```java
@EnableAsync   // 允许同步任务在后台线程跑
@SpringBootApplication
public class MiniSyncServerApplication {
    public static void main(String[] args) {
        SpringApplication.run(MiniSyncServerApplication.class, args);
    }
}
```

配置文件（`application.properties`）：

```properties
spring.application.name=mini-sync-server
server.port=${MINISYNC_SERVER_PORT:8081}

# 注意：库名是 mini_sync_admin，不是 mini_sync
spring.datasource.url=jdbc:mysql://${MINISYNC_ADMIN_HOST:127.0.0.1}:${MINISYNC_ADMIN_PORT:3306}/${MINISYNC_ADMIN_DB:mini_sync_admin}?...
spring.datasource.username=${MINISYNC_DB_USER:root}
spring.datasource.password=${MINISYNC_DB_PWD}

mybatis-plus.configuration.map-underscore-to-camel-case=true
mybatis-plus.configuration.log-impl=org.apache.ibatis.logging.stdout.StdOutImpl
```

**端口选 8081 是刻意的**：与 Node 的 8090 并存，方便对比与回退。

### 2.3 第一个接口：健康检查

```java
@RestController
@RequestMapping("/api")
public class HealthController {
    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "UP", "service", "mini-sync-server");
    }
}
```

### 2.4 第一次启动踩的坑

执行 `mvn -pl mini-sync-server spring-boot:run`，等了两分钟没起来。

查日志发现：**Maven 在下载 `spring-boot-maven-plugin` 的额外依赖**。
首次运行的一次性成本，等它下载完就好了。

┌─ 排查方法 ─────────────────────────────────┐
│ 服务没起来时，先看两件事：                    │
│   1. 端口有没有在监听                        │
│      Get-NetTCPConnection -LocalPort 8081   │
│   2. 日志末尾是"下载依赖"还是"报错"           │
│      前者只需等，后者才要处理                 │
└────────────────────────────────────────────┘

启动成功后：

```
Tomcat started on port 8081 (http)
Started MiniSyncServerApplication in 1.401 seconds
```

访问 `http://localhost:8081/api/health` 返回：

```json
{"status":"UP","service":"mini-sync-server","time":"2026-10-15 00:02:45"}
```

---

## 三、第 2 步：抽出共用的同步服务（本阶段最关键的设计）

### 3.1 遇到的问题

原来"按 jobId 跑一次同步"的完整逻辑写在 `DynamicSyncBootstrap.main()` 里：

```java
public static void main(String[] args) {
    SyncJobEntity job = loadJob(jobId);          // 读配置
    DataSourceDriver source = pool.getDriver(...); // 建连接
    executor.executeFullSync(...);                // 执行
    saveWatermark(...);                          // 落水位
    System.exit(exitCode);                       // ← 问题在这
}
```

**Spring Boot 没法直接调用它**，因为：

1. 它会在结束时 `System.exit()` —— 常驻服务被这个方法调用会直接退出
2. 参数是 `String[] args` —— 命令行风格，不适合程序内调用
3. 逻辑和"读配置"混在一起 —— 而 Web 层读配置要用 MyBatis-Plus，不是 JDBC

### 3.2 解决：抽出可复用的 Service

新建 `mini-sync-job/src/main/java/com/minisync/job/service/SyncExecutionService.java`：

```java
public class SyncExecutionService {

    /** 执行结果 */
    public record ExecutionResult(boolean success, String syncType,
                                  int totalCount, long costMs,
                                  String watermark, String errorMsg) {}

    /** 水位落库回调（由调用方决定用 JDBC 还是 MyBatis-Plus） */
    @FunctionalInterface
    public interface WatermarkSaver {
        void save(long jobId, String water, long pk, boolean quiet);
    }

    public ExecutionResult execute(SyncJobEntity job,
                                   DataSourceConfig sourceCfg,
                                   DataSourceConfig targetCfg,
                                   String sourceType, String targetType,
                                   WatermarkSaver watermarkSaver) {
        // ① 按 sync_type 分发 ② 执行 ③ 落水位 ④ 返回结果
    }
}
```

**这个类只做"执行"，不关心配置从哪来、水位往哪写。**

### 3.3 两种入口共用它

```
命令行入口 DynamicSyncBootstrap
   ├── 读配置：JDBC
   ├── 水位落库：JDBC
   └── 执行：SyncExecutionService.execute(...)  ← 共用
                    ↑
Web 服务 SyncTaskService
   ├── 读配置：MyBatis-Plus
   ├── 水位落库：MyBatis-Plus
   └── 执行：SyncExecutionService.execute(...)  ← 共用
```

> 💡 **小白要点**：这叫"**依赖倒置**"的实用版本 ——
> 核心逻辑不依赖具体的数据访问方式，而是通过**回调接口**让调用方提供。
>
> 好处：如果命令行和网页各写一套同步逻辑，早晚会出现
> "网页跑出来 10 万条、命令行跑出来 99998 条"这种见鬼的问题。

### 3.4 重构后必须做的回归

改完立刻用命令行验证，确认行为**完全一致**：

```
改造前：起始水位=(last_offset=2026-10-14 23:10:27, last_pk=7299)
        同步条数: 0
        总耗时  : 40 ms

改造后：起始水位=(last_offset=2026-10-14 23:10:27, last_pk=7299)  ✅ 一样
        同步条数: 0                                              ✅ 一样
        总耗时  : 40 ms                                          ✅ 一样
```

日志来源从 `DynamicSyncBootstrap` 变成了 `SyncExecutionService`，
说明逻辑确实下沉了，但**外部行为零变化**。

---

## 四、第 3 步：MyBatis-Plus 接管管理库

### 4.1 先查真实表结构（重要习惯）

写 Entity 之前，我又执行了一次 `SHOW COLUMNS`。

**为什么要重复做这件事？** 因为阶段一就是靠这个发现了"水位字段类型写反"的危险。
Entity 的字段名和类型必须和真实表严格对应，凭印象写必然出错。

```sql
SHOW COLUMNS FROM sys_user;
SHOW COLUMNS FROM datasource_config;
SHOW COLUMNS FROM sync_log;
```

### 4.2 写 4 个 Entity

```java
@Data
@TableName("sync_job")
public class SyncJob {
    @TableId(type = IdType.AUTO)
    private Long id;

    private String jobName;        // ← 对应 job_name（下划线转驼峰）

    /** FULL 全量 / INCREMENT 增量 */
    private String syncType;

    private String incrementField;

    /** 水位字段值（时间戳字符串）→ 数据库里是 VARCHAR */
    private String lastOffset;

    /** 主键值（数字）→ 数据库里是 BIGINT */
    private Long lastPk;

    private LocalDateTime lastSyncTime;
    // ...
}
```

**字段名用驼峰，靠配置自动映射到 `job_name` 这类列名：**

```properties
mybatis-plus.configuration.map-underscore-to-camel-case=true
```

### 4.3 4 个 Mapper（几乎是空的）

```java
@Mapper
public interface SyncJobMapper extends BaseMapper<SyncJob> {
}
```

继承 `BaseMapper` 就能白拿到单表 CRUD，不用手写 SQL。

### 4.4 验证映射是否真的生效

**这里有个隐蔽的坑**：如果 `map-underscore-to-camel-case` 配错，
表现不是报错，而是**能连上数据库但取出来的字段全是 null**。

所以我专门写了个接口来抽查：

```
GET /api/health/db-sample

dsName       = 本地源库          ← ds_name 映射成功
dsType       = MYSQL
databaseName = mini_sync_src     ← database_name 映射成功
jobName      = MySQL 源库 → 目标库
incrementField = null
```

**另外发现一个问题**：`createTime` 返回的是 `2026-10-14T01:01:16`（带 `T`）。

这正是阶段一在水位落库上踩过的坑 —— Java 8 的 `LocalDateTime`
默认被 Jackson 序列化成 ISO-8601 格式。加一个配置统一修掉：

```java
@Configuration
public class JacksonConfig {
    private static final String PATTERN = "yyyy-MM-dd HH:mm:ss";

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer jsonCustomizer() {
        return builder -> {
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern(PATTERN);
            JavaTimeModule module = new JavaTimeModule();
            module.addSerializer(LocalDateTime.class, new LocalDateTimeSerializer(fmt));
            module.addDeserializer(LocalDateTime.class, new LocalDateTimeDeserializer(fmt));
            builder.modules(module);
        };
    }
}
```

修好后：`2026-10-14 01:01:16` ✅

> 💡 **经验复用**：同一个坑（LocalDateTime 格式）在两个阶段各踩了一次。
> 第一次在水位落库，第二次在 JSON 序列化。**记住这个类型的特点**：
> 它的 `toString()` 和默认 JSON 输出都带 `T`，需要显式格式化。

---

## 五、第 4 步：迁移 15 个接口（分批）

### 5.1 为什么分批

15 个接口一次做完，出问题时**分不清是哪一批引起的**。所以分 4 批，
每批迁完立刻验证：

| 批次 | 接口 | 数量 |
|---|---|---|
| 批 1 | login / logout | 2 |
| 批 2 | datasource list/save/delete/test | 4 |
| 批 3 | job list/save/delete/resetwatermark | 4 |
| 批 4 | run / run/status / logs / preview / truncate / drivertype | 6 |

### 5.2 关键原则：让后端适配前端

Node 版的返回格式是 `{code, msg, data}`：

```js
// 前端 index.html 里的判断
const r = await fetch('/api/xxx').then(x => x.json());
if (r.code === 200) { ... } else { toast(r.msg) }
```

如果我给 Spring Boot 设计一套新的返回格式（比如 RESTful 风格），
前端所有页面都要改。

**所以做了一个统一响应类，保持格式完全一致**：

```java
public final class R {
    public static Map<String, Object> ok(String msg) { ... }
    public static Map<String, Object> data(Object data) { ... }
    public static Map<String, Object> fail(int code, String msg) { ... }
    // 统一输出 {code, msg, data}
}
```

这样**前端一行都不用改**，只改端口。

### 5.3 批 2 遇到的第一个 bug：插件目录找不到

迁完数据源接口后测试，发现 SQLite 数据源显示离线：

```
未实现的数据源类型: SQLITE。已注册类型: []
排查方向：plugins 目录(D:\mini-sync\mini-sync-server\plugins)下未发现驱动 jar
```

**注意那个路径**：`D:\mini-sync\mini-sync-server\plugins` —— 它少了一层，
应该是 `D:\mini-sync\plugins`。

**原因**：

```java
// PluginDriverRegistry 里
Paths.get("plugins").toAbsolutePath()
```

**相对路径是相对"进程工作目录"解析的。** 而启动命令是：

```powershell
mvn -pl mini-sync-server spring-boot:run
```

Maven 会把工作目录设为**模块目录** `mini-sync-server/`，
于是去找 `mini-sync-server/plugins`（不存在）。

**修复**：写一个按优先级解析的函数

```java
private static String resolvePluginDir() {
    // ① 显式系统属性（部署时用）
    String explicit = System.getProperty(PLUGIN_DIR_PROPERTY);
    if (explicit != null && !explicit.trim().isEmpty()) return explicit.trim();

    // ② 当前工作目录下的 plugins/
    Path cwdPlugins = Paths.get("plugins").toAbsolutePath().normalize();
    if (Files.isDirectory(cwdPlugins)) return cwdPlugins.toString();

    // ③ 从 class 文件位置逐级向上找（兼容 IDE 与 Maven 两种启动方式）
    Path p = Paths.get(MyClass.class.getProtectionDomain()
            .getCodeSource().getLocation().toURI()).normalize();
    for (int i = 0; i < 4 && p != null; i++) {
        Path candidate = p.resolve("plugins");
        if (Files.isDirectory(candidate)) return candidate.toString();
        p = p.getParent();
    }
    return "plugins";   // 兜底
}
```

修好后：

```
驱动插件目录: D:\mini-sync\plugins
已注册驱动: type=SQLITE, provider=..., jar=minisync-sqlite-plugin-1.0.0.jar
GET /api/drivertype/list → MYSQL, POSTGRESQL, SQLITE
```

> 💡 **小白要点**：**相对路径是相对"工作目录"，不是相对"项目根"**。
> 从 IDE 运行、从 Maven 运行、打包后运行，工作目录可能都不一样。
> 需要稳定定位文件时，用"显式配置 + 多级兜底查找"。

### 5.4 批 2 的第二个 bug：@RequestParam 报 500

数据源列表正常了，但 `/api/datasource/test?id=1` 报 500：

```
java.lang.IllegalArgumentException:
  Name for argument of type [java.lang.Long] not specified,
  and parameter name information not available via reflection.
  Ensure that the compiler uses the '-parameters' flag.
```

**原因**：我写的是

```java
public Map<String, Object> test(@RequestParam Long id) { ... }
```

**没写参数名**，Spring 只能靠**反射读取编译后的参数名**来绑定 `?id=1`。
而 Java 默认**不在字节码里保留参数名**。

**两层修复**（都做了，双保险）：

1. 在父 pom 打开编译开关：

```xml
<plugin>
    <artifactId>maven-compiler-plugin</artifactId>
    <configuration>
        <parameters>true</parameters>
    </configuration>
</plugin>
```

2. 给注解补上显式名字：

```java
public Map<String, Object> test(@RequestParam("id") Long id) { ... }
```

> 💡 **小白要点**：这是 Spring Boot 项目的常见坑。
> **最稳的写法是始终在注解里写显式名字**：
> `@RequestParam("id")` / `@PathVariable("id")`。
> 这样即使编译参数变了也不会踩坑。

### 5.5 批 3 的 bug：重置水位没生效

`/api/job/resetwatermark` 返回成功，但查数据库发现**水位还在**。

**原因**：我用了 MyBatis-Plus 的 `updateById`：

```java
SyncJob patch = new SyncJob();
patch.setId(id);
patch.setLastOffset(null);   // 想置空
jobMapper.updateById(patch); // ❌ 没生效
```

**因为 `updateById` 会忽略 null 字段**（默认策略 `NOT_NULL`）。
这是 MyBatis-Plus 的默认行为：它认为 null 是"这个字段不用更新"。

**修复**：改用 `LambdaUpdateWrapper.set` 显式指定

```java
jobMapper.update(null, new LambdaUpdateWrapper<SyncJob>()
        .eq(SyncJob::getId, id)
        .set(SyncJob::getLastOffset, null)      // ✅ 显式写 NULL
        .set(SyncJob::getLastPk, 0L)
        .set(SyncJob::getLastSyncTime, null));
```

修复后验证：

```
重置前: last_offset=[2026-10-14 23:10:27]  last_pk=7299
重置后: last_offset=[NULL]                 last_pk=0    ✅
```

> 💡 **小白要点**：ORM 框架的"便利方法"往往有隐含策略。
> `updateById` 忽略 null 是为了防止"误清空字段"，
> 但当你**确实想清空**时，就必须用显式的 UpdateWrapper。
>
> 这个坑在阶段一也出现过一次（当时用 JDBC 直接写 SQL，没这问题）。
> **同一个需求，两种实现方式，不同的坑。**

### 5.6 批 4：异步执行改造

这是本阶段最有价值的改动。

**改造前**（Node 版）：

```js
const d = await fetch('/api/run?jobId=' + jobId).then(x => x.json()).data;
// ↑ 这个 await 会等 10 万条同步跑完，几十秒
```

**改造后**：

```js
// 第一步：提交，立刻拿到 runId（约 10ms）
const submit = await fetch('/api/run?jobId=' + jobId).then(x => x.json());
const runId = submit.data.runId;

// 第二步：轮询进度
setInterval(() => pollRunStatus(runId, jobId, btn), 700);
```

后端配套：

```java
@GetMapping("/run")
public Map<String, Object> run(@RequestParam("jobId") Long jobId) {
    // ① 检查并发锁（防止同一任务被点两次）
    RunState st = runStateService.create(jobId, job.getJobName(), syncType);
    if (!runStateService.tryLockJob(jobId, st.getRunId())) {
        return R.fail(409, "该任务正在执行中");
    }
    // ② 提交到后台线程池，立刻返回
    syncTaskService.executeAsync(jobId, st.getRunId());
    return R.data(Map.of("runId", st.getRunId(), "status", "RUNNING"));
}
```

并发锁的实现（关键在原子性）：

```java
public boolean tryLockJob(Long jobId, String runId) {
    // putIfAbsent 是原子操作：
    // 两个请求同时来，只有一个能成功放入 → 只有一个能执行
    return runningByJob.putIfAbsent(jobId, runId) == null;
}
```

实测效果：

| 场景 | 结果 |
|---|---|
| 触发接口返回 | **9 ms** |
| 不改动再触发 | 0 条 / 13 ms |
| 改 150 行 | 150 条 / 21 ms |
| 连点两次 | 第 1 次 200，第 2 次 **409** |

> 💡 **小白要点**：**"提交"和"执行"要分开**。
> HTTP 请求应该只负责"把任务放进队列"，不该负责"等任务跑完"。
> 这是所有长任务系统的通用设计。
>
> 判断并发锁是否正确的标准：**必须用原子操作**
> （`putIfAbsent` / `setIfAbsent`），
> 不能写成 `if (!contains) { put(); }` —— 那中间有窗口期。

### 5.7 一个容易忽略的点：@Async 为什么有时不生效

`@Async` 依赖 **Spring 的动态代理**。如果这样写：

```java
@Service
public class SyncTaskService {
    public void submit() {
        this.executeAsync(...);   // ❌ 同类内部调用，绕过代理 → 变成同步执行
    }
    @Async
    public void executeAsync(...) { ... }
}
```

**内部调用不会走代理，`@Async` 失效**，方法会同步执行。

所以 `executeAsync` 必须是 `public`，且**由外部 Bean（Controller）调用**：

```java
// RunController 里
syncTaskService.executeAsync(jobId, st.getRunId());   // ✅ 经过代理
```

> 💡 **小白要点**：`@Async`、`@Transactional`、`@Cacheable`
> 这类**基于 AOP 代理的注解**都有这个限制：
> **同类内部调用不生效**。这条规则适用于所有 Spring AOP 注解。

---

## 六、第 5 步：前端改造与 Node 退役

### 6.1 前端放哪

改之前前端在 `web/`（Node 提供），改之后要由 Spring Boot 提供。

标准做法：放到 `src/main/resources/static/`，Spring Boot 会自动作为静态资源提供。

```powershell
Copy-Item "web\index.html" "mini-sync-server\src\main\resources\static\index.html"
Copy-Item "web\login.html" "mini-sync-server\src\main\resources\static\login.html"
```

验证：

```
GET /            → HTTP 200  44980 bytes
GET /index.html  → HTTP 200  44980 bytes
GET /login.html  → HTTP 200  6112 bytes
```

### 6.2 前端只改了一处

因为接口路径与返回格式都没变，前端只需要改执行同步的逻辑
（同步阻塞 → 异步轮询）：

```js
// 旧：等同步跑完
const d = await fetch('/api/run?jobId=' + jobId).then(x => x.json()).data;

// 新：提交 + 轮询
const submit = await fetch('/api/run?jobId=' + jobId).then(x => x.json());
const runId = submit.data.runId;
runPollTimer = setInterval(() => pollRunStatus(runId, jobId, btn), 700);
pollRunStatus(runId, jobId, btn);   // 立即拉一次，避免首屏空等
```

轮询函数要处理三种状态：

```js
async function pollRunStatus(runId, jobId, btn) {
    const d = (await fetch('/api/run/status?runId=' + runId).then(x => x.json())).data;

    // ① 还在跑 → 显示进度，继续轮询
    if (d.running) { 显示蓝色进度条; return; }

    // ② 结束 → 停止轮询
    clearInterval(runPollTimer);

    // ③ 成功 / 失败分别渲染
    if (d.status === 'SUCCESS') { 绿色成功框 }
    else { 红色失败框 }
}
```

### 6.3 迁完后核对前端路径

**这一步很重要**：前端用的路径必须和后端提供的严格一致。

我写了个脚本提取前端所有 `fetch('/api/...')` 路径，逐个探测：

```
✅ /api/datasource/delete      HTTP 400   (缺参数，符合预期)
✅ /api/datasource/list        HTTP 200
✅ /api/drivertype/list        HTTP 200   ← 这里发现过 bug
✅ /api/job/list               HTTP 200
✅ /api/run                    HTTP 200
...
未匹配路径: 0 个
```

**这个检查抓到一个真 bug**：前端调用的是 `/api/drivertype/list`，
而我最初实现的是 `/api/datasource/drivertypes` —— **路径对不上，下拉框会空**。

修复方式：**让后端适配前端**，新建一个 Controller 用前端期望的路径：

```java
@RestController
@RequestMapping("/api/drivertype")
public class DriverTypeController {
    @GetMapping("/list")
    public Map<String, Object> list() { ... }
}
```

> 💡 **小白要点**：迁移时**不要顺手改接口路径**。
> 保持路径不变，前端就一行不用动；改了路径，所有调用点都要跟着改，
> 而且很容易漏掉——漏掉的那个只有用户点到时候才会发现。

### 6.4 全量接口验收

写了 31 项断言的验收脚本，覆盖 15 个接口的正常与异常路径：

```
【1/15】POST /api/login
  ✅ 登录成功返回 token
  ✅ 错误密码返回 401
  ✅ 空请求体返回 400
...
【12/15】GET /api/run （异步）
  ✅ 触发接口立即返回 runId  — 耗时 9ms
  ✅ 返回耗时 < 500ms（证明是异步）
  ✅ 同任务并发触发被拒（409）
...
==========================================
  通过 31 项，失败 0 项
==========================================
```

### 6.5 Node 退役

验收全过后才删 Node：

```powershell
Get-NetTCPConnection -LocalPort 8090 | ForEach-Object { Stop-Process -Id $_.OwningProcess -Force }
git rm -r web
```

**注意 git 的输出**：

```
R  web/index.html -> mini-sync-server/src/main/resources/static/index.html
R  web/login.html -> mini-sync-server/src/main/resources/static/login.html
D  web/server.js
```

`R` 表示 rename（git 识别出这是移动而非删除重建），
`D` 表示 delete。这样 git 历史更清晰，也能追溯文件演变。

> 💡 **小白要点**：删代码前先确认新实现完全覆盖了旧功能。
> 我们的验收清单（15 个接口 + 31 项断言）就是"覆盖证明"。
> 另外全程在分支上做，`main` 一直保留可用版本。

---

## 七、最终成果

### 7.1 性能对比

| 场景 | Node 版 | Spring Boot 版 | 提升 |
|---|---|---|---|
| 数据源列表（4 库行数探测） | 17.2 s | **0.2 s** | **86×** |
| 触发同步 HTTP 返回 | 阻塞几十秒 | **9 ms** | — |
| SQLite 等插件类型取数 | ~17 s | 毫秒级 | — |
| 并发触发保护 | 无 | 409 拒绝 | 新增能力 |

### 7.2 交付清单

| 类别 | 内容 |
|---|---|
| 新增模块 | `mini-sync-server`（Spring Boot 3.3.5） |
| 新增接口 | 15 个（认证 2 / 数据源 4 / 任务 4 / 执行 5） |
| 新增 Service | `SyncExecutionService`（共用同步逻辑）等 6 个 |
| 持久层 | 4 个 Entity + 4 个 Mapper（MyBatis-Plus 3.5.7） |
| 前端 | 移入 static/，执行页改为异步轮询 |
| 启动脚本 | `start.bat`（自动加载 .env → 编译 → 启动） |
| 测试 | 4 个 Spring Boot 集成测试（+ 阶段一 7 个 = 11 个） |
| 移除 | `web/`（Node 实现，功能已完全覆盖） |

### 7.3 本次修复的 5 个 bug

| # | 现象 | 根因 | 修复 |
|---|---|---|---|
| 1 | SQLite 报"未注册类型" | 相对路径按工作目录解析，Maven 启动时是模块目录 | 多级兜底查找插件目录 |
| 2 | `/api/datasource/test` 返回 500 | `@RequestParam` 未写显式名，字节码无参数名 | 开 `-parameters` + 补显式名 |
| 3 | 重置水位没生效 | `updateById` 忽略 null 字段 | 改用 `LambdaUpdateWrapper.set` |
| 4 | 时间返回带 `T` | Jackson 默认 ISO-8601 序列化 `LocalDateTime` | `JacksonConfig` 统一格式 |
| 5 | 驱动类型下拉为空 | 前端路径 `/api/drivertype/list` 与后端实现不一致 | 让后端适配前端路径 |

---

## 八、遗留问题（面试可主动讲）

1. **运行状态存内存**
   `SyncRunStateService` 用 `ConcurrentHashMap`，**服务重启后进度会丢**，
   多实例部署也读不到彼此状态。生产要换 Redis ——
   届时只需替换这一个类的实现，Controller 和前端不用改。

2. **管理库连接未池化**
   每次同步新建 `DataSourcePool`，执行完关闭。
   常驻服务里这仍是毫秒级（相比阶段一的 17 秒已质变），
   但正确做法是复用 HikariCP。

3. **接口未做鉴权拦截**
   登录逻辑是有的，token 也发了，但**后端接口本身不校验 token** ——
   前端靠 localStorage 判断。生产要加拦截器统一校验。

4. **全量同步仍无断点续传**
   只有增量任务有逐批水位。全量的断点需要按主键范围分段持久化。

---

## 九、给你（小白）的复盘要点

### 1️⃣ 慢的时候先问"慢在哪个环节"

17 秒里有效工作只占 0.1 秒 —— **改代码优化是没用的，要改进程模型。**
我做的第一件事是拆解耗时构成，而不是去优化 SQL。

### 2️⃣ 核心逻辑只写一份

命令行和 Web 各自实现一遍同步逻辑，早晚会不一致。
用**回调接口**把"变化的部分"（配置从哪读、水位往哪写）交给调用方，
核心逻辑保持单一实现。

### 3️⃣ 迁移时让后端适配前端

保持接口路径与返回格式不变，前端就零改动。
改了路径看着更"规范"，但会引入大量回归风险，且漏掉的地方很难发现。

### 4️⃣ 分批迁移 + 每批验收

15 个接口分 4 批，每批迁完立刻测。
出问题时能立刻定位到是哪一批，而不是面对一坨改动猜。

### 5️⃣ 开发时留意"框架的隐含策略"

`updateById` 忽略 null、`@Async` 内部调用失效、
`LocalDateTime` 带 T、`@RequestParam` 缺参数名 ——
这些都不是 bug，是框架的设计选择。
**踩一次就要记住，因为它们会在不同项目里反复出现。**

### 6️⃣ 相对路径要小心

"相对"是相对**工作目录**，而工作目录随启动方式变化
（IDE / Maven / 打包运行各不相同）。
需要稳定定位时，用"显式配置 + 多级兜底"。

---

## 附：常用命令速查

```powershell
# ---------- 启动（推荐）----------
cd D:\mini-sync
.\start.bat                 # 自动加载 .env 并启动
.\start.bat --build         # 先全量编译再启动

# ---------- 手动启动 ----------
Get-Content .env | ForEach-Object {
  if ($_ -match '^\s*([^#=]+)\s*=\s*(.*)$') {
    [Environment]::SetEnvironmentVariable($matches[1].Trim(), $matches[2].Trim(), 'Process')
  }
}
$env:JAVA_HOME = "D:\jdk"
mvn -B -pl mini-sync-server spring-boot:run
# 打开 http://localhost:8081

# ---------- 停止 ----------
Get-NetTCPConnection -LocalPort 8081 -State Listen -ErrorAction SilentlyContinue |
  ForEach-Object { Stop-Process -Id $_.OwningProcess -Force }

# ---------- 编译 + 测试 ----------
mvn -B clean install              # 9 个模块 + 11 个测试

# ---------- 健康检查 ----------
curl.exe http://localhost:8081/api/health

# ---------- 命令行跑同步（调试用，日志最全）----------
mvn -B -pl mini-sync-job exec:java `
  "-Dexec.mainClass=com.minisync.job.DynamicSyncBootstrap" "-Dexec.args=6"
```
