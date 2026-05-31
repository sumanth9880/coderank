package com.coderank.coderank.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "taskExecutor")
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(4);       // 4 concurrent executions at idle
        exec.setMaxPoolSize(8);        // up to 8 under load
        exec.setQueueCapacity(50);     // backlog before rejecting
        exec.setThreadNamePrefix("exec-");
        exec.initialize();
        return exec;
    }
}