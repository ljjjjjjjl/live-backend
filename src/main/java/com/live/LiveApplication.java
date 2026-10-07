package com.live;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 直播系统启动类
 *
 * <p>{@code @EnableScheduling} 开启 Spring 的定时任务支持，让 {@code @Scheduled} 生效
 * （点赞数对账任务 LikeCountReconcileTask 依赖它）。
 * 不加这个注解的坑在于：{@code @Scheduled} 方法会<b>静默不执行</b> —— 不报错、不打日志，
 * 只是"任务好像没跑"，非常难排查。
 */
@SpringBootApplication
@EnableScheduling
@MapperScan("com.live.mapper") // 扫描 Mapper 接口，交给 MyBatis-Plus 管理
public class LiveApplication {

    public static void main(String[] args) {
        SpringApplication.run(LiveApplication.class, args);
    }
}
