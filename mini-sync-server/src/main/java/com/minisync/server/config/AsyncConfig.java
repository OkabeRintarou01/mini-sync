package com.minisync.server.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 同步任务线程池配置。
 *
 * <p>同步是 IO 密集型（等数据库返回），不是 CPU 密集型，
 * 所以线程数不需要等于核数，重点是<b>限制并发上限</b>：
 * 同时跑太多同步会把源库/目标库压垮。</p>
 */
@Configuration
public class AsyncConfig {

    @Bean("syncExecutor")
    public ThreadPoolTaskExecutor syncExecutor() {
        ThreadPoolTaskExecutor e = new ThreadPoolTaskExecutor();
        // 核心 2 个：允许两个任务并行跑
        e.setCorePoolSize(2);
        // 最多 4 个：再多就排队，避免把数据库压垮
        e.setMaxPoolSize(4);
        e.setQueueCapacity(50);
        e.setThreadNamePrefix("sync-");
        // 队列满了由调用线程执行：宁可让触发方等一下，也不丢任务
        e.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        e.setWaitForTasksToCompleteOnShutdown(true);
        e.setAwaitTerminationSeconds(30);
        e.initialize();
        return e;
    }
}
