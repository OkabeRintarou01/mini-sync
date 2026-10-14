# 阶段一开发过程全记录（小白版）

> 这份文档记录**真实做了什么、为什么这么做、踩了什么坑**。
> 不是理论，是一次完整的开发过程复盘。
> 配套代码文档见 [PHASE1-INCREMENT.md](PHASE1-INCREMENT.md)。

---

## 一、开工之前：先搞清楚"坏了什么"

### 1.1 我做的第一件事：不是写代码，是**读代码 + 查数据库**

很多人（包括以前的我）会直接开始写。但这次我先做了三件核对：

**第一步：把所有入口类的调用关系列出来。** 用搜索工具查 `executeIncrementSync` 被谁调用了：

```
结果：只有它自己的定义（第 155 行），没有任何地方调用它。
```

这就像你买了个电饭煲，插头是好的、内胆是好的，但**插头从来没插到插座上**。

**第二步：查 `last_offset` / `last_pk` 被谁写了。** 搜 `UPDATE sync_job`：

```
结果：只有 /api/job/save 一处，而且它只改任务配置，不碰水位字段。
```

**第三步：查数据库真实结构。** 因为要写 SQL，必须先看清列名和类型：

```sql
SHOW COLUMNS FROM sync_job;
```

关键发现：

```
last_offset   varchar(64)   ← 是字符串！
last_pk       bigint(20)    ← 是数字！
```

### 1.2 为什么这一步救了命

因为我最初拿到的方案里写的是：

```sql
last_offset BIGINT,          -- 错
last_pk VARCHAR(255),        -- 错
```

**两者正好颠倒。** 如果我照着执行 `ALTER TABLE ... MODIFY last_offset BIGINT`，
MySQL 会尝试把 `"2026-01-01 00:00:00"` 转成数字，结果是 0 或者直接报错——
**已经存好的水位就全毁了**。

> 💡 **小白要点**：动手改数据库之前，永远先 `SHOW COLUMNS` 或 `DESC` 看一眼真实结构。
> 文档、记忆、别人给的方案都可能过时，只有 `SHOW COLUMNS` 不会骗你。

### 1.3 确认的三个断点

| # | 断在哪 | 证据 |
|---|---|---|
| 1 | 入口不读任务配置 | `DynamicSyncBootstrap.main()` 用命令行参数拼了个假任务，写死 `syncType="FULL"` |
| 2 | 没有模式分发 | 4 个入口全都只调 `executeFullSync` |
| 3 | 水位从不落库 | 表字段有、实体有、代码读，就是没人写 |

**修复顺序就是 ① → ② → ③。**

---

## 二、第一步：给数据库加一个字段

### 2.1 为什么只加一个字段

我核对后发现，增量需要的字段**大部分已经有了**：

| 字段 | 状态 |
|---|---|
| `sync_type` | ✅ 已有（FULL/INCREMENT） |
| `increment_field` | ✅ 已有 |
| `cursor_field` | ✅ 已有（默认 id） |
| `last_offset` | ✅ 已有 |
| `last_pk` | ✅ 已有 |
| `last_sync_time` | ❌ **缺这个** |

所以只加 `last_sync_time`，其余不动。**改动越小，出错概率越低。**

### 2.2 写迁移脚本

新建 `sql/migration_increment.sql`：

```sql
ALTER TABLE sync_job
    ADD COLUMN last_sync_time DATETIME NULL DEFAULT NULL
    COMMENT '上次同步完成时间（增量任务用于界面展示）';
```

**这里有个坑**：我最初想写 `ADD COLUMN IF NOT EXISTS`（更安全，重复执行不报错），
但查证后发现——**MySQL 不支持这个语法**，那是 MariaDB 的扩展。MySQL 用了会直接语法错误。

所以只能写成普通的 `ADD COLUMN`，并在注释里说明"重复执行会报 Duplicate column name，属正常"。

> 💡 **小白要点**：`IF NOT EXISTS` 在 `CREATE TABLE` 里 MySQL 支持，
> 但在 `ALTER TABLE ADD COLUMN` 里**不支持**。这两个别搞混。

### 2.3 执行并验证

```powershell
# 先加载配置（密码不写进命令）
Get-Content .env | ForEach-Object {
  if ($_ -match '^\s*([^#=]+)\s*=\s*(.*)$') {
    [Environment]::SetEnvironmentVariable($matches[1].Trim(), $matches[2].Trim(), 'Process')
  }
}

# 执行迁移
& $env:MINISYNC_MYSQL_BIN -u$env:MINISYNC_DB_USER -p mini_sync_admin -e "SOURCE sql/migration_increment.sql"

# 验证
& $env:MINISYNC_MYSQL_BIN -u$env:MINISYNC_DB_USER -p mini_sync_admin -e "SHOW COLUMNS FROM sync_job LIKE 'last%';"
```

**实际输出**（迁移后）：

```
Field           Type          Null  Key  Default  Extra
last_offset     varchar(64)   YES        NULL
last_pk         bigint(20)    YES        0
last_sync_time  datetime      YES        NULL      ← 新加的
```

✅ **成功。** 同时我把 `admin.sql` 的建表语句也补上了这个字段和注释，
这样以后新装环境不用再单独跑迁移。

---

## 三、第二步：改 Java——这是核心

### 3.1 想清楚"参数怎么传"

改之前，`DynamicSyncBootstrap` 的调用方式是：

```
DynamicSyncBootstrap cursor 1000 1 2 t_order t_order
                    ↑      ↑    ↑ ↑   ↑       ↑
                   模式  批次 源 目标 源表   目标表
```

**6 个位置参数。** 现在要支持增量，还需要 `increment_field`、`last_offset`、
`last_pk`、`cursor_field` —— 再往下加就是 **10 个位置参数**。

10 个位置参数意味着什么？意味着某天有人把源表和目标表传反了，
程序**不会报错**，它会安安静静地把数据搬错方向。这类 bug 最难查。

所以我做了个决定：**只传 jobId，让引擎自己去数据库读配置。**

```
DynamicSyncBootstrap 6
                    ↑
                  任务ID
```

好处有三个：
1. 调用方不需要知道任务细节
2. 不可能传错位置
3. 以后任务加字段，调用方**不用改**

> 💡 **小白要点**：当参数超过 3~4 个，就该考虑"传 ID 让它自己查"。
> 这叫"参数对象化"思想，是工程上很常见的判断。

### 3.2 代码骨架

新的 `main()` 逻辑：

```java
public static void main(String[] args) throws Exception {
    long jobId = Long.parseLong(args[0]);

    // ① 读配置
    SyncJobEntity job = loadJob(jobId);
    String syncType = job.getSyncType().toUpperCase();

    // ② 读两端数据源
    DataSourceDriver source = pool.getDriver(sourceId, sourceType, sourceCfg);
    DataSourceDriver target = pool.getDriver(targetId, targetType, targetCfg);

    // ③ 按模式分发
    if ("INCREMENT".equals(syncType)) {
        runIncrement(executor, job, source, target, mapping);
    } else {
        runFull(executor, job, source, target, mapping);
    }
}
```

三层结构：**读配置 → 建连接 → 分发执行。**

### 3.3 水位落库：成功怎么办

```java
private static void runIncrement(...) {
    try {
        SyncResult r = executor.executeIncrementSync(job, source, target, mapping);
        log.info("同步条数: {}", r.getTotalCount());

        // 成功：把推进到的水位写回去
        Watermark w = r.getWatermark();
        if (w != null && w.getWater() != null) {
            saveWatermark(job.getId(), w.getWater(), w.getPk());
        } else {
            // 表为空或已是最新：保持原值，别用 null 覆盖
            log.info("本次无可推进的水位（无新增数据），保持原水位不变");
        }
    } catch (IncrementSyncException e) {
        ...
    }
}
```

**注意那个 `else` 分支**。如果不判空直接用 null 覆盖，
下次同步会发现 `last_offset = null`，然后**从头重扫整张表**。
数据不会错（upsert 幂等），但 10 万行白搬了。

