# mini-sync 插件化驱动机制

> 目标：**支持自定义数据源，按固定规则开发 driver 放入 `plugins/` 目录即可使用**
> —— 主工程零改动，不需要重新编译 mini-sync 本身。

---

## 一、一句话说明

把每一种数据库变成一个**自包含的 jar 插件**，放进 `mini-sync/plugins/` 目录，
程序启动时扫描该目录、逐个用独立 ClassLoader 加载、通过 SPI 注册到工厂。
之后控制台的数据源类型下拉里就多出这一项，可以直接建数据源、配同步任务。

---

## 二、当前已注册的驱动

| 类型 | 显示名 | 插件 jar | 说明 |
|---|---|---|---|
| `MYSQL` | MySQL | `minisync-mysql-plugin-1.0.0.jar` | 含 JDBC 驱动，自包含 |
| `POSTGRESQL` | PostgreSQL | `minisync-postgresql-plugin-1.0.0.jar` | 含 JDBC 驱动，自包含 |
| `SQLITE` | SQLite | `minisync-sqlite-plugin-1.0.0.jar` | **独立第三方插件**，主工程不引用它的任何代码 |

> 最后一行是关键证据：SQLite 的代码**不在 mini-sync 的 reactor 里**，
> 它只共享父 pom 的依赖版本管理。删掉这个 jar，SQLite 就从系统里消失；
> 放回去，什么都不用改就能用。

---

## 三、目录结构

```
D:\mini-sync\
├── mini-sync-spi\                     ← 插件契约（零依赖！）
│   └── src/main/java/com/minisync/spi/
│       ├── DataSourceDriver.java         驱动接口（6 个方法）
│       ├── DataSourceConfig.java         连接配置
│       └── DataSourceDriverProvider.java provider 契约（唯一注册点）
│
├── mini-sync-plugin-loader\           ← 插件加载器
│   └── src/main/java/com/minisync/plugin/
│       ├── PluginDriverRegistry.java     扫描 plugins/ + 独立CL + SPI注册
│       └── LoadedPlugin.java             已加载插件的信息（类型/来源jar）
│
├── mini-sync-connector\               ← 工厂 + 连接池（不含任何具体驱动）
│   └── factory/DataSourceDriverFactory.java   插件优先，classpath 兜底
│
├── mini-sync-plugins\
│   ├── mysql-plugin\                   ← MySQL 驱动插件
│   ├── postgresql-plugin\              ← PostgreSQL 驱动插件
│   └── sqlite-plugin\                  ← SQLite 插件（不在主 reactor 中）
│
└── plugins\                            ← 打包产物落点（运行时扫描这个目录）
    ├── minisync-mysql-plugin-1.0.0.jar
    ├── minisync-postgresql-plugin-1.0.0.jar
    └── minisync-sqlite-plugin-1.0.0.jar
```

---

## 四、怎么开发一个新驱动（照抄即可）

以"接入 ClickHouse"为例，一共 4 个文件。

### 第 1 步：建 Maven 工程

```xml
<artifactId>minisync-clickhouse-plugin</artifactId>

<dependencies>
    <!-- 只依赖这一个 jar，这就是第三方需要的全部 -->
    <dependency>
        <groupId>com.minisync</groupId>
        <artifactId>mini-sync-spi</artifactId>
        <version>1.0.0</version>
    </dependency>
    <dependency>
        <groupId>com.clickhouse</groupId>
        <artifactId>clickhouse-jdbc</artifactId>
    </dependency>
</dependencies>
```

### 第 2 步：实现 `DataSourceDriver`

```java
public class ClickhouseDataSourceDriver implements DataSourceDriver {

    @Override public String getType() { return "CLICKHOUSE"; }

    @Override public void connect(DataSourceConfig config) {
        Class.forName("com.clickhouse.jdbc.ClickHouseDriver");
        this.connection = DriverManager.getConnection(
            "jdbc:ch://" + config.getHost() + ":" + config.getPort() + "/" + config.getDatabaseName(),
            config.getUsername(), config.getPassword());
    }

    @Override public List<Map<String,Object>> executeQuery(String sql, Object... params) { /* ... */ }

    @Override public int batchWrite(String table, List<Map<String,Object>> data) {
        // ClickHouse 没有 upsert，用 INSERT（或 ReplacingMergeTree 引擎）
        // ← 这就是"每个驱动必须单独适配"的地方
    }

    // queryMaxValue / testConnection / close ...
}
```

**必须注意的适配点**（不同数据库行为不一致，不能想当然）：

| 数据库 | Upsert 写法 |
|---|---|
| MySQL | `INSERT ... ON DUPLICATE KEY UPDATE c=VALUES(c)` |
| PostgreSQL | `INSERT ... ON CONFLICT (id) DO UPDATE SET c=EXCLUDED.c` |
| SQLite | `INSERT ... ON CONFLICT(id) DO UPDATE SET c=excluded.c` |
| ClickHouse | **没有**，靠 `ReplacingMergeTree` 表引擎去重 |
| Oracle | `MERGE INTO ... USING ... ON ... WHEN MATCHED THEN UPDATE` |

### 第 3 步：实现 provider（薄薄一层）

```java
public class ClickhouseDriverProvider implements DataSourceDriverProvider {
    @Override public String type() { return "CLICKHOUSE"; }

    /** 必须每次返回新实例 —— driver 持有 Connection，两端不能共享 */
    @Override public DataSourceDriver create(String type) { return new ClickhouseDataSourceDriver(); }

    @Override public String displayName() { return "ClickHouse"; }
}
```

### 第 4 步：写 SPI 声明文件

路径固定：`src/main/resources/META-INF/services/com.minisync.spi.DataSourceDriverProvider`

文件内容（一行一个全限定类名）：

```
com.minisync.plugin.clickhouse.ClickhouseDriverProvider
```

### 第 5 步：打包并放入 plugins 目录

```powershell
mvn clean package
copy target\minisync-clickhouse-plugin-1.0.0.jar D:\mini-sync\plugins\
```

**重启后生效**，控制台数据源类型下拉里会出现 `ClickHouse`。

---

## 五、验证机制是否真的工作

```powershell
cd D:\mini-sync
mvn -B -q -pl mini-sync-job exec:java "-Dexec.mainClass=com.minisync.job.DriverTypeLister"
```

输出示例：

```
DRIVER_TYPES_BEGIN
DRIVER_PLUGIN_DIR D:\mini-sync\plugins
DRIVER_TYPE MYSQL MySQL minisync-mysql-plugin-1.0.0.jar
DRIVER_TYPE POSTGRESQL PostgreSQL minisync-postgresql-plugin-1.0.0.jar
DRIVER_TYPE SQLITE SQLite minisync-sqlite-plugin-1.0.0.jar
DRIVER_CREATABLE MYSQL com.minisync.plugin.mysql.MysqlDataSourceDriver
DRIVER_CREATABLE POSTGRESQL com.minisync.plugin.postgresql.PostgresqlDataSourceDriver
DRIVER_CREATABLE SQLITE com.minisync.plugin.sqlite.SqliteDataSourceDriver
DRIVER_TYPES_END
```

也可以直接跑一个真实同步（MySQL → SQLite，SQLite 驱动来自插件）：

```powershell
mvn -B -pl mini-sync-job exec:java "-Dexec.mainClass=com.minisync.job.PluginSyncBootstrap"
```

实测输出：

```
源驱动类 = com.minisync.plugin.mysql.MysqlDataSourceDriver
目标驱动类 = com.minisync.plugin.sqlite.SqliteDataSourceDriver
同步条数: 100000
总耗时  : 842 ms
目标表实际行数: 100000（同步条数 100000，一致）
```

单元测试（7 个，覆盖契约与工厂行为）：

```powershell
mvn -B clean install -pl mini-sync-job -am -Dtest=PluginDriverRegistryTest -Dsurefire.failIfNoSpecifiedTests=false
```

---

## 六、实现要点与踩过的坑

### 6.1 SPI 声明必须从 jar 文件里读，不能用 `classloader.getResource()`

**现象**：插件注册成功，但"类型来源 jar"全变成最后一个被扫描的 jar。

**原因**：`job` 模块把 mysql/pg 插件**同时**作为运行时依赖引入了 classpath
（为了实现"plugins 目录被清空时仍能跑"的兜底）。
classloader 是**父优先**的，`loader.getResource(SERVICE_FILE)` 会先命中 classpath 上
那份同名文件，于是拿到的是 classpath 的 provider 列表。

**解法**：直接 `ZipFile` 开 jar，读自己内部的 services 文件。

```java
try (ZipFile zip = new ZipFile(jar.toFile())) {
    ZipEntry e = zip.getEntry("META-INF/services/" + DataSourceDriverProvider.class.getName());
    // 逐行读类名，用本 jar 的 classloader 加载
}
```

### 6.2 也不能用 `ServiceLoader.load(clazz, pluginLoader)`

同样受父优先委托影响，而且它会沿父链继续找，把 classpath 上的 provider 也报一份。
改成"读 jar 内声明文件 + 显式 `Class.forName(name, true, loader)`" 才可预测。

### 6.3 一处失败不影响整体

每个 jar 的加载包在独立 try-catch 里，失败只记入 `failures`：

```
DRIVER_FAILURE minisync-broken-plugin-1.0.0.jar: SPI 声明中的类不存在 - com.xxx.NoSuchDriver
```

其余插件照常注册，程序照常启动。已用"故意造一个指向不存在类的 jar"验证过。

### 6.4 provider 与 driver 必须分成两层

`DataSourceDriver` 是**有状态**的（持有一个 Connection），
`DataSourceDriverProvider` 是**无状态**的工厂。
如果省掉 provider 直接把 driver 注册进 SPI，`ServiceLoader` 会缓存 driver 实例，
源库/目标库同类型时共用同一个连接 —— 这正是早期踩过的坑。

### 6.5 工厂做了"类型一致性"校验

provider 声明 `type()=MYSQL`，但它返回的 driver `getType()=POSTGRESQL` 时直接抛错。
否则 `DataSourcePool` 按数据源 ID 缓存会张冠李戴（缓存的是 A 类型，取出来是 B 类型）。

---

## 七、取舍说明（要能诚实回答）

### 优点

- 新增数据源**不需要改主工程一行代码**，也不需要重新编译主工程
- 每个插件的 JDBC 驱动版本互相隔离（独立 ClassLoader）
- 插件 jar 自包含，拷走即用
- 单个坏插件不会让系统起不来

### 限制（面试被问到要直说）

1. **新增插件需要重启进程**。没有做热加载 —— JDK 9+ 起 `URLClassLoader` 已不是
   `ClassLoader` 的公开子类型，且 classloader 卸载要求"类、实例、加载器三者均不可达"，
   中途卸载极易造成连接泄漏。本项目的取舍是"启动时扫描一次"。
2. **classpath 上同时有一份 mysql/pg 插件**（兜底用）。
   代价是插件升级时两边都要更新；换来的是 plugins 目录误删时系统仍可用。
3. **驱动间没有做注册表的版本协商**。如果两个 jar 都声明 `type()=MYSQL`，
   按文件名字典序后注册者覆盖先注册者，并打 WARN。生产上应加版本号或来源优先级。

---

## 八、与 datalinkx 的做法对比

datalinkx 的 `DsDriverFactory` 也是 `URLClassLoader` + 反射，但它的定位方式是
**命名约定**：

```java
// datalinkx
private static final String DRIVER_DIST = "driver-dist";
String targetJarName  = String.format("datalinkx-driver-%s-1.0.0.jar", dsType.toLowerCase());
String targetClassName = PACKAGE_PREFIX + dsType + "driver." + PascalCase(dsType) + "Driver";
// → 找 datalinkx-driver-clickhouse-1.0.0.jar
// → 加载 com.datalinkx.clickhousedriver.ClickhouseDriver
```

| | datalinkx | mini-sync |
|---|---|---|
| 定位方式 | jar 名 + 类名**命名约定** | `META-INF/services` **SPI 声明** |
| jar 命名 | 必须叫 `datalinkx-driver-<type>-1.0.0.jar` | 任意 |
| 类名/包名 | 必须匹配 `com.datalinkx.<type>driver.<Type>Driver` | 任意 |
| 编译期保护 | 无（字符串拼接，写错只在运行时抛） | 有（SPI 文件与接口强类型） |
| 一个 jar 多类型 | 不支持 | 支持 |

> 面试可以这么答：**"我参考了它 runtime 加载的思路，但把命名约定换成 SPI 注册。
> 命名约定把包名/jar 名变成隐式契约，写错一个字母要等到运行才发现；
> SPI 有唯一的显式注册点，IDE 和构建工具都能校验。"**

---

## 九、控制台对插件类型的支持

插件化不只影响「同步」，控制台的**行数统计、数据预览、清空表**同样要支持插件类型，
否则界面上会显示 `0` 或"离线"，让人误以为插件没生效。

### 9.1 双通道设计

最初 `server.js` 按类型分支：MySQL 用 `mysql.exe`，PG 用 `docker exec psql`。
这有两个问题：每加一种数据源就要改 Node 代码；SQLite / ClickHouse 这类
**只在插件 jar 里有 JDBC 驱动**的类型，本地根本没有工具可用。

现在改成双通道：

| 类型 | 通道 | 单次耗时 |
|---|---|---|
| MYSQL | 本地 `mysql.exe` | ~165 ms |
| POSTGRESQL | 本地 `docker exec psql` | ~1 s |
| SQLITE / 其它 | Java 引擎（`SqlQueryBootstrap`） | ~17 s |

> 引擎慢的根因是 `Node -> mvn.cmd -> JVM` 每次都要起一个 Maven。
> **彻底解决要改成常驻 Java 服务**（起一次 JVM，之后 HTTP 毫秒级）。
> 本次的取舍是"复用现有链路，接受启动开销"。

### 9.2 为什么 SQL 要走临时文件

调用链三层：`Node -> mvn.cmd(batch) -> java`。batch 对命令行引号解析不可靠，
含空格的 SQL（如 `SELECT COUNT(*) AS c FROM t_order`）会被拆成多个参数，
表现为 `The syntax of the command is incorrect`。

解法：SQL 写到临时文件，参数只传 `@文件路径`（无空格、无特殊字符）。
Java 侧见到 `@` 前缀就改为读文件内容，彻底绕开三层 shell 的转义问题。

### 9.3 读不到行数时不许显示 0

早期失败时 `rowCount = 0`，界面显示"0 行"，与"真的是 0 行"无法区分。

现在区分三种状态：

```js
d.rowCount         // 有值 → 在线且查到了
d.rowCount = null  // 读不到 → 界面显示「—」，title 挂失败原因
d.rowCountError    // 失败的具体原因，便于排查
```

前端对应显示 `—`（虚线下边框，悬停看原因），不再误导。

### 9.4 方言差异（必须逐个适配）

| 数据库 | 清空表的写法 |
|---|---|
| MySQL / PostgreSQL | `TRUNCATE TABLE t_order` |
| SQLite | `DELETE FROM t_order`（**没有 TRUNCATE**） |

另外 `DataSourceConfig` 的同一个字段在不同驱动里含义不同：
`databaseName` 在 MySQL/PG 是库名，在 **SQLite 是 .db 文件路径**；
`port` 对 SQLite 无意义。Node 侧传 `port=0` 时转成 `-`，Java 侧据此忽略。

### 9.5 表单要按类型变形，不能一视同仁

SQLite 没有 host/port，也不需要账号密码。如果硬显示这些输入框，
用户只会乱填；「数据库名」这一项的含义还从"库名"变成了"文件路径"。

所以前端按 `DRIVER_META` 里的两个开关变形：

```js
needsServer: false,   // 隐藏 主机地址 / 端口
needsAuth:   false,   // 隐藏 用户名 / 密码
dbLabel: '数据库文件路径'   // label 与 placeholder 跟着换
```

配套的保存逻辑也要分支：文件型数据源提交时 `host=''`、`port=0`，
后端收到后忽略这两项。

**这条经验可以推广**：加新数据源类型时，先回答三个问题 ——
它有没有服务端概念？要不要账号密码？"数据库名"实际填什么？
答案写进 `DRIVER_META`，表单、校验、提示三处就都对了。

---

## 十、快速参考

```powershell
# 全量构建（含插件打包并拷到 plugins/）
$env:JAVA_HOME = "D:\jdk"
cd D:\mini-sync
mvn -B clean install -DskipTests

# 单独构建 SQLite 插件（它不在主 reactor 里）
cd D:\mini-sync\mini-sync-plugins\sqlite-plugin
mvn -B clean package

# 看当前注册了哪些驱动
cd D:\mini-sync
mvn -B -q -pl mini-sync-job exec:java "-Dexec.mainClass=com.minisync.job.DriverTypeLister"

# 单元测试
mvn -B clean install -pl mini-sync-job -am -Dtest=PluginDriverRegistryTest -Dsurefire.failIfNoSpecifiedTests=false

# 启动控制台
cd D:\mini-sync\web
node server.js
```

自定义插件目录：

```powershell
# 通过系统属性覆盖默认的 plugins/ 目录
$env:MINISYNC_PLUGIN_DIR = "D:\somewhere\my-drivers"
node server.js
# 或 Java 侧
mvn exec:java -Dexec.args="..." -Dminisync.plugin.dir=D:\my-drivers
```
