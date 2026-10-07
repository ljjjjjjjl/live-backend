package com.live.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.live.common.BusinessException;
import com.live.common.Result;
import com.live.entity.User;
import com.live.mapper.UserMapper;
import com.live.util.JwtUtil;
import com.live.vo.LoginVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 用户业务逻辑
 */
@Service
public class UserService {

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private BCryptPasswordEncoder passwordEncoder;

    @Autowired
    private JwtUtil jwtUtil;

    /**
     * 注册（密码 BCrypt 加密存储）
     */
    public Result<Long> register(User user) {
        // ① 应用层校验：用户名是否重复
        Long cnt = userMapper.selectCount(
                new LambdaQueryWrapper<User>().eq(User::getUsername, user.getUsername()));
        if (cnt > 0) {
            throw new BusinessException("用户名已存在");
        }

        // ② 密码加密：明文 → BCrypt（带随机盐，每次结果不同），再入库
        //    ⚠️ 一定要"加密后才 insert"，否则数据库里存的就是明文了
        user.setPassword(passwordEncoder.encode(user.getPassword()));

        // ③ 插入（数据库唯一索引兜底，双保险）
        userMapper.insert(user);
        return Result.ok(user.getId());
    }

    /**
     * 登录（BCrypt 校验 + 签发 JWT）
     *
     * 为什么不能用 selectOne(username + password) 查库？
     * BCrypt 带随机盐：同一个明文密码每次加密结果都不同，数据库里存的是密文，
     * 拿明文去 WHERE password=？ 永远匹配不上。
     * 正确姿势：① 先按 username 查出用户 → ② 用 matches(明文, 库里的密文) 比对。
     */
    public Result<LoginVO> login(String username, String password) {
        // ① 只按用户名查，密码比对放在应用层做（因为密文不可直接等值查）
        User u = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, username));
        if (u == null) {
            throw new BusinessException("用户名或密码错误");
        }

        String stored = u.getPassword();
        boolean matched;
        if (stored != null && stored.startsWith("$2")) {
            // ② 正常路径：库里是 BCrypt 密文（$2a$/$2b$/$2y$ 开头）→ 用 matches 比对
            matched = passwordEncoder.matches(password, stored);
        } else {
            // ③ 兼容老数据：之前测试注册的用户，库里存的是明文（无 $2 前缀）
            //    → 直接明文比对；比对成功后顺手把明文升级成 BCrypt 密文（懒迁移）
            matched = stored != null && stored.equals(password);
            if (matched) {
                u.setPassword(passwordEncoder.encode(password));
                userMapper.updateById(u);
                u.setPassword(null); // 防止下面又把密文带出去
            }
        }

        if (!matched) {
            throw new BusinessException("用户名或密码错误");
        }

        // ④ BCrypt 校验通过 → 签发 JWT（把 userId/username 放进去，带过期时间）
        String token = jwtUtil.generateToken(u.getId(), u.getUsername());

        // ⑤ 组装登录返回体：token + 用户基本信息（不返密码）
        LoginVO vo = new LoginVO();
        vo.setToken(token);
        vo.setUserId(u.getId());
        vo.setUsername(u.getUsername());
        vo.setName(u.getName());
        return Result.ok(vo);
    }
}
