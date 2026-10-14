# 阶段一：增量同步闭环（修正版）

> **目标**：让 `executeIncrementSync` 从死代码变成「界面可触发 → 水位落库 → 断点续传」的完整链路。
> **前提**：本文所有 SQL 与列名都已对照真实数据库核对过（实测）。
> **预计工时**：2–3 天（含验证）。

---

## 0. 先理解：现在断在哪里

三条断点，缺一不可：

| # | 断点 | 现状 |
|---|---|---|
| 1 | **入口不读任务配置** | `DynamicSyncBootstrap.main()` 用命令行参数拼了一个 `syncType="FULL"` 的临时任务，从不读 `sync_job` 行 |
| 2 | **没有模式分发** | 4 个 Bootstrap 全部只调 `executeFullSync`，`executeIncrementSync` 无人调用 |
| 3 | **水位从不落库** | `sync_job.last_offset / last_pk` 被读取，但全项目没有一处 UPDATE 它们 |

所以修复顺序就是：**① 读配置 → ② 按模式分发 → ③ 落水位**。

### 为什么不用改 `DataSourceDriver` 接口

增量查询可以直接复用现有的 `executeQuery(sql, params)`——`executeIncrementSync` 内部已经这么写了。
**不要去给 SPI 接口加 `readByIncrementCursor` 之类的方法**：`mini-sync-spi` 是已发布的插件契约，
三个插件 jar（mysql / postgresql / sqlite）都实现了它，改签名会让它们全部失效。
"插件契约稳定"正是你面试讲插件化时最该强调的点，不能自己破坏。

---

## 1. 数据库：只加一个字段

真实结构（已核对，**注意类型不要改**）：

```
last_offset   varchar(64)   -- 水位值，例如 "2026-10-14 10:00:00"
last_pk       bigint(20)    -- 主键值，例如 50000
```

> ⚠️ 常见错误：把 `last_offset` 写成 `BIGINT`、`last_pk` 写成 `VARCHAR`。
> 水位是**时间戳字符串**，主键是**数字**，两者正好相反。
> 用 `MODIFY` 改错类型会把已有水位转成 0 或直接报错。

`increment_field`、`last_offset`、`last_pk`、`sync_type` **都已存在**，不需要加。
只缺一个记录同步时间的字段。

### 执行迁移

新建 `sql/migration_increment.sql`：

```sql
-- 增量同步闭环所需的最小改动：只新增一个「上次同步时间」字段。
-- 注意：MySQL 不支持 ADD COLUMN IF NOT EXISTS（那是 MariaDB 扩展），
--       所以本脚本只能执行一次；重复执行会报 Duplicate column name，属正常。

ALTER TABLE sync_job
    ADD COLUMN last_sync_time DATETIME NULL DEFAULT NULL
    COMMENT '上次同步完成时间（增量任务用于展示）';
```

执行：

```powershell
cd D:\mini-sync       # 注意：必须在项目根目录，脚本用相对路径
# 先加载 .env（凭据不写进命令）
Get-Content .env | ForEach-Object {
  if ($_ -match '^\s*([^#=]+)\s*=\s*(.*)$') {
    [Environment]::SetEnvironmentVariable($matches[1].Trim(), $matches[2].Trim(), 'Process')
  }
}
& $env:MINISYNC_MYSQL_BIN -u$env:MINISYNC_DB_USER -p mini_sync_admin -e "SOURCE sql/migration_increment.sql"
```

验证：

```powershell
& $env:MINISYNC_MYSQL_BIN -u$env:MINISYNC_DB_USER -p mini_sync_admin -e "SHOW COLUMNS FROM sync_job LIKE 'last%';"
```

期望看到 `last_offset varchar(64)`、`last_pk bigint(20)`、`last_sync_time datetime` 三行。

### 同步更新初始化脚本

在 `sql/admin.sql` 的 `sync_job` 建表语句里，`last_pk` 之后补一行，让新装环境也有这个字段：

```sql
    last_pk              BIGINT       DEFAULT 0,
    last_sync_time       DATETIME     DEFAULT NULL COMMENT '上次同步完成时间',
```

---

## 2. Java：读配置 + 分发 + 落水位

只改**一个文件**：`mini-sync-job/src/main/java/com/minisync/job/DynamicSyncBootstrap.java`。

### 设计变化

| 项目 | 改前 | 改后 |
|---|---|---|
| 参数 | `mode batchSize sourceId targetId sourceTable targetTable` | **`jobId`** |
| 任务配置来源 | 命令行参数拼的假对象 | **从 `sync_job` 表读** |
| 执行 | 恒定 `executeFullSync` | 按 `sync_type` 分发 |
| 水位 | 不写 | **成功写结果水位；失败写目标端水位** |

> 参数简化成 jobId 是刻意的：增量需要 `increment_field`、`last_offset`、`last_pk`、`cursor_field` 四个参数，
> 继续用位置参数会变得又长又容易传错。让引擎自己去库里读，调用方只管"跑哪个任务"。