### 3.4 水位落库：失败怎么办（这是精华）

这是整个项目**最能讲的一个设计**：

```java
} catch (IncrementSyncException e) {
    if (e.getTargetWatermark() != null) {
        saveWatermark(job.getId(), e.getTargetWatermark(), e.getLastPk());
        log.warn("增量失败，已按【目标端真实进度】回写水位");
    }
    throw e;
}
```

**为什么要回查目标端？** 举个例子：

```
第 5 批：从源端读出 1000 行 → 写入目标端 → 失败（目标库连接断了）
```

现在水位该怎么记？

- ❌ **记源端位置**（"我已经读到第 5000 行了"）
  → 下次从 5000 开始 → **但这 1000 行根本没写进目标库** → **数据永久丢失**

- ✅ **回查目标端**（"目标库实际只写到第 4000 行"）
  → 下次从 4000 开始 → 第 5 批会被**重新读、重新写** → 数据不丢

代价是可能**重复写**，但：
1. 驱动层的 `batchWrite` 是 **upsert**（幂等），重复写不会产生脏数据
2. **宁可重复，不可丢失** —— 这就是 `at-least-once` 语义

> 💡 **面试可以这么说**：
> "增量同步失败时，位点不能沿用源端进度，必须回查目标端真实写入位置。
> 否则'已读未写成功'的那批数据会被永久跳过。我选择 at-least-once：
> 宁可重复写，配合 upsert 幂等保证不产生脏数据。"

### 3.5 第一次编译：报错

```java
private static void runIncrement(...) {          // 没声明异常
    saveWatermark(...);                          // 这个方法抛 SQLException
}
```

编译报错：

```
[ERROR] 未报告的异常错误java.sql.SQLException; 必须对其进行捕获或声明以便抛出
```

**原因**：Java 的"受检异常"机制——一个方法如果会抛 `SQLException`，
调用它的方法要么 `try-catch`，要么在签名上声明 `throws`。

**修复**：给 `runIncrement` 加上 `throws SQLException`。

```java
private static void runIncrement(...) throws SQLException {
```

> 💡 **小白要点**：Java 把异常分两类。
> **受检异常**（如 `SQLException`）编译器强制你处理；
> **非受检异常**（如 `IllegalArgumentException`）不强制。
> 看到 "未报告的异常" 就是忘记 `throws` 或 `catch` 了。

---

## 四、第三步：改 Node 控制台

### 4.1 改任务保存：能存模式了

原来的 `/api/job/save` 写死 `sync_type='FULL'`：

```js
VALUES (..., 'FULL', 'id', ...)
```

改成从请求里取：

```js
const syncType = String(b.sync_type || 'FULL').toUpperCase() === 'INCREMENT'
    ? 'INCREMENT' : 'FULL';
const incField = syncType === 'INCREMENT' ? String(b.increment_field || '').trim() : null;

// 增量必须有字段，否则提前拦住
if (syncType === 'INCREMENT' && !incField) {
    return json(res, { code: 400, msg: '增量模式必须填写「增量字段」，如 update_time' });
}
```

**这里我犯过一个错**：方案里写的是 `sync_mode` 列，但真实列名是 `sync_type`。
如果我照抄，运行时会报 `Unknown column 'sync_mode'`。
好在我前面查过 `SHOW COLUMNS`，写的时候直接用对了。

> 💡 **小白要点**：改数据库相关代码时，**列名一定要对着 `SHOW COLUMNS` 的实际输出抄**，
> 不要凭印象、不要照抄别人的方案。

### 4.2 改触发同步：只传 jobId

原来：

```js
const args = [mode, batchSize, sourceId, targetId, sourceTable, targetTable].join(' ');
```

改成：

```js
'-Dexec.args=' + String(job.id)
```

同时增强返回值的解析，加上水位和错误信息：

```js
const fe = out.match(/同步失败:\s*(.+)/);
const wm = out.match(/水位已落库: jobId=\d+, last_offset=([^,]+), last_pk=(\d+)/);

resolve({
    ...
    watermark: wm ? (wm[1].trim() + ' / ' + wm[2]) : null,
    errorMsg: m ? null : (fe ? fe[1].trim() : '未知错误'),
});
```

