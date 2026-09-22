package com.ticketide.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步任务配置：附件上传等异步处理使用独立线程池
 */
@Configuration
@EnableAsync
@Slf4j
public class AsyncConfig {

    /**
     * 附件处理专用线程池：
     * - corePoolSize=2：常驻线程
     * - maxPoolSize=8：高峰扩展
     * - queueCapacity=64：等待队列（超出走拒绝策略）
     * - CallerRunsPolicy：队列满时由调用线程执行（避免任务丢失）
     */
    @Bean("attachmentExecutor")
    public Executor attachmentExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(64);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("attachment-async-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        log.info("异步线程池 attachmentExecutor 初始化完成");
        return executor;
    }

    /**
     * 操作日志专用线程池（@LogRecord 切面异步写日志使用）：
     * 与附件线程池隔离，避免附件大文件处理把日志线程占满，影响工单时间线的完整性
     */
    @Bean("logExecutor")
    public Executor logExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(256);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("op-log-async-");
        // 队列满时由调用线程兜底执行，保证日志不丢
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        log.info("异步线程池 logExecutor 初始化完成");
        return executor;
    }
}