### 完整替换内容

```java
package com.minisync.job;

import com.minisync.connector.pool.DataSourcePool;
import com.minisync.job.entity.SyncJobEntity;
import com.minisync.job.executor.SyncJobExecutor;
import com.minisync.spi.DataSourceConfig;
import com.minisync.spi.DataSourceDriver;
import lombok.extern.slf4j.Slf4j;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

/**
 * 动态同步入口：按 jobId 从管理库读取完整任务配置并执行。
 *
 * <p>支持两种模式，由 {@code sync_job.sync_type} 决定：</p>
 * <ul>
 *   <li>{@code FULL} —— 全量同步，主键游标分页，每次全表搬</li>
 *   <li>{@code INCREMENT} —— 增量同步，复合游标 {@code (增量字段, 主键)}，
 *       每批成功后把水位写回 {@code sync_job}</li>
 * </ul>
 *
 * <p><b>为什么任务配置从库里读，而不是命令行传？</b>
 * 增量同步需要 increment_field / last_offset / last_pk / cursor_field 四项配置，
 * 位置参数会变得冗长且容易传错。让引擎自己读库，调用方只需给出 jobId。</p>
 *
 * <p>用法：{@code java DynamicSyncBootstrap <jobId>}</p>
 */
@Slf4j
public class DynamicSyncBootstrap {

    private static final String ADMIN_URL =
            "jdbc:mysql://" + env("MINISYNC_ADMIN_HOST", "127.0.0.1")
            + ":" + env("MINISYNC_ADMIN_PORT", "3306")
            + "/" + env("MINISYNC_ADMIN_DB", "mini_sync_admin")
            + "?useUnicode=true&characterEncoding=utf8&useSSL=false"
            + "&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true";
    private static final String ADMIN_USER = env("MINISYNC_DB_USER", "root");
    private static final String ADMIN_PWD = requiredEnv("MINISYNC_DB_PWD");

    /** 取非敏感环境变量，为空时用默认值。 */
    private static String env(String key, String fallback) {
        String v = System.getenv(key);
        return (v == null || v.trim().isEmpty()) ? fallback : v.trim();
    }

    /** 敏感变量必须显式配置，不在源码中提供口令默认值。 */
    private static String requiredEnv(String key) {
        String v = System.getenv(key);
        if (v == null || v.trim().isEmpty()) {
            throw new IllegalStateException("缺少环境变量 " + key
                    + "；请复制 .env.example 为 .env 并填写数据库口令");
        }
        return v.trim();
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args[0].trim().isEmpty()) {
            System.err.println("用法: DynamicSyncBootstrap <jobId>");
            System.exit(2);
            return;
        }
        long jobId = Long.parseLong(args[0].trim());

        // ---------- 1. 读取完整任务配置（含增量水位）----------
        SyncJobEntity job = loadJob(jobId);
        String syncType = (job.getSyncType() == null || job.getSyncType().trim().isEmpty())
                ? "FULL" : job.getSyncType().trim().toUpperCase();
        job.setSyncType(syncType);

        if ("INCREMENT".equals(syncType)
                && (job.getIncrementField() == null || job.getIncrementField().trim().isEmpty())) {
            throw new IllegalStateException(
                    "任务 " + jobId + " 是增量模式，但未配置 increment_field（增量字段）");
        }

        log.info("任务: id={}, name={}, 模式={}, 批次={}, 源表={}, 目标表={}",
                job.getId(), job.getJobName(), syncType, job.getBatchSize(),
                job.getSourceTable(), job.getTargetTable());
        if ("INCREMENT".equals(syncType)) {
            log.info("增量配置: 字段={}, 起始水位=(last_offset={}, last_pk={})",
                    job.getIncrementField(), job.getLastOffset(), job.getLastPk());
        }

        // ---------- 2. 两端数据源 ----------
        long sourceId = job.getSourceDatasourceId();
        long targetId = job.getTargetDatasourceId();
        DataSourceConfig sourceCfg = loadDataSource(sourceId);
        DataSourceConfig targetCfg = loadDataSource(targetId);
        String sourceType = loadDataSourceType(sourceId);
        String targetType = loadDataSourceType(targetId);

        log.info("源数据源: id={}, type={}, db={}", sourceId, sourceType, sourceCfg.getDatabaseName());
        log.info("目标数据源: id={}, type={}, db={}", targetId, targetType, targetCfg.getDatabaseName());

        DataSourcePool pool = new DataSourcePool();
        // 工厂不缓存实例，因此源与目标即使同类型也会拿到两条独立连接
        DataSourceDriver source = pool.getDriver(sourceId, sourceType, sourceCfg);
        DataSourceDriver target = pool.getDriver(targetId, targetType, targetCfg);

        SyncJobExecutor executor = new SyncJobExecutor(pool);
        Map<String, String> mapping = new HashMap<String, String>();

        log.info("========== 开始同步 ==========");
        log.info("方向: {} -> {}", sourceType, targetType);

        long start = System.currentTimeMillis();
        try {
            if ("INCREMENT".equals(syncType)) {
                runIncrement(executor, job, source, target, mapping);
            } else {
                runFull(executor, job, source, target, mapping);
            }
        } catch (Exception e) {
            // 失败信息要能被 Node 侧解析出来写进 sync_log
            log.error("同步失败: {}", e.getMessage(), e);
            System.out.println("同步失败: " + e.getMessage());
            pool.closeAll();
            System.exit(1);
            return;
        }
        long cost = System.currentTimeMillis() - start;

        log.info("总耗时  : {} ms", cost);
        log.info("==============================");

        pool.closeAll();
    }

    // ==================================================================
    // 全量
    // ==================================================================

    private static void runFull(SyncJobExecutor executor, SyncJobEntity job,
                                DataSourceDriver source, DataSourceDriver target,
                                Map<String, String> mapping) {
        SyncJobExecutor.SyncResult r = executor.executeFullSync(job, source, target, mapping);
        log.info("同步条数: {}", r.getTotalCount());
        // 全量同步不维护水位：它是"整表搬"，没有"从哪继续"的语义。
        // 断点续传只对增量任务有意义。
    }

    // ==================================================================
    // 增量 + 水位落库
    // ==================================================================

    private static void runIncrement(SyncJobExecutor executor, SyncJobEntity job,
                                     DataSourceDriver source, DataSourceDriver target,
                                     Map<String, String> mapping) {
        try {
            SyncJobExecutor.SyncResult r =
                    executor.executeIncrementSync(job, source, target, mapping);
            log.info("同步条数: {}", r.getTotalCount());

            // ----- 成功：把本次推进到的水位落库 -----
            SyncJobExecutor.Watermark w = r.getWatermark();
            if (w != null && w.getWater() != null) {
                saveWatermark(job.getId(), w.getWater(), w.getPk());
            } else {
                // 表为空、或已同步到最新：没有新水位可推进。
                // 此时必须"保持原值"，不能被 null 覆盖掉，否则下次会从头重扫。
                log.info("本次无可推进的水位（无新增数据），保持原水位不变");
            }

        } catch (SyncJobExecutor.IncrementSyncException e) {
            // ===== 关键设计：失败时回写【目标端真实水位】，而不是源端水位 =====
            //
            // 若沿用源端水位，那么"本批已从源端读出、但写入目标端失败"的数据，
            // 在下次任务中会被直接跳过 —— 造成永久丢失。
            //
            // 回查目标端真实进度，则下次会从"目标端确实写到哪"继续，
            // 本批数据会被重新读取并重新写入。配合驱动层的 upsert 幂等，
            // 重复写不会产生脏数据。这就是 at-least-once（宁可重复，不可丢失）。
            if (e.getTargetWatermark() != null) {
                saveWatermark(job.getId(), e.getTargetWatermark(), e.getLastPk());
                log.warn("增量失败，已按【目标端真实进度】回写水位: last_offset={}, last_pk={}",
                        e.getTargetWatermark(), e.getLastPk());
            } else {
                log.warn("增量失败，且无法回查目标端水位，保持原水位不变"
                        + "（下次将重跑本批，属正常）: {}", e.getMessage());
            }
            throw e;
        }
    }

    /** 水位落库。last_offset 是水位值（字符串），last_pk 是主键（数字）。 */
    private static void saveWatermark(long jobId, String water, long pk) throws SQLException {
        String sql = "UPDATE sync_job SET last_offset=?, last_pk=?, last_sync_time=NOW() WHERE id=?";
        try (Connection conn = DriverManager.getConnection(ADMIN_URL, ADMIN_USER, ADMIN_PWD);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, water);
            ps.setLong(2, pk);
            ps.setLong(3, jobId);
            int n = ps.executeUpdate();
            log.info("水位已落库: jobId={}, last_offset={}, last_pk={}, 影响行数={}",
                    jobId, water, pk, n);
        }
    }

    // ==================================================================
    // 读配置
    // ==================================================================

    private static SyncJobEntity loadJob(long jobId) throws SQLException {
        String sql = "SELECT id, job_name, source_datasource_id, source_table, "
                + "target_datasource_id, target_table, sync_type, increment_field, "
                + "cursor_field, batch_size, last_offset, last_pk "
                + "FROM sync_job WHERE id = ?";
        try (Connection conn = DriverManager.getConnection(ADMIN_URL, ADMIN_USER, ADMIN_PWD);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, jobId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalArgumentException("任务不存在: id=" + jobId);
                }
                SyncJobEntity j = new SyncJobEntity();
                j.setId(rs.getLong("id"));
                j.setJobName(rs.getString("job_name"));
                j.setSourceDatasourceId(rs.getLong("source_datasource_id"));
                j.setSourceTable(rs.getString("source_table"));
                j.setTargetDatasourceId(rs.getLong("target_datasource_id"));
                j.setTargetTable(rs.getString("target_table"));
                j.setSyncType(rs.getString("sync_type"));
                j.setIncrementField(rs.getString("increment_field"));
                j.setCursorField(rs.getString("cursor_field"));
                int bs = rs.getInt("batch_size");
                j.setBatchSize(rs.wasNull() || bs <= 0 ? 1000 : bs);
                j.setLastOffset(rs.getString("last_offset"));
                long pk = rs.getLong("last_pk");
                j.setLastPk(rs.wasNull() ? 0L : pk);
                return j;
            }
        }
    }

    /** 读取数据源连接配置 */
    private static DataSourceConfig loadDataSource(long id) throws Exception {
        try (Connection conn = DriverManager.getConnection(ADMIN_URL, ADMIN_USER, ADMIN_PWD);
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT host, port, database_name, username, password FROM datasource_config WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalArgumentException("数据源不存在: id=" + id);
                }
                DataSourceConfig c = new DataSourceConfig();
                c.setHost(rs.getString("host"));
                c.setPort(rs.getInt("port"));
                c.setDatabaseName(rs.getString("database_name"));
                c.setUsername(rs.getString("username"));
                c.setPassword(rs.getString("password"));
                return c;
            }
        }
    }

    /** 读取数据源类型 */
    private static String loadDataSourceType(long id) throws Exception {
        try (Connection conn = DriverManager.getConnection(ADMIN_URL, ADMIN_USER, ADMIN_PWD);
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT ds_type FROM datasource_config WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalArgumentException("数据源不存在: id=" + id);
                }
                return rs.getString("ds_type");
            }
        }
    }
}
```

