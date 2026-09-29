package ru.lct.heatnetwork;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.annotation.Value;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.core.task.TaskExecutor;

@SpringBootApplication
@EnableScheduling
public class HeatNetworkRoutingApplication {
    public static void main(String[] args) {
        SpringApplication.run(HeatNetworkRoutingApplication.class, args);
    }

    @Bean(name = "calculationTaskExecutor")
    public TaskExecutor calculationTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("heat-route-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    @Bean(name = "routeSearchExecutor", destroyMethod = "shutdown")
    public ExecutorService routeSearchExecutor(
            @Value("${app.calculation.route-search-parallelism:4}") int parallelism) {
        if (parallelism < 1 || parallelism > 8) {
            throw new IllegalArgumentException("Route search parallelism must be between 1 and 8");
        }
        AtomicInteger sequence = new AtomicInteger();
        ThreadFactory threadFactory = task -> new Thread(task, "route-search-" + sequence.incrementAndGet());
        return new ThreadPoolExecutor(parallelism, parallelism, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(parallelism * 2), threadFactory, new ThreadPoolExecutor.AbortPolicy());
    }
}
