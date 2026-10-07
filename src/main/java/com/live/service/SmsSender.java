package com.live.service;

/**
 * 短信发送抽象（开闭原则的关键一环）。
 *
 * <p>业务层只依赖这个接口，不关心具体是哪家短信服务商。目前先用
 * {@link MockSmsSender} 模拟发送成功；将来接阿里云/腾讯云短信，**只需新增一个实现类**
 * （如 {@code AliyunSmsSender}），调用方代码一行都不用改——这就是"对扩展开放、对修改关闭"。
 */
public interface SmsSender {

    /**
     * 发送短信验证码。
     *
     * @param phone 接收手机号
     * @param code  验证码
     * @return 是否发送成功
     */
    boolean send(String phone, String code);
}
