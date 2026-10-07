package com.live.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.live.common.ValidationGroups;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户实体，对应 user 表
 *
 * <p>参数校验（配合 @Valid / @Validated）：
 * <ul>
 *   <li>username/password：默认组（Default），登录+注册都校验——非空 + 长度。</li>
 *   <li>name：@{@link ValidationGroups.Create} 组，只在校验时带上该组才校验（注册必填），登录不校验。</li>
 *   <li>phone：可空，非空时才校验手机号格式。</li>
 * </ul>
 */
@Data
@TableName("user") // 指定表名
public class User {

    @TableId(type = IdType.AUTO) // 主键自增
    private Long id;

    @NotBlank(message = "用户名不能为空")
    @Size(max = 50, message = "用户名长度不能超过50")
    private String username;   // 登录用户名（唯一）

    @NotBlank(message = "密码不能为空")
    @Size(max = 100, message = "密码长度不能超过100")
    private String password;   // 密码（BCrypt 加密后的密文，注册时用 BCryptPasswordEncoder.encode 生成）

    @NotBlank(message = "昵称不能为空", groups = ValidationGroups.Create.class)
    @Size(max = 50, message = "昵称长度不能超过50")
    private String name;       // 昵称/显示名

    private String avatar;     // 头像地址

    /** 手机号：可空；非空时才校验（11 位，1 开头） */
    @Pattern(regexp = "^$|^1\\d{10}$", message = "手机号格式错误")
    private String phone;      // 手机号

    private LocalDateTime createTime; // 创建时间
}
