package com.live.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 配置。
 *
 * 这里只做一件事：注册**分页插件**。
 * MyBatis-Plus 的 selectPage 必须配合分页插件才会真正执行物理分页（LIMIT），
 * 否则默认只查全表。这也是为什么之前的分页要单独配置。
 *
 * 面试点：MyBatis-Plus 分页不是自带的，需要 PaginationInnerInterceptor，
 * 它会根据数据库类型生成对应的分页 SQL（MySQL 是 LIMIT）。
 */
@Configuration
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        // 指定数据库类型为 MySQL，分页插件才能生成正确的 LIMIT 语法
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }
}