**为什么保留 `同步条数:` 和 `总耗时  :` 的日志格式？**
因为 Node 用正则从 Java 的 stdout 里抓这两个值：

```js
const m = out.match(/总耗时\s*:\s*(\d+)\s*ms/);
const c = out.match(/同步条数:\s*(\d+)/);
```

**格式一改，界面上的统计就全变 0。** 这类"隐式约定"要写注释标出来。

### 4.3 新增重置水位接口

```js
if (p === '/api/job/resetwatermark') {
    const id = parseInt(url.searchParams.get('id'));
    const rows = await query('SELECT id, job_name FROM sync_job WHERE id=' + id, ADMIN_DB);
    if (!rows.length) return json(res, { code: 404, msg: '任务不存在' });
    await query(`UPDATE sync_job
                 SET last_offset=NULL, last_pk=0, last_sync_time=NULL
                 WHERE id=${id}`, ADMIN_DB);
    return json(res, { code: 200, msg: '已重置「' + rows[0].job_name + '」的水位' });
}
```

**为什么需要这个接口？** 演示和测试都要用——想重头跑一遍，总不能去手动改数据库。

---

## 五、第四步：改前端

### 5.1 弹窗加两个字段

```html
<div class="form-2">
  <div class="form-row">
    <label>同步模式 *</label>
    <select id="jobSyncType" onchange="onSyncTypeChange()">
      <option value="FULL">全量（每次搬整表）</option>
      <option value="INCREMENT">增量（按水位只搬变更）</option>
    </select>
  </div>
  <div class="form-row" id="rowIncrementField">
    <label>增量字段 *</label>
    <input type="text" id="jobIncrementField" placeholder="update_time">
  </div>
</div>
```

**关键**：选「全量」时把「增量字段」藏起来。因为全量模式根本不看这个字段，
显示出来只会让人困惑。

```js
function onSyncTypeChange() {
  const isInc = document.getElementById('jobSyncType').value === 'INCREMENT';
  toggleFieldRow('rowIncrementField', isInc);
  // 顺便给个说明，让用户知道两种模式的区别
  document.getElementById('jobModeHint').textContent = isInc
    ? '增量模式：按「增量字段 + 主键」复合游标推进，每批成功后把水位写回数据库，中断后可续传。'
    : '全量模式：每次按主键游标分页搬整表，不维护水位。';
}
```

### 5.2 列表加两列

新增「模式」和「上次水位」两列。水位只在增量任务上有意义，
全量任务显示 `—`：

```js
const wm = isInc
  ? (j.last_offset
      ? `... ${shorten(String(j.last_offset), 19)} / ${j.last_pk} ...`
      : '<span class="cnt-na" title="尚未同步过，首次将全范围读取">未开始</span>')
  : '<span class="cnt-na">—</span>';
```

再加一个「重置水位」按钮，但**只对增量任务显示**：

```js
${isInc ? `<span class="link" onclick="resetWatermark(${j.id})">重置水位</span>` : ''}
```

> 💡 **小白要点**：UI 要跟着业务语义走。全量任务没有水位，
> 就不该给它看"重置水位"按钮——**能点但没用的按钮，比没有按钮更让人困惑**。

### 5.3 表头 colspan 必须同步改

加了 2 列，表头从 7 个 `<th>` 变成 9 个，
那么"暂无任务"那行的 `colspan="7"` **也必须改成 9**，否则表格会错位。

我写完专门写了个小脚本核对：

```
jobTable: 表头 9 / colspan 9 ✓
dsTable:  表头 9 / colspan 9 ✓
historyTable: 表头 7 / colspan 7 ✓
```

> 💡 **小白要点**：HTML 表格里 `colspan` 和实际列数不一致，
> 症状是空数据时表格边框错位。这种"看起来没报错但显示不对"的问题，
> 用脚本核对比肉眼看可靠。

### 5.4 执行结果要区分成功/失败

