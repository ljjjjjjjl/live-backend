package com.live.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 配置：注册 JWT 拦截器 + 配置白名单。
 *
 * 哪些接口要登录（被拦截）：
 *   /api/live/**、/api/danmaku/** 需要身份校验。
 *   /api/video/** 需要身份校验：点赞必须知道"是谁点的"，userId 从 Token 取，
 *     绝不接受前端传参 —— 否则任何人都能伪造"别人给我的视频点赞"。
 *   /api/auth/phone/**（绑定手机号）需要登录；/api/auth/sms/**（发码、手机号登录）公开。
 *   /api/sensitive/**（敏感词管理/调试）、/api/count/**（早期计数演示）也需要登录 ——
 *     这两个接口原来不在拦截范围内，任何人都能调，其中 /reload 会触发全表扫描，是个 DoS 入口。
 *   注意：GET /api/live/rooms 直播间列表对游客放行（见 JwtInterceptor 里的方法级判断），
 *   但 POST /api/live/rooms（创建）、/start、/stop 仍需登录。
 *
 * 白名单（路径级放行，任何方法都不拦）：
 *   /api/user/login     登录
 *   /api/user/register  注册
 *   /api/auth/sms/send  发送短信验证码（手机号登录用）
 *   /api/auth/sms/login 手机号+验证码登录
 *   /api/recommend/**   推荐（看直播/视频不需要登录）
 *   /ws/**              弹幕 WebSocket（它走握手拦截器，不走这个 HTTP 拦截器）
 *
 * 游客可读的"只读展示类"接口（GET 放行，写在 JwtInterceptor#isGuestReadable 里）：
 *   GET /api/live/rooms、GET /api/live/recommend、GET /api/live/{id}/online、
 *   GET /api/live/{id}/gift/rank、GET /api/danmaku
 *   —— 这些是"看"，不需要身份；而创建/开播/送礼/进房/发弹幕是"写"，仍然必须登录。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Autowired
    private JwtInterceptor jwtInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(jwtInterceptor)
                .addPathPatterns(
                        "/api/live/**",
                        "/api/danmaku/**",
                        // 视频点赞：需要登录态才能拿到 userId
                        "/api/video/**",
                        // 绑定手机号需要登录（取 userId）；/api/auth/sms/** 是公开的，不在此列
                        "/api/auth/phone/**",
                        // 管理/调试类接口：原来完全裸奔（任何人都能 POST /api/sensitive/reload
                        // 触发全表扫描 + 重建 Trie，等于一个免费的 DoS 入口；
                        // /api/count/** 更是"谁都能给任意目标刷计数"）。至少要求登录态。
                        "/api/sensitive/**",
                        "/api/count/**")
                .excludePathPatterns(
                        "/api/user/login",
                        "/api/user/register",
                        "/api/recommend/**",
                        "/ws/**",
                        // 静态资源（登录测试页）放行：这些本就不在 addPathPatterns 里，
                        // 这里显式排除，避免以后加拦截路径时误伤前端页面。
                        "/",
                        "/index.html",
                        "/login.html",
                        "/home.html",
                        "/live.html",
                        "/room.html",
                        "/tools.html",
                        "/like.html",
                        "/favicon.ico",
                        "/css/**",
                        "/js/**",
                        "/static/**");
    }
}