### 为什么保留 `同步条数: {}` 和 `总耗时  : {} ms` 这两行日志

`web/server.js` 用正则从 stdout 里解析它们：

```js
const m = out.match(/总耗时\s*:\s*(\d+)\s*ms/);
const c = out.match(/同步条数:\s*(\d+)/);
```

**格式不能改**，否则界面上的"同步条数/耗时"会变成 0。

---

## 3. Node：任务保存 / 触发 / 重置水位

改 `web/server.js` 三处 + 新增一个接口。

### 3.1 任务保存：写入模式与增量字段

**找到** `/api/job/save`，**替换整个分支**：

```js
        if (p === '/api/job/save' && req.method === 'POST') {
            const b = await readBody(req);
            if (!b.job_name || !b.source_datasource_id || !b.target_datasource_id) {
                return json(res, { code: 400, msg: '任务名与源/目标数据源不能为空' });
            }
            // 注意：数据库里的列叫 sync_type（不是 sync_mode）
            const syncType = String(b.sync_type || 'FULL').toUpperCase() === 'INCREMENT'
                ? 'INCREMENT' : 'FULL';
            const incField = syncType === 'INCREMENT' ? (b.increment_field || '').trim() : null;
            if (syncType === 'INCREMENT' && !incField) {
                return json(res, { code: 400, msg: '增量模式必须填写「增量字段」，如 update_time' });
            }

            if (b.id) {
                await query(`UPDATE sync_job SET
                    job_name=${sqlEscape(b.job_name)},
                    source_datasource_id=${parseInt(b.source_datasource_id)},
                    source_table=${sqlEscape(b.source_table || 't_order')},
                    target_datasource_id=${parseInt(b.target_datasource_id)},
                    target_table=${sqlEscape(b.target_table || 't_order')},
                    sync_type=${sqlEscape(syncType)},
                    increment_field=${sqlEscape(incField)},
                    batch_size=${parseInt(b.batch_size) || 1000},
                    status=${parseInt(b.status) || 1}
                    WHERE id=${parseInt(b.id)}`, ADMIN_DB);
                return json(res, { code: 200, msg: '修改成功' });
            }
            await query(`INSERT INTO sync_job
                (job_name, source_datasource_id, source_table, target_datasource_id, target_table,
                 sync_type, increment_field, cursor_field, batch_size, status)
                VALUES (${sqlEscape(b.job_name)}, ${parseInt(b.source_datasource_id)},
                        ${sqlEscape(b.source_table || 't_order')}, ${parseInt(b.target_datasource_id)},
                        ${sqlEscape(b.target_table || 't_order')}, ${sqlEscape(syncType)},
                        ${sqlEscape(incField)}, 'id',
                        ${parseInt(b.batch_size) || 1000}, ${parseInt(b.status) || 1})`, ADMIN_DB);
            return json(res, { code: 200, msg: '新增成功' });
        }
```