**这里有个容易踩的坑**：Java 进程失败时退出码是 1，
但 Node 依然返回 `HTTP 200` + `data.success=false`。

所以前端**不能只靠 try/catch** 判断成败：

```js
if (!d.success) {
  // 显示失败
} else {
  // 显示成功 + 水位
}
```

如果只写 try/catch，失败时会走"成功"分支，显示一条"同步成功：0 条"——
**这比报错还误导人。**

---

## 六、验证阶段：这里发现了两个真问题

我按计划逐项验证。前几项顺利，但**第 5 项（断点续传）暴露了两个 bug**。

### 6.1 顺利的部分

**6.1 建增量任务** ✅

```json
{"code":200,"msg":"新增成功"}

id  job_name            sync_type   increment_field  last_offset  last_pk
6   增量：源库→目标库    INCREMENT   update_time      NULL         0
```

**6.2 只同步变更** ✅ 改了 100 行 → 同步 **100 条**（不是 10 万），耗时 34ms。

**6.3 水位生效** ✅ 不动源表再跑 → 同步 **0 条**，8ms。

**6.4 复合游标边界** ✅ 这条最有价值：

```sql
-- 制造 2500 行 update_time 完全相同的场景
UPDATE t_order SET update_time='2026-03-01 12:00:00' WHERE id BETWEEN 1 AND 2500;
-- batch_size=1000 → 必然跨 3 批
```

结果：

```
dst_updated  dst_stale
2500         0          ← 2500 行全部更新，零丢失
水位落在: 2026-03-01 12:00:00 / pk=2500
```

**为什么这条最重要？** 如果水位只用 `update_time > ?`（单字段），
第二批会跳过剩余同时间戳的行。而复合游标
`WHERE (water > ?) OR (water = ? AND pk > ?)` 能严格推进。

**这就是"复合游标解决边界丢数据"的实证。**

### 6.2 🐛 Bug #1：水位格式不对

**现象**：落库的水位是 `2026-01-01T00:00`，而不是 `2026-01-01 00:00:00`。

**排查过程**：

1. 第一反应是 `String.valueOf(Timestamp)` 的问题 —— 但 `Timestamp.toString()`
   产出的是 `2026-01-01 00:00:00.0`（空格分隔），**不是 `T`**。
2. 加了 `Timestamp` / `Date` 的判断后，**问题依旧**。
3. 于是我在想：到底是什么类型？

**答案：`LocalDateTime`。**

MySQL Connector/J 8.x 对 DATETIME 列**默认返回 `java.time.LocalDateTime`**，
不是老的 `java.sql.Timestamp`。而 `LocalDateTime.toString()` 的格式正是
ISO-8601：`2026-01-01T00:00` —— **带 T，而且秒被省略了**。

**修复**：

```java
if (waterValue instanceof java.time.LocalDateTime) {
    return ((java.time.LocalDateTime) waterValue)
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
}
```

**为什么必须修？** 这个值会作为参数回填进下一轮的
`WHERE update_time > ?`。MySQL 虽然能容忍 `T` 分隔符，
但**"丢掉秒"是实打实的**：同一分钟内的多行数据会被算作"水位相同"，
一旦跨分钟边界就会出错。

> 💡 **小白要点**：JDBC 取时间字段，**永远不要直接 `String.valueOf()`**。
> MySQL 8 驱动返回 `LocalDateTime`，PostgreSQL 返回 `Timestamp`，
> Oracle 又是别的类型。**显式格式化**才是唯一稳妥的做法。

### 6.3 🐛 Bug #2：水位不是逐批落库（更严重）

**现象**：按计划做断点续传测试——跑到一半强杀进程，查水位：

```
wm    last_pk
NULL  0        ← 水位是空的！
```

但查目标表：

```
dst_rows
100000         ← 数据其实都写进去了
```

**这说明**：数据被逐批 upsert 提交了，但水位**只在同步全部完成后才写一次**。
进程被杀 → 那次写入没执行 → 进度全丢。

