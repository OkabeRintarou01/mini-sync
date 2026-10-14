package com.minisync.server.store;

/**
 * 同步运行状态与任务锁的存储抽象。
 *
 * <p><b>为什么要有这个接口？</b></p>
 *
 * <p>这两类数据有个共同特征：<b>它们是"临时状态"，但必须跨进程共享。</b></p>
 *
 * <table border="1">
 *   <caption>为什么不能只放内存</caption>
 *   <tr><th>场景</th><th>纯内存的问题</th></tr>
 *   <tr><td>服务重启</td><td>正在跑的任务进度全丢，用户进度条直接消失</td></tr>
 *   <tr><td>多实例部署</td><td>实例 A 执行任务，请求被负载均衡到实例 B → 查不到进度</td></tr>
 *   <tr><td>并发触发</td><td>各实例一把锁 → 同一任务被两个实例同时执行 → 数据搬两遍</td></tr>
 * </table>
 *
 * <p>所以抽出接口，提供两套实现：</p>
 * <ul>
 *   <li>{@code InMemoryRunStateStore} —— 单机开发用，零依赖</li>
 *   <li>{@code RedisRunStateStore} —— 多实例/生产用</li>
 * </ul>
 *
 * <p>通过 {@code minisync.redis.enabled} 配置切换。
 * 这样<b>没装 Redis 也能把项目跑起来</b>，装了则自动获得跨实例能力。</p>
 *
 * <p><b>数据模型</b>：读出来是不可变快照（{@link SyncRunState}），
 * 改完再存回去。不用"可变对象 + 引用"的模型，因为 Redis 实现天然做不到原地修改。</p>
 */
public interface RunStateStore {

    /**
     * 创建一次运行记录。
     *
     * @return 新建的运行状态（status = RUNNING）
     */
    SyncRunState createRun(Long jobId, String jobName, String syncType);

    /**
     * 尝试占用某个任务，防止并发执行。
     *
     * <p><b>必须保证原子性</b>：两个实例/线程同时调用，只能有一个返回 true。
     * 内存版用 {@code putIfAbsent}，Redis 版用 {@code SET key value NX EX ttl}。</p>
     *
     * <p><b>必须有超时</b>：如果持有锁的进程崩溃且没来得及释放，
     * 锁要能自动过期，否则任务会被永久卡住。</p>
     *
     * @return 拿到锁返回 true
     */
    boolean tryLockJob(Long jobId, String runId);

    /**
     * 释放任务锁。
     *
     * <p><b>只能释放自己持有的锁</b>：如果任务 A 的锁已因超时被任务 B 拿到，
     * A 执行完不应该把 B 的锁删掉。Redis 版用 Lua 脚本做"值匹配才删"。</p>
     */
    void unlockJob(Long jobId, String runId);

    /** 该任务当前是否有人持有锁 */
    boolean isRunning(Long jobId);

    /** 按 runId 取运行状态，不存在返回 null */
    SyncRunState getRun(String runId);

    /** 取某任务最近一次运行（前端刷新页面后恢复显示用），没有则返回 null */
    SyncRunState latestByJob(Long jobId);

    /**
     * 追加一行日志。
     *
     * <p>实现需要限制日志行数上限（内存版保留最近 200 行，
     * Redis 版用 {@code LTRIM}），避免长任务把存储撑爆。</p>
     */
    void appendLog(String runId, String line);

    /** 更新已同步条数（用于前端显示"已处理 N 条"） */
    void updateProgress(String runId, int totalCount);

    /** 结束一次运行：写入终态、耗时、水位/错误信息 */
    void finishRun(String runId, String status, int totalCount, long costMs,
                   String watermark, String errorMsg);

    /** 清理过期的运行记录（内存版手动调用；Redis 版靠 TTL 自动过期） */
    void purgeExpired(int olderThanMinutes);

    /** 当前生效的实现名，用于健康检查显示（in-memory / redis） */
    String backendName();
}