### 3.2 新增：重置水位接口

在 `/api/job/delete` **后面**插入：

```js
        // ---------- 重置水位（增量任务从头上重跑） ----------
        if (p === '/api/job/resetwatermark') {
            const id = parseInt(url.searchParams.get('id'));
            const rows = await query('SELECT id, job_name FROM sync_job WHERE id=' + id, ADMIN_DB);
            if (!rows.length) return json(res, { code: 404, msg: '任务不存在' });
            await query(`UPDATE sync_job
                         SET last_offset=NULL, last_pk=0, last_sync_time=NULL
                         WHERE id=${id}`, ADMIN_DB);
            return json(res, { code: 200, msg: '已重置 ' + rows[0].job_name + ' 的水位' });
        }
```

### 3.3 触发同步：只传 jobId，并区分模式

**替换** `runSync` 函数：

```js
// ------------------------------------------------------------------
// 同步执行
//
// 只把 jobId 传给 Java 引擎，任务配置（模式、增量字段、水位）由引擎从库里读。
// 这样调用方不需要知道任务细节，也避免位置参数传错。
// ------------------------------------------------------------------
function runSync(job) {
    return new Promise((resolve) => {
        const t0 = Date.now();
        const env = Object.assign({}, process.env, { JAVA_HOME });

        execFile(MVN, [
            '-B', '-q', '-pl', 'mini-sync-job', 'exec:java',
            '-Dexec.mainClass=com.minisync.job.DynamicSyncBootstrap',
            '-Dexec.args=' + String(job.id)
        ], { cwd: PROJECT, env, maxBuffer: 20 * 1024 * 1024, timeout: 600000 },
            (err, stdout, stderr) => {
                const out = (stdout || '') + (stderr || '');
                // 这两行的格式必须与 Java 侧保持一致，否则统计会变成 0
                const m = out.match(/总耗时\s*:\s*(\d+)\s*ms/);
                const c = out.match(/同步条数:\s*(\d+)/);
                const fe = out.match(/同步失败:\s*(.+)/);
                const watermark = out.match(/水位已落库: jobId=\d+, last_offset=([^,]+), last_pk=(\d+)/);

                resolve({
                    jobId: job.id,
                    mode: job.sync_type || 'FULL',
                    costMs: m ? parseInt(m[1]) : 0,
                    totalCount: c ? parseInt(c[1]) : 0,
                    watermark: watermark ? (watermark[1] + ' / ' + watermark[2]) : null,
                    wallMs: Date.now() - t0,
                    success: !!m,
                    errorMsg: m ? null
                        : (fe ? fe[1].trim() : String(err ? err.message : '未知错误').slice(0, 300)),
                    raw: out.split(/\r?\n/).filter(l => l.includes('INFO') || l.includes('ERROR')).slice(-30)
                });
            });
    });
}
```