**这算 bug 吗？** 严格说数据没错（upsert 幂等，重跑无害），
但它**不叫"断点续传"** —— 它叫"重头再来一遍"。
而我的文档和简历上都写了"断点续传"，**名不副实**。

**修复方案**：加一个批次回调（Callback）。

在 `SyncJobExecutor` 里定义一个函数式接口：

```java
@FunctionalInterface
public interface BatchWatermarkListener {
    void onBatchCommitted(String water, long pk) throws Exception;
}
```

每批写完就回调一次：

```java
if (onBatchCommitted != null) {
    try {
        onBatchCommitted.onBatchCommitted(lastWater, lastPk);
    } catch (Exception cbEx) {
        // 水位落库失败不阻断同步：数据已经进目标端了，
        // 只是下次可能多扫一点（幂等保证无害）
        log.warn("批次水位回调失败（不影响本批数据，仅影响续传精度）: {}", cbEx.getMessage());
    }
}
```

`DynamicSyncBootstrap` 里传一个 lambda：

```java
executor.executeIncrementSync(job, source, target, mapping,
    (water, pk) -> saveWatermarkQuietly(job.getId(), water, pk));
```

**两个设计细节**：

1. **为什么回调失败不抛出？**
   数据已经写进目标端了。为了"进度记录得准不准"这个问题去中断整个同步，
   不划算。记个警告就够了。

2. **为什么做"安静版"？**
   每批一条 INFO 日志，10 万行/500 批次 = 200 条日志刷屏。
   所以 `saveWatermarkQuietly` 不打日志。

**修复后重测**：

```
强杀后水位: [2026-10-14 23:07:52]  18500   ← 保住了！
重启后续传: 起始水位=(2026-10-14 23:07:52, 18500)
           同步条数: 81500                  ← = 100000 - 18500
```

✅ **这才是真正的断点续传**：从 18500 继续，而不是从头。

> 💡 **小白要点**：什么叫真正的断点续传？
> **进度必须"边做边记"，不能"做完全记"。**
> 就像下载文件，要定期把已下载的字节数存下来，
> 而不是等整个文件下完才记录——那样中途断网就全白费了。

---

## 七、最终验证结果

全部 9 项验收标准通过（都是真实数据库实测）：

| # | 验收项 | 结果 |
|---|---|---|
| 1 | 迁移脚本执行、字段存在 | ✅ `last_sync_time` 已加，类型核对无误 |
| 2 | 界面能建增量任务 | ✅ `sync_type=INCREMENT`、`increment_field=update_time` |
| 3 | 执行走进 `executeIncrementSync` | ✅ 日志显示"增量配置: 字段=update_time" |
| 4 | 只变更 100 行 → 同步 100 条 | ✅ 100 条（而非 10 万），34ms |
| 5 | 不变更 → 同步 0 条 | ✅ 0 条，8ms |
| 6 | 2500 行同时间戳跨批无丢失 | ✅ 目标 2500 行，零丢失 |
| 7 | 强杀后续传 | ✅ 水位停 18500，重启续传 81500 |
| 8 | 全量任务不受影响 | ✅ 10 万条正常，不写水位 |
| 9 | 重置水位可从头重跑 | ✅ 接口可用，重置后水位归 NULL |

### 通过控制台接口的完整回归

| 轮次 | 源表变化 | 同步条数 | 耗时 |
|---|---|---|---|
| 第 1 轮（重置后） | — | 100,000 | 2787ms |
| 第 2 轮 | 无变化 | **0** | **8ms** |
| 第 3 轮 | 改 300 行 | **300** | **54ms** |

**第 2、3 轮的数据直接证明了增量同步在工作**——
如果没有水位，第 2 轮会重新搬 10 万条。

---

## 八、这次改动的清单

```
 mini-sync-job/.../DynamicSyncBootstrap.java   | 250 +++++-   核心：读配置+分发+落水位
 mini-sync-job/.../executor/SyncJobExecutor.java |  94 ++-    水位格式化 + 批次回调
 sql/migration_increment.sql                    |  19 +        新增字段
 sql/admin.sql                                  |  15 +-       建表语句同步
 web/server.js                                  |  67 +-       保存/触发/重置水位
 web/index.html                                 |  97 +-       模式选择/水位列/重置按钮
 PHASE1-INCREMENT.md                            | 904 ++++      实施文档
```

