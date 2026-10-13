/**
 * mini-sync 控制台后端
 *
 * 提供：
 *   - 登录 / 登出（演示级，token 存内存）
 *   - 数据源 CRUD（持久化到 mini_sync_admin.datasource_config）
 *   - 同步任务 CRUD
 *   - 动态触发同步（读任务配置 → 调 Java 执行器）
 *   - 执行历史
 *
 * 设计说明：管理台只是「数据同步引擎」的外挂展示层，
 * 真正的同步逻辑在 Java 侧的 SyncJobExecutor 里。
 */
const http = require('http');
const fs = require('fs');
const os = require('os');
const path = require('path');
const { execFile } = require('child_process');

// 零依赖读取项目根目录 .env：系统环境变量优先，.env 补充未设置项。
// Java 子进程继承 process.env，因此 Node 控制台触发的 Java 同步也能读取同一配置。
function loadEnvFile(filePath) {
    if (!fs.existsSync(filePath)) return;
    const lines = fs.readFileSync(filePath, 'utf8').split(/\r?\n/);
    for (const line of lines) {
        const trimmed = line.trim();
        if (!trimmed || trimmed.startsWith('#')) continue;
        const m = trimmed.match(/^([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$/);
        if (!m || process.env[m[1]] !== undefined) continue;
        let value = m[2].trim();
        if ((value.startsWith('"') && value.endsWith('"')) ||
            (value.startsWith("'") && value.endsWith("'"))) {
            value = value.slice(1, -1);
        }
        process.env[m[1]] = value;
    }
}
loadEnvFile(path.resolve(__dirname, '..', '.env'));

const PORT = process.env.MINISYNC_PORT ? parseInt(process.env.MINISYNC_PORT) : 8090;
const ROOT = __dirname;

// ------------------------------------------------------------------
// 配置外置
//
// 所有环境相关、含敏感信息的配置都从【环境变量】读取，代码里不留硬编码口令。
// 这样做的意义不是"把密码藏起来"（本地测试口令谈不上藏），而是：
//   1. 同一份代码可以跑在不同环境，不需要改代码
//   2. 提交到版本库时不带敏感信息
//   3. 生产上可以接密钥管理系统，只改部署配置
//
// 每个变量都提供开发期默认值，保证 clone 下来 `node server.js` 直接能跑。
// ------------------------------------------------------------------
const MYSQL = process.env.MINISYNC_MYSQL_BIN
    || 'C:\\Program Files\\MySQL\\MySQL Server 5.7\\bin\\mysql.exe';
const DB_USER = process.env.MINISYNC_DB_USER || 'root';
const DB_PWD  = process.env.MINISYNC_DB_PWD || '';
const ADMIN_DB = process.env.MINISYNC_ADMIN_DB || 'mini_sync_admin';
const DEFAULT_DS_PASSWORD = process.env.MINISYNC_DEFAULT_DS_PWD || '';
if (!DB_PWD) {
    console.error('[config] 缺少 MINISYNC_DB_PWD。请复制项目根目录 .env.example 为 .env，填写密码后重新启动。');
    process.exit(1);
}

// Maven / JDK 路径按本机实际安装位置配置，也可用环境变量覆盖
const MVN       = process.env.MINISYNC_MVN      || 'D:\\apache-maven-3.9.16\\bin\\mvn.cmd';
const JAVA_HOME = process.env.MINISYNC_JAVA_HOME || 'D:\\jdk';
const PROJECT   = path.resolve(__dirname, '..');

// 只报告凭据已配置，不打印口令内容。
console.log('[config] MINISYNC_DB_PWD 已从环境变量/.env 加载');

// 驱动类型查询用的入口类（打印全部已注册插件后退出）
const DRIVER_TYPES_CLASS = 'com.minisync.job.DriverTypeLister';
// 通用查询入口：行数统计 / 数据预览都走它，按类型自动选驱动
const SQL_QUERY_CLASS = 'com.minisync.job.SqlQueryBootstrap';

// ------------------------------------------------------------------
// SQL 执行：统一走 mysql 命令行
// ------------------------------------------------------------------
function sqlEscape(v) {
    if (v === null || v === undefined) return 'NULL';
    if (typeof v === 'number') return String(v);
    return "'" + String(v).replace(/\\/g, '\\\\').replace(/'/g, "\\'") + "'";
}

function query(sql, database) {
    return new Promise((resolve, reject) => {
        const args = ['-u' + DB_USER, '-p' + DB_PWD, '--default-character-set=utf8mb4', '-B'];
        if (database) args.push(database);
        args.push('-e', sql);

        execFile(MYSQL, args, { maxBuffer: 20 * 1024 * 1024 }, (err, stdout, stderr) => {
            if (err && !stdout) return reject(new Error(stderr || err.message));
            const lines = stdout.trim().split(/\r?\n/).filter(Boolean);
            if (lines.length < 2) return resolve([]);
            const head = lines[0].split('\t');
            resolve(lines.slice(1).map(line => {
                const cells = line.split('\t');
                const o = {};
                head.forEach((h, i) => { o[h] = cells[i] === 'NULL' ? null : cells[i]; });
                return o;
            }));
        });
    });
}

/**
 * 在指定数据源上执行一条 SQL，返回 { rows, cols }。
 *
 * <p><b>双通道设计：MySQL / PostgreSQL 走本地工具，其它类型走 Java 引擎。</b></p>
 *
 * <p>为什么不全部走引擎？引擎链路是 {@code Node -> mvn.cmd -> JVM}，
 * 单次约 17 秒（Maven 启动占大头）。MySQL 用本地 {@code mysql.exe}
 * 只要几百毫秒，差两个数量级。</p>
 *
 * <p>控制台的「行数探测」在页面加载时对每个数据源各触发一次，
 * 4 个数据源全走引擎实测 68 秒，等于不可用。所以：
 * <b>有本地通道就用（快），没有才走引擎（慢但能work）</b>。</p>
 *
 * <p>本地通道只能覆盖有命令行工具的库。SQLite / ClickHouse 这类
 * <b>只在插件 jar 里有 JDBC 驱动</b>的类型没有本地工具，只能走引擎。</p>
 *
 * <p><b>这依然保留了插件化的核心价值</b>：新类型无需改 Node 任何代码，
 * 只是探询速度受限于 Maven 启动。
 * 要彻底解决需改成常驻 Java 服务（起一次 JVM，之后 HTTP 毫秒级），
 * 那属于架构调整，先记在这里。</p>
 *
 * @param {number} limit 限制返回行数，0 表示不限制
 */
function queryOn(ds, sql, limit) {
    return new Promise((resolve, reject) => {
        if (ds.ds_type === 'MYSQL') {
            query(limit > 0 ? wrapLimit(sql, limit) : sql, ds.database_name)
                .then(rows => resolve({ rows: toRows(rows), cols: colsOf(rows) }))
                .catch(reject);
            return;
        }
        if (ds.ds_type === 'POSTGRESQL') {
            execFile('docker', [
                'exec', 'mini-sync-pg', 'psql', '-U', ds.username, '-d', ds.database_name,
                '-t', '-A', '-F', '\t', '-c', (limit > 0 ? wrapLimit(sql, limit) : sql)
            ], { maxBuffer: 20 * 1024 * 1024 }, (err, stdout, stderr) => {
                if (err && !stdout) return reject(new Error(stderr || err.message));
                // PG 的 -t -A 模式不带列名，cols 留空，由调用方用别名兜底
                resolve({ rows: stdout.trim().split(/\r?\n/)
                        .filter(l => l.trim()).map(l => l.split('\t')), cols: '' });
            });
            return;
        }
        // 其它类型（SQLITE / CLICKHOUSE / ...）—— 插件驱动，必须走引擎
        sqlQueryViaEngine(ds, sql, limit).then(resolve).catch(reject);
    });
}

/** 包一层 limit，避免一次拉全表 */
function wrapLimit(sql, limit) {
    const t = String(sql).trim().replace(/;+$/, '');
    if (!/^select/i.test(t)) return sql;
    return 'SELECT * FROM (' + t + ') __minisync_limit LIMIT ' + limit;
}

/** 本地 mysql.exe 返回的对象数组 → 二维数组 */
function toRows(rows) {
    if (!rows || !rows.length) return [];
    const keys = Object.keys(rows[0]);
    return rows.map(r => keys.map(k => r[k]));
}

/** 本地结果的对象数组 → 列名串 */
function colsOf(rows) {
    if (!rows || !rows.length) return '';
    return Object.keys(rows[0]).join(',');
}

/**
 * 走 Java 引擎执行 SQL（仅插件类型用）。
 *
 * <p><b>为什么 SQL 要写进临时文件再传路径？</b>
 * 调用链是 {@code Node -> mvn.cmd(batch) -> java}，共三层。
 * batch 对命令行引号解析不可靠 —— 含空格的 SQL 会被拆成多个参数，
 * 表现为 {@code The syntax of the command is incorrect}。
 * 所以 SQL 写临时文件，参数只传文件路径（无空格、无特殊字符），
 * 彻底绕开三层 shell 的转义问题。</p>
 */
function sqlQueryViaEngine(ds, sql, limit) {
    return new Promise((resolve, reject) => {
        const env = Object.assign({}, process.env, { JAVA_HOME });

        const sqlFile = path.join(os.tmpdir(),
            'minisync-sql-' + Date.now() + '-' + Math.random().toString(36).slice(2) + '.sql');
        fs.writeFileSync(sqlFile, sql, 'utf8');

        const a = [
            ds.ds_type,
            ds.host || '-',
            // SQLite 等没有端口意义，port=0 时传 '-' 让 Java 侧忽略
            (ds.port == null || ds.port === 0) ? '-' : String(ds.port),
            ds.database_name,
            ds.username || '-',
            ds.password || '-',
            '@' + sqlFile,            // SQL 走文件
            String(limit == null ? 0 : limit)
        ];

        const cleanup = () => { try { fs.unlinkSync(sqlFile); } catch (e) {} };

        execFile(MVN, [
            '-B', '-q', '-pl', 'mini-sync-job', 'exec:java',
            '-Dexec.mainClass=' + SQL_QUERY_CLASS,
            '-Dexec.args=' + a.join(' ')
        ], { cwd: PROJECT, env, maxBuffer: 20 * 1024 * 1024, timeout: 180000 },
            (err, stdout, stderr) => {
                cleanup();
                const out = (stdout || '') + (stderr || '');
                const rows = [];
                let cols = '';
                let error = null;

                out.split(/\r?\n/).forEach(line => {
                    if (line.startsWith('ROW ')) {
                        rows.push(line.slice(4).split('\t'));
                    } else if (line.startsWith('QUERY_END')) {
                        const m = line.match(/cols=(\S*)\s*(.*)$/);
                        if (m) {
                            cols = m[1] || '';
                            const em = m[2].match(/error=(.*)$/);
                            if (em && em[1]) error = em[1];
                        }
                    }
                });

                // 协议里没有 QUERY_END，说明 JVM 没正常启动（mvn/batch 层就挂了）
                if (!out.includes('QUERY_END')) {
                    return reject(new Error(
                        '引擎未返回结果，可能启动失败。末尾输出：' +
                        out.split(/\r?\n/).filter(Boolean).slice(-4).join(' | ')));
                }
                if (error) return reject(new Error(error));
                resolve({ rows, cols });
            });
    });
}

/** 预览接口的固定列顺序（本地通道可能不带列名，用它兜底） */
const DEFAULT_PREVIEW_COLS = ['id', 'order_no', 'user_id', 'amount', 'status'];

/** 把 { rows, cols } 转成对象数组，便于 JSON 给前端 */
function rowsToObjects(result, fallbackCols) {
    const names = (result.cols || '').split(',').filter(Boolean);
    const use = names.length ? names : (fallbackCols || DEFAULT_PREVIEW_COLS);
    return result.rows.map(cells => {
        const o = {};
        cells.forEach((v, i) => { o[use[i] || ('col' + i)] = v; });
        return o;
    });
}

/** 取某数据源的行数。table 默认 t_order */
async function countRows(ds, table) {
    table = table || 't_order';
    const result = await queryOn(ds, 'SELECT COUNT(*) AS c FROM ' + table, 0);
    const list = rowsToObjects(result, ['c']);
    return parseInt(list[0] && list[0].c) || 0;
}

// ------------------------------------------------------------------
// 同步执行
// ------------------------------------------------------------------
function runSync(job, mode, batchSize) {
    return new Promise((resolve) => {
        const t0 = Date.now();
        const env = Object.assign({}, process.env, { JAVA_HOME });
        // 统一走 CrossDbBootstrap，由它根据源/目标类型自动选择驱动
        const args = [mode, batchSize,
            String(job.source_datasource_id), String(job.target_datasource_id),
            job.source_table, job.target_table].join(' ');

        execFile(MVN, [
            '-B', '-q', '-pl', 'mini-sync-job', 'exec:java',
            '-Dexec.mainClass=com.minisync.job.DynamicSyncBootstrap',
            '-Dexec.args=' + args
        ], { cwd: PROJECT, env, maxBuffer: 20 * 1024 * 1024, timeout: 600000 },
            (err, stdout, stderr) => {
                const out = (stdout || '') + (stderr || '');
                const m = out.match(/总耗时\s*:\s*(\d+)\s*ms/);
                const c = out.match(/同步条数:\s*(\d+)/);
                resolve({
                    jobId: job.id,
                    mode,
                    costMs: m ? parseInt(m[1]) : 0,
                    totalCount: c ? parseInt(c[1]) : 0,
                    wallMs: Date.now() - t0,
                    success: !!m,
                    raw: out.split(/\r?\n/).filter(l => l.includes('INFO') || l.includes('ERROR')).slice(-30)
                });
            });
    });
}

// ------------------------------------------------------------------
// 驱动类型查询：问 Java 引擎"你注册了哪些数据源类型"
// ------------------------------------------------------------------
function listDriverTypes() {
    return new Promise((resolve, reject) => {
        const env = Object.assign({}, process.env, { JAVA_HOME });
        const t0 = Date.now();
        execFile(MVN, [
            '-B', '-q', '-pl', 'mini-sync-job', 'exec:java',
            '-Dexec.mainClass=' + DRIVER_TYPES_CLASS
        ], { cwd: PROJECT, env, maxBuffer: 10 * 1024 * 1024, timeout: 180000 },
            (err, stdout, stderr) => {
                const out = (stdout || '') + (stderr || '');
                // 引擎按固定协议输出：DRIVER_TYPE <TYPE> <显示名> <来源jar>
                // 用 [^...]+ 而非 .+，避免把 jar 名吃进显示名
                const types = [];
                out.split(/\r?\n/).forEach(line => {
                    const m = line.match(/^\s*DRIVER_TYPE\s+(\S+)\s+(.+?)\s+(\S+\.jar)\s*$/);
                    if (m) types.push({ type: m[1], display: m[2], jar: m[3] });
                });
                if (!types.length) {
                    return reject(new Error('未解析到驱动类型，引擎输出：' +
                        out.split(/\r?\n/).slice(-5).join(' | ')));
                }
                resolve(types);
            });
    });
}

// ------------------------------------------------------------------
// 静态页面发送
// ------------------------------------------------------------------
/**
 * 发送 HTML 文件，并禁用所有缓存。
 *
 * 为什么必须禁用缓存：本项目没有构建步骤，改完 html 直接刷新就能看到效果。
 * 但浏览器默认会缓存 html，导致用户拿到旧页面 ——
 * 现象是"服务端代码是新的，但页面行为还是老的"，非常难排查。
 */
function sendHtml(res, filePath) {
    // max-age=0 + must-revalidate：允许缓存但每次都要问服务端验新鲜度；
    // no-store：更彻底，干脆不落盘。二者叠加最稳。
    const headers = {
        'Content-Type': 'text/html; charset=utf-8',
        'Cache-Control': 'no-store, no-cache, must-revalidate, proxy-revalidate, max-age=0',
        'Pragma': 'no-cache',
        'Expires': '0',
        'Last-Modified': new Date().toUTCString()
    };
    // 文件不存在时给一个可读的错误页，而不是裸的 ECONNRESET
    if (!fs.existsSync(filePath)) {
        res.writeHead(404, headers);
        return res.end('<h3>404</h3><p>页面不存在: ' + path.basename(filePath) + '</p>');
    }
    res.writeHead(200, headers);
    res.end(fs.readFileSync(filePath));
}

// ------------------------------------------------------------------
// HTTP 工具
// ------------------------------------------------------------------
function json(res, data, code = 200) {
    res.writeHead(code, {
        'Content-Type': 'application/json; charset=utf-8',
        'Access-Control-Allow-Origin': '*'
    });
    res.end(JSON.stringify(data));
}

function readBody(req) {
    return new Promise(resolve => {
        let b = '';
        req.on('data', c => b += c);
        req.on('end', () => {
            try { resolve(b ? JSON.parse(b) : {}); } catch (e) { resolve({}); }
        });
    });
}

// 演示级会话：token -> 用户
const sessions = new Map();

const server = http.createServer(async (req, res) => {
    const url = new URL(req.url, 'http://localhost');
    const p = url.pathname;

    try {
        // ---------- 静态页 ----------
        // 统一走 sendHtml：开发期反复改页面，必须让浏览器每次都重新取，
        // 否则用户会拿到缓存里的旧版 login.html / index.html，
        // 表现为"代码明明是好的但功能不生效"这类难以排查的怪问题。
        if (p === '/' || p === '/login.html') {
            return sendHtml(res, path.join(ROOT, 'login.html'));
        }
        if (p === '/index.html') {
            return sendHtml(res, path.join(ROOT, 'index.html'));
        }

        // ---------- 登录 ----------
        if (p === '/api/login' && req.method === 'POST') {
            const body = await readBody(req);
            if (!body.username || !body.password) {
                return json(res, { code: 400, msg: '请输入账号和密码' });
            }
            const rows = await query(
                `SELECT id, username, nickname FROM sys_user
                 WHERE username=${sqlEscape(body.username)}
                   AND password=${sqlEscape(body.password)} LIMIT 1`, ADMIN_DB);
            if (!rows.length) {
                // 把用户名回显在错误里，方便区分"账号打错"还是"密码打错"。
                // 这是本地演示工具，不存在撞库风险，可读性优先。
                return json(res, {
                    code: 401,
                    msg: '用户名或密码错误（账号：' + body.username + '）'
                });
            }
            const token = 'tk_' + Date.now() + '_' + Math.random().toString(36).slice(2);
            sessions.set(token, rows[0]);
            return json(res, { code: 200, data: { token, user: rows[0] } });
        }

        if (p === '/api/logout') {
            const t = url.searchParams.get('token');
            if (t) sessions.delete(t);
            return json(res, { code: 200 });
        }

        // ---------- 已注册驱动类型（插件化的直接体现） ----------
        // 前端数据源类型下拉不再写死 MySQL/PG，而是问引擎"你现在认识哪些类型"。
        // 新增驱动插件后，这里自动多一项，前端无需改动。
        if (p === '/api/drivertype/list') {
            try {
                const types = await listDriverTypes();
                return json(res, { code: 200, data: types });
            } catch (e) {
                // 引擎启动失败时不能拖垮页面，退回内置类型让用户仍能操作
                return json(res, {
                    code: 200,
                    data: [{ type: 'MYSQL', display: 'MySQL' },
                           { type: 'POSTGRESQL', display: 'PostgreSQL' }],
                    fallback: true,
                    msg: '驱动类型查询失败：' + e.message
                });
            }
        }

        // ---------- 数据源 CRUD ----------
        if (p === '/api/datasource/list') {
            const rows = await query('SELECT * FROM datasource_config ORDER BY id', ADMIN_DB);

            // 逐个探测行数。三种结果要分清，不能一律显示 0：
            //   rowCount 有值  → 在线且查到了
            //   rowCount=null  → 探测失败（离线/表不存在/驱动缺失），前端显示"—"
            // 用一个 rowCountError 字段把原因带上，方便界面上给提示
            for (const d of rows) {
                try {
                    d.rowCount = await countRows(d, 't_order');
                    d.rowCountError = null;
                    d.online = true;
                } catch (e) {
                    d.rowCount = null;
                    d.online = false;
                    d.rowCountError = String(e.message).slice(0, 200);
                    console.warn('[datasource] 探测失败 id=' + d.id
                        + ' name=' + d.ds_name + ' : ' + d.rowCountError);
                }
                // 绝不把数据源密码回传给浏览器；编辑时密码留空表示沿用数据库原值。
                delete d.password;
            }
            return json(res, { code: 200, data: rows });
        }

        if (p === '/api/datasource/save' && req.method === 'POST') {
            const b = await readBody(req);
            if (!b.ds_name || !b.database_name) {
                return json(res, { code: 400, msg: '名称与数据库名不能为空' });
            }
            const type = String(b.ds_type || 'MYSQL').toUpperCase();
            const isSqlite = type === 'SQLITE';
            const host = isSqlite ? '' : (b.host || '127.0.0.1');
            const port = isSqlite ? 0 : (parseInt(b.port) || (type === 'POSTGRESQL' ? 5432 : 3306));
            const username = isSqlite ? '' : (b.username || '');
            const password = isSqlite ? '' : (b.password || DEFAULT_DS_PASSWORD);
            if (b.id == null && !isSqlite && !password) {
                return json(res, { code: 400, msg: '请填写数据库密码，或在 .env 配置 MINISYNC_DEFAULT_DS_PWD' });
            }

            if (b.id) {
                const id = parseInt(b.id);
                const currentRows = await query(
                    'SELECT password FROM datasource_config WHERE id=' + id, ADMIN_DB);
                if (!currentRows.length) return json(res, { code: 404, msg: '数据源不存在' });
                // 编辑时密码输入框留空表示沿用现有口令；后端不把密码发回前端。
                const nextPassword = isSqlite ? '' : (b.password ? b.password : currentRows[0].password);
                await query(`UPDATE datasource_config SET
                    ds_name=${sqlEscape(b.ds_name)}, ds_type=${sqlEscape(type)},
                    host=${sqlEscape(host)}, port=${port},
                    database_name=${sqlEscape(b.database_name)}, username=${sqlEscape(username)},
                    password=${sqlEscape(nextPassword)}, remark=${sqlEscape(b.remark)}
                    WHERE id=${id}`, ADMIN_DB);
                return json(res, { code: 200, msg: '修改成功' });
            }
            await query(`INSERT INTO datasource_config
                (ds_name, ds_type, host, port, database_name, username, password, remark)
                VALUES (${sqlEscape(b.ds_name)}, ${sqlEscape(type)},
                        ${sqlEscape(host)}, ${port},
                        ${sqlEscape(b.database_name)}, ${sqlEscape(username)},
                        ${sqlEscape(password)}, ${sqlEscape(b.remark)})`, ADMIN_DB);
            return json(res, { code: 200, msg: '新增成功' });
        }

        if (p === '/api/datasource/delete') {
            const id = parseInt(url.searchParams.get('id'));
            await query('DELETE FROM datasource_config WHERE id=' + id, ADMIN_DB);
            return json(res, { code: 200, msg: '删除成功' });
        }

        if (p === '/api/datasource/test') {
            const id = parseInt(url.searchParams.get('id'));
            const rows = await query('SELECT * FROM datasource_config WHERE id=' + id, ADMIN_DB);
            if (!rows.length) return json(res, { code: 404, msg: '数据源不存在' });
            try {
                const n = await countRows(rows[0], 't_order');
                return json(res, {
                    code: 200,
                    msg: '连接成功，t_order 现有 ' + n.toLocaleString() + ' 行',
                    rowCount: n
                });
            } catch (e) {
                // 把真实原因返回去，前端 toast 能看到"是什么导致连不上"
                return json(res, { code: 500, msg: '连接失败：' + e.message });
            }
        }

        // ---------- 同步任务 CRUD ----------
        if (p === '/api/job/list') {
            const jobs = await query(`
                SELECT j.*, s.ds_name AS sourceName, s.ds_type AS sourceType,
                       t.ds_name AS targetName, t.ds_type AS targetType
                FROM sync_job j
                LEFT JOIN datasource_config s ON j.source_datasource_id = s.id
                LEFT JOIN datasource_config t ON j.target_datasource_id = t.id
                ORDER BY j.id DESC`, ADMIN_DB);
            return json(res, { code: 200, data: jobs });
        }

        if (p === '/api/job/save' && req.method === 'POST') {
            const b = await readBody(req);
            if (!b.job_name || !b.source_datasource_id || !b.target_datasource_id) {
                return json(res, { code: 400, msg: '任务名与源/目标数据源不能为空' });
            }
            if (b.id) {
                await query(`UPDATE sync_job SET
                    job_name=${sqlEscape(b.job_name)},
                    source_datasource_id=${parseInt(b.source_datasource_id)},
                    source_table=${sqlEscape(b.source_table || 't_order')},
                    target_datasource_id=${parseInt(b.target_datasource_id)},
                    target_table=${sqlEscape(b.target_table || 't_order')},
                    batch_size=${parseInt(b.batch_size) || 1000},
                    status=${parseInt(b.status) || 1}
                    WHERE id=${parseInt(b.id)}`, ADMIN_DB);
                return json(res, { code: 200, msg: '修改成功' });
            }
            await query(`INSERT INTO sync_job
                (job_name, source_datasource_id, source_table, target_datasource_id, target_table,
                 sync_type, cursor_field, batch_size, status)
                VALUES (${sqlEscape(b.job_name)}, ${parseInt(b.source_datasource_id)},
                        ${sqlEscape(b.source_table || 't_order')}, ${parseInt(b.target_datasource_id)},
                        ${sqlEscape(b.target_table || 't_order')}, 'FULL', 'id',
                        ${parseInt(b.batch_size) || 1000}, ${parseInt(b.status) || 1})`, ADMIN_DB);
            return json(res, { code: 200, msg: '新增成功' });
        }

        if (p === '/api/job/delete') {
            const id = parseInt(url.searchParams.get('id'));
            await query('DELETE FROM sync_job WHERE id=' + id, ADMIN_DB);
            return json(res, { code: 200, msg: '删除成功' });
        }

        // ---------- 触发同步 ----------
        if (p === '/api/run') {
            const jobId = parseInt(url.searchParams.get('jobId'));
            const mode = url.searchParams.get('mode') || 'cursor';
            const rows = await query('SELECT * FROM sync_job WHERE id=' + jobId, ADMIN_DB);
            if (!rows.length) return json(res, { code: 404, msg: '任务不存在' });

            const job = rows[0];
            const r = await runSync(job, mode, job.batch_size || 1000);

            await query(`INSERT INTO sync_log
                (job_id, job_name, sync_mode, total_count, cost_ms, success, error_msg)
                VALUES (${job.id}, ${sqlEscape(job.job_name)}, ${sqlEscape(mode)},
                        ${r.totalCount}, ${r.costMs}, ${r.success ? 1 : 0}, NULL)`, ADMIN_DB);

            return json(res, { code: 200, data: r });
        }

        // ---------- 执行历史 ----------
        if (p === '/api/logs') {
            const rows = await query(
                'SELECT * FROM sync_log ORDER BY id DESC LIMIT 15', ADMIN_DB);
            return json(res, { code: 200, data: rows });
        }

        // ---------- 数据预览 ----------
        if (p === '/api/preview') {
            const id = parseInt(url.searchParams.get('id'));
            const rows = await query('SELECT * FROM datasource_config WHERE id=' + id, ADMIN_DB);
            if (!rows.length) return json(res, { code: 404, msg: '数据源不存在' });
            try {
                // 全类型统一入口（本地通道 + 引擎自动分流）
                const result = await queryOn(rows[0],
                    'SELECT id, order_no, user_id, amount, status FROM t_order ORDER BY id', 8);
                return json(res, { code: 200, data: rowsToObjects(result) });
            } catch (e) {
                return json(res, { code: 500, msg: e.message, data: [] });
            }
        }

        // ---------- 清空某数据源的表 ----------
        if (p === '/api/truncate') {
            const id = parseInt(url.searchParams.get('id'));
            const rows = await query('SELECT * FROM datasource_config WHERE id=' + id, ADMIN_DB);
            if (!rows.length) return json(res, { code: 404, msg: '数据源不存在' });
            const ds = rows[0];
            // SQLite 没有 TRUNCATE，用 DELETE 清空（保留表结构）
            const sql = ds.ds_type === 'SQLITE'
                ? 'DELETE FROM t_order;'
                : 'TRUNCATE TABLE t_order;';
            try {
                await queryOn(ds, sql, 0);
                return json(res, { code: 200, msg: '已清空 ' + ds.ds_name + ' 的 t_order' });
            } catch (e) {
                return json(res, { code: 500, msg: '清空失败：' + e.message });
            }
        }

        res.writeHead(404);
        res.end('Not Found');
    } catch (e) {
        json(res, { code: 500, msg: e.message }, 500);
    }
});

server.listen(PORT, () => {
    console.log('mini-sync 控制台已启动: http://localhost:' + PORT);
});