**替换** `/api/run` 分支：

```js
        // ---------- 触发同步 ----------
        if (p === '/api/run') {
            const jobId = parseInt(url.searchParams.get('jobId'));
            const rows = await query('SELECT * FROM sync_job WHERE id=' + jobId, ADMIN_DB);
            if (!rows.length) return json(res, { code: 404, msg: '任务不存在' });

            const job = rows[0];
            const syncType = String(job.sync_type || 'FULL').toUpperCase();
            if (syncType === 'INCREMENT' && !job.increment_field) {
                return json(res, { code: 400, msg: '增量任务未配置「增量字段」，请先修改任务' });
            }

            const r = await runSync(job);

            // 失败时把错误信息也存进日志，便于界面上直接看到原因
            await query(`INSERT INTO sync_log
                (job_id, job_name, sync_mode, total_count, cost_ms, success, error_msg)
                VALUES (${job.id}, ${sqlEscape(job.job_name)}, ${sqlEscape(syncType)},
                        ${r.totalCount}, ${r.costMs}, ${r.success ? 1 : 0},
                        ${sqlEscape(r.errorMsg)})`, ADMIN_DB);

            return json(res, { code: 200, data: r });
        }
```

### 3.4 任务列表：不用改

`/api/job/list` 用的是 `SELECT j.*`，所以 `last_offset / last_pk / last_sync_time` 会自动带出来。

---

## 4. 前端：模式选择 + 上次水位 + 重置按钮

改 `web/index.html`。

### 4.1 任务弹窗：加「同步模式」和「增量字段」

在「批次大小」那个 `form-row` **后面**插入：

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
      <div class="hint" id="jobModeHint"></div>
```

### 4.2 任务列表：加「模式」和「上次水位」两列

表头改成（`colspan` 同时从 7 改成 **9**）：

```html
            <tr>
              <th style="width:60px">ID</th>
              <th>任务名称</th>
              <th>源数据源</th>
              <th>目标数据源</th>
              <th style="width:70px">批次</th>
              <th style="width:90px">模式</th>
              <th style="width:180px">上次水位</th>
              <th style="width:80px">状态</th>
              <th style="width:150px;white-space:nowrap">操作</th>
            </tr>