**总计 7 个文件，1376 行新增，70 行删除。**

---

## 九、遗留问题（诚实记录）

做完之后，以下几点仍是已知限制，**面试可以主动讲**：

1. **失败时的水位组合有理论瑕疵**
   `IncrementSyncException` 携带的是「目标端水位 + 源端主键」。
   正常回滚场景下两者一致；但如果目标端因历史原因超前，
   "用目标端水位配源端主键"理论上可能跳过极少量行。
   更严谨的做法是记录「最后一批成功提交的完整水位对」。

2. **增量依赖源表时间字段被正确维护**
   如果业务的 `update_time` 不随更新变化（比如手写 SQL 忘了带），增量会漏数据。
   生产上更可靠的是 **CDC（binlog）**——这是增量同步的行业共识，
   面试被问到可以说"我知道边界，生产会考虑 CDC"。

3. **批次计数仍是乐观计数**
   `total += batch.size()`，未核对目标端实际写入行数。
   如果数据库静默丢弃（触发器拦截、字符集截断），程序会误报成功。

4. **全量同步仍无断点**
   本阶段只给增量加水位。全量的断点需要按主键范围分段持久化，
   语义与增量不同（增量是"水位"，全量是"分段"）。

---

## 十、给你（小白）的复盘要点

如果让我把这次开发浓缩成 5 条可复用的经验：

### 1️⃣ 动手前先"体检"

`SHOW COLUMNS`、搜索调用关系、确认字段类型——
**20 分钟的核对，省下了可能毁数据的翻车。**
我发现的"类型颠倒"就是靠这一步拦住的。

### 2️⃣ 参数多了就"传 ID"

6 个位置参数已经是危险信号。传 ID 让它自己查，
不仅不会传错，以后加字段还不用改调用方。

### 3️⃣ 关键状态要"边做边记"

水位只在最后写一次 = 中途挂了全丢。
**改成逐批写，才叫断点续传。**
任何"长时间运行的任务"都要想这个问题。

### 4️⃣ 失败时记"真实进度"，不记"我以为的进度"

源端读到哪 ≠ 目标端写到哪。
失败时回查目标端，宁可重复不可丢失。

### 5️⃣ 验证要造"边界数据"

正常跑 10 万条、改 100 行，这都不算严格。
**专门造"2500 行同一时间戳"这种极端场景**，才逼出了复合游标的真实价值。
测试数据的"刁钻程度"决定了你能发现多少问题。

---

## 附：常用命令速查

```powershell
# 加载环境变量（每次新开终端都要）
cd D:\mini-sync
Get-Content .env | ForEach-Object {
  if ($_ -match '^\s*([^#=]+)\s*=\s*(.*)$') {
    [Environment]::SetEnvironmentVariable($matches[1].Trim(), $matches[2].Trim(), 'Process')
  }
}

# 编译
$env:JAVA_HOME = "D:\jdk"
mvn -B clean install -DskipTests

# 手动跑某个任务（看日志最直接）
mvn -B -pl mini-sync-job exec:java "-Dexec.mainClass=com.minisync.job.DynamicSyncBootstrap" "-Dexec.args=6"

# 查水位
& $env:MINISYNC_MYSQL_BIN -u$env:MINISYNC_DB_USER -p mini_sync_admin `
  -e "SELECT id, sync_type, increment_field, last_offset, last_pk, last_sync_time FROM sync_job;"

# 重置水位
curl.exe "http://127.0.0.1:8090/api/job/resetwatermark?id=6"

# 重启控制台
Get-NetTCPConnection -LocalPort 8090 -State Listen -ErrorAction SilentlyContinue |
  ForEach-Object { Stop-Process -Id $_.OwningProcess -Force }
Start-Process node -ArgumentList "server.js" -WorkingDirectory "D:\mini-sync\web" -WindowStyle Hidden
```
