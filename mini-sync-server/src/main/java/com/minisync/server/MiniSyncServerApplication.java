package com.minisync.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * mini-sync 常驻 HTTP 服务入口。
 *
 * <p><b>为什么要有这个服务？</b></p>
 *
 * <p>阶段一之前，控制台是 Node.js 写的，每点一次「同步」或「查询行数」，
 * Node 都会通过 {@code mvn exec:java} 启动一次 Maven + JVM。
 * 实测代价：查 4 个数据源的行数要 17 秒，其中绝大部分是 Maven 启动开销。</p>
 *
 * <p>改成常驻服务后，JVM 只启动一次，后续请求在同进程内直接处理，
 * 同一个查询可以降到毫秒级。</p>
 *
 * <p>{@code @EnableAsync} 用于让同步任务在后台线程池执行，
 * 这样 HTTP 触发接口可以立刻返回一个 taskId，前端再轮询进度，
 * 不必阻塞几十秒。</p>
 */
@EnableAsync
@SpringBootApplication
public class MiniSyncServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(MiniSyncServerApplication.class, args);
    }
}