```

同时把空态那行的 `colspan="7"` 改成 `colspan="9"`。

### 4.3 JS：渲染行时补上两列

把 `loadJobs` 里生成表格的那段替换为：

```js
  document.getElementById('jobTable').innerHTML = jobCache.length ? jobCache.map(j => {
    const isInc = String(j.sync_type || 'FULL').toUpperCase() === 'INCREMENT';
    const mode = isInc
      ? '<span class="badge pg">增量</span>'
      : '<span class="badge gray">全量</span>';
    // 水位只在增量任务上有意义；全量任务显示"—"
    const wm = isInc
      ? (j.last_offset
          ? `<span class="mono" style="font-size:12px" title="${escapeAttr('last_offset=' + j.last_offset + '  last_pk=' + j.last_pk)}">${shorten(j.last_offset, 19)} / ${j.last_pk}</span>`
          : '<span class="cnt-na" title="尚未同步过，首次将全范围读取">未开始</span>')
      : '<span class="cnt-na">—</span>';
    return `
    <tr>
      <td>${j.id}</td>
      <td><b>${j.job_name}</b>${isInc && j.increment_field
            ? '<div style="font-size:12px;color:#8a919f;">增量字段: ' + escapeAttr(j.increment_field) + '</div>' : ''}</td>
      <td><span class="badge ${badgeCls(j.sourceType)}">${j.sourceType || '-'}</span>
          ${j.sourceName || '-'}</td>
      <td><span class="badge ${badgeCls(j.targetType)}">${j.targetType || '-'}</span>
          ${j.targetName || '-'}</td>
      <td class="mono">${j.batch_size}</td>
      <td>${mode}</td>
      <td>${wm}</td>
      <td><span class="badge ok">启用</span></td>
      <td style="white-space:nowrap">
        <span class="link" onclick="goRun(${j.id})">执行</span>
        <span class="link" onclick="openJobModal(${j.id})">修改</span>
        ${isInc ? `<span class="link" onclick="resetWatermark(${j.id})">重置水位</span>` : ''}
        <span class="link red" onclick="delJob(${j.id})">删除</span>
      </td>
    </tr>`;
  }).join('')
    : '<tr><td colspan="9" style="text-align:center;color:#8a919f;padding:30px;">暂无任务</td></tr>';
```

### 4.4 JS：模式切换、打开弹窗、保存、重置水位

新增/替换这几个函数：

```js
/** 切换任务模式时，决定是否显示「增量字段」 */
function onSyncTypeChange() {
  const isInc = document.getElementById('jobSyncType').value === 'INCREMENT';
  document.getElementById('rowIncrementField').style.display = isInc ? '' : 'none';
  document.getElementById('jobModeHint').textContent = isInc
    ? '增量模式：按「增量字段 + 主键」复合游标推进，每批成功后把水位写回数据库，中断后可续传。'
    : '全量模式：每次按主键游标分页搬整表，不维护水位。';
}

function openJobModal(id) {
  if (!dsCache.length) return toast('请先创建数据源');
  const j = id ? jobCache.find(x => x.id == id) : null;
  document.getElementById('jobModalTitle').textContent = j ? '修改同步任务' : '新建同步任务';
  document.getElementById('jobId').value = j ? j.id : '';
  document.getElementById('jobName').value = j ? j.job_name : '';
  document.getElementById('jobSource').value = j ? j.source_datasource_id : dsCache[0].id;
  document.getElementById('jobTarget').value = j ? j.target_datasource_id : dsCache[Math.min(1, dsCache.length - 1)].id;
  document.getElementById('jobSourceTable').value = j ? j.source_table : 't_order';
  document.getElementById('jobTargetTable').value = j ? j.target_table : 't_order';
  document.getElementById('jobBatch').value = j ? j.batch_size : 1000;
  document.getElementById('jobSyncType').value = j ? (j.sync_type || 'FULL') : 'FULL';
  document.getElementById('jobIncrementField').value = j ? (j.increment_field || '') : 'update_time';
  onSyncTypeChange();
  document.getElementById('jobMask').classList.add('show');
}

async function saveJob() {
  const body = {
    id: document.getElementById('jobId').value || null,
    job_name: document.getElementById('jobName').value.trim(),
    source_datasource_id: document.getElementById('jobSource').value,
    source_table: document.getElementById('jobSourceTable').value.trim(),
    target_datasource_id: document.getElementById('jobTarget').value,
    target_table: document.getElementById('jobTargetTable').value.trim(),
    batch_size: document.getElementById('jobBatch').value,
    sync_type: document.getElementById('jobSyncType').value,
    increment_field: document.getElementById('jobIncrementField').value.trim()
  };
  if (!body.job_name) return toast('请填写任务名称');
  if (body.source_datasource_id === body.target_datasource_id) {
    return toast('源和目标不能是同一个数据源');
  }
  if (body.sync_type === 'INCREMENT' && !body.increment_field) {
    return toast('增量模式需要填写增量字段');
  }

  const r = await fetch('/api/job/save', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body)
  }).then(x => x.json());

  toast(r.msg || '已保存');
  if (r.code === 200) { closeModal('jobMask'); loadJobs(); }
}

