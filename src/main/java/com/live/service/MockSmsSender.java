package com.live.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

/**
 * 短信发送的 Mock 实现：**不真调第三方**，只把验证码打到日志，直接返回成功。
 *
 * <p>用途：开发/演示阶段跑通"发验证码 → 存 Redis → 校验 → 登录"整条链路，无需短信服务商。
 * 接入真实短信（阿里云/腾讯云）时，新增 {@code AliyunSmsSender implements SmsSender}
 * 并去掉本类的 {@code @Primary} 即可，业务代码不受影响。
 */
@Slf4j
@Primary                    // 有多个 SmsSender 实现时优先用这个（Mock）
@Service
public class MockSmsSender implements SmsSender {

    @Override
    public boolean send(String phone, String code) {
        // ⚠️ 仅用于开发：真实项目绝不会把验证码打进日志
        log.info("【Mock 短信】给手机号 {} 发送验证码：{}（未真实发送，模拟成功）", phone, code);
        return true;
    }
}