/** 重置水位：增量任务从头上重跑 */
async function resetWatermark(id) {
  if (!confirm('确认重置该任务的水位？下次增量同步将从表头重新开始。')) return;
  const r = await fetch('/api/job/resetwatermark?id=' + id).then(x => x.json());
  toast(r.msg || '已重置');
  loadJobs();
}
```

### 4.5 JS：执行结果区分成功/失败并显示水位

`doRun` 里处理返回值的部分替换为：

```js
    const d = (await fetch('/api/run?jobId=' + jobId).then(x => x.json())).data;
    (d.raw || []).forEach(l => logLine(l.replace(/^\S+\s+\S+\s+\S+\s+\S+\s+-\s+/, '')));

    if (!d.success) {
      logLine('同步失败：' + (d.errorMsg || '未知原因'), 'err');
      document.getElementById('runResult').innerHTML =
        `<div style="padding:12px 16px;background:#fdeaea;border-radius:6px;color:#d9534f;">
           同步失败：${escapeAttr(d.errorMsg || '未知原因')}
         </div>`;
    } else {
      logLine('同步条数：' + d.totalCount + ' 条', 'ok');
      logLine('总耗时：' + d.costMs + ' ms', 'ok');
      if (d.watermark) logLine('水位已落库：' + d.watermark, 'ok');
      document.getElementById('runResult').innerHTML =
        `<div style="padding:12px 16px;background:#e7f7f0;border-radius:6px;color:#17a673;">
          同步成功：${(d.totalCount || 0).toLocaleString()} 条，耗时 ${d.costMs} ms
          ${d.watermark ? '<div style="margin-top:6px;font-size:12px;color:#4a7c2f;">水位已落库：' + escapeAttr(d.watermark) + '</div>' : ''}
         </div>`;
    }
```

> 注意：Java 失败时进程退出码非 0，但 `fetch` 仍会拿到 HTTP 200 + `data.success=false`。
> 所以必须判断 `d.success`，不能只靠 try/catch。原来的 `fetch(...).then(x=>x.json())` 里
> 不要再写 `?mode=cursor`，模式完全由任务配置决定。

---

## 5. 编译与重启

```powershell
cd D:\mini-sync
$env:JAVA_HOME = "D:\jdk"

# 加载 .env 到当前进程（Node 与 Java 都需要）
Get-Content .env | ForEach-Object {
  if ($_ -match '^\s*([^#=]+)\s*=\s*(.*)$') {
    [Environment]::SetEnvironmentVariable($matches[1].Trim(), $matches[2].Trim(), 'Process')
  }
}

# 编译 Java
mvn -B clean install -DskipTests

# 重启控制台（改了 server.js 和 index.html 必须重启 Node）
Get-NetTCPConnection -LocalPort 8090 -State Listen -ErrorAction SilentlyContinue |
  ForEach-Object { Stop-Process -Id $_.OwningProcess -Force }
Start-Process node -ArgumentList "server.js" -WorkingDirectory "D:\mini-sync\web" -WindowStyle Hidden
```

---

## 6. 验证清单（这一步的真正价值）

### ✅ 6.1 增量任务能从界面创建

界面新建任务 → 同步模式选「增量」→ 增量字段填 `update_time` → 保存。

核对数据库：

```powershell
& $env:MINISYNC_MYSQL_BIN -u$env:MINISYNC_DB_USER -p mini_sync_admin `
  -e "SELECT id, job_name, sync_type, increment_field, last_offset, last_pk FROM sync_job ORDER BY id;"
```

期望：`sync_type=INCREMENT`、`increment_field=update_time`、`last_offset` 为 NULL。

### ✅ 6.2 增量真的只同步变更（核心验证）

```sql
-- 先把源表和目标表对齐（先跑一次全量任务）
-- 然后只改 100 行的 update_time
USE mini_sync_src;
UPDATE t_order SET update_time = NOW() WHERE id BETWEEN 1 AND 100;
```

点「执行」→ 期望日志 `同步条数: 100`（不是 10 万）。

查水位：

```powershell
& $env:MINISYNC_MYSQL_BIN -u$env:MINISYNC_DB_USER -p mini_sync_admin `
  -e "SELECT id, last_offset, last_pk, last_sync_time FROM sync_job WHERE sync_type='INCREMENT';"
```

期望：`last_offset` 有值，`last_pk` ≈ 100，`last_sync_time` 是刚才的时间。

### ✅ 6.3 水位生效：再跑一次应为 0 条

不动源表，再点一次「执行」。

期望：`同步条数: 0`，日志出现「本次无可推进的水位…保持原水位不变」。
**这一步证明水位被正确读取和比对**——如果没有水位，这里会重新搬 10 万条。

### ✅ 6.4 复合游标的边界验证（最能打的一条）

这一步直接验证你简历上「复合游标解决边界丢数据」的说法。

```sql
-- 制造"同一时间戳跨批次"的经典场景：
-- 2500 行 update_time 完全相同，而 batch_size=1000 → 必然跨 3 批
USE mini_sync_src;
UPDATE t_order SET update_time = '2026-01-01 00:00:00' WHERE id BETWEEN 1 AND 2500;
```

把任务 `batch_size` 改成 1000，重置水位，然后执行。

**预期结果**：
- 同步条数 ≈ 2500（允许因 upsert 重跑略多，但**不能少**）
- 目标表这 2500 行的数据与源表完全一致，无缺失

**如果换成单字段水位（`update_time > ?`）**，第二批会因为 `>` 而跳过剩余同一时间戳的行 —— 这正是要证明的反例。

验证无丢失（在目标库执行）：

```sql
SELECT COUNT(*) FROM mini_sync_dst.t_order WHERE update_time = '2026-01-01 00:00:00';
-- 期望 2500
```

### ✅ 6.5 断点续传（Windows 版）

> ⚠️ Windows 没有 `kill -9`，用 `Stop-Process -Force`。

```powershell
# 1. 准备 10 万行待同步数据
USE mini_sync_src;
UPDATE t_order SET update_time = NOW();

# 2. 把 batch_size 调小（比如 500），重置水位
# 3. 点「执行」，观察日志中"增量同步进行中, 已同步=..."
# 4. 跑到一半时，在另一个窗口强杀 Java 进程
Get-Process java -ErrorAction SilentlyContinue | Stop-Process -Force

# 5. 查水位：应该停在最后一批成功的位置，而不是 NULL
& $env:MINISYNC_MYSQL_BIN -u$env:MINISYNC_DB_USER -p mini_sync_admin `
  -e "SELECT last_offset, last_pk FROM sync_job WHERE sync_type='INCREMENT';"

# 6. 重新点「执行」（不要重置水位）
#    日志第一行的起始水位应等于第 5 步查到的值
```

期望：第 6 步的起始水位 = 第 5 步落库的水位，**不从表头重来**。

### ✅ 6.6 全量任务不受影响

挑一个 `sync_type='FULL'` 的旧任务执行一次。

期望：`同步条数: 100000`，行为与改动前一致。

### ✅ 6.7 重置水位可用

点任务行的「重置水位」→ 查库确认 `last_offset` 为 NULL、`last_pk` 为 0 → 再执行增量 → 应从表头重跑。

---

## 7. 常见错误对照

| 现象 | 原因 | 处理 |
|---|---|---|
| `Duplicate column name 'last_sync_time'` | 迁移脚本已执行过 | 正常，忽略 |
| `Unknown column 'sync_mode'` | 用了别的项目的列名 | 本项目列名是 `sync_type` |
| `增量同步必须配置增量字段` | `increment_field` 为空 | 在任务里补充 |
| `同步条数: 0` 但源表明明有变更 | 改动的不是 `increment_field` 指定的那一列 | 确认 UPDATE 的是同一列 |
| 界面上条数/耗时显示 0 | Java 日志格式被改了 | 恢复 `同步条数: {}` 与 `总耗时  : {} ms` 原格式 |
| 水位一直是 NULL | `saveWatermark` 没被调用，或 `Watermark.getWater()` 为 null | 看日志有没有「水位已落库」 |
| 改了前端没生效 | Node 没重启 / 浏览器缓存 | 重启 `node server.js` + `Ctrl+F5` |

---

## 8. 遗留问题（面试可以主动讲）

做完这一步后，以下几点仍是已知限制，**主动说出来比被问出来好**：

1. **失败时的水位组合有理论瑕疵**：`IncrementSyncException` 携带的是「目标端水位 + 源端主键」。
   正常回滚场景下两者一致；但如果目标端因历史原因超前，"用目标端水位配源端主键"理论上可能跳过极少量行。
   更严谨的做法是记录「最后一批成功提交的完整水位对」，而不是混用两端。

2. **增量依赖源表时间字段被正确维护**：如果业务的 `update_time` 不随更新变化（例如手写 SQL 忘了带），
   增量会漏数据。生产上更可靠的是 CDC（binlog），这是增量同步的行业共识。

3. **批次计数仍是乐观计数**：`total += batch.size()`，未核对目标端实际写入行数。属后续改进项。

4. **全量同步仍无断点**：本阶段只给增量加水位。全量的断点需要按主键范围分段持久化，语义与增量不同。

---

## 完成标准

- [ ] `sql/migration_increment.sql` 执行成功，`last_sync_time` 字段存在
- [ ] 界面能创建增量任务（含增量字段）
- [ ] 执行增量任务，日志显示进入 `executeIncrementSync`
- [ ] 只变更 100 行时，同步条数为 100 而不是 10 万
- [ ] 不变更时再次执行，同步条数为 0
- [ ] 2500 行同时间戳 + batch 1000 的边界测试无数据丢失
- [ ] 强杀进程后，水位停在最后成功的批次，重启从该位置续传
- [ ] 全量任务行为不变
- [ ] 重置水位后能从头重跑

**九项全过，简历上「增量同步 + 断点续传 + 失败回写目标端水位」就是真的了，可以现场演示。**
