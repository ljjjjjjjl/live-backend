package com.live.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.live.common.BusinessException;
import com.live.common.Result;
import com.live.common.UserStatus;
import com.live.entity.User;
import com.live.mapper.UserMapper;
import com.live.util.JwtUtil;
import com.live.vo.LoginRequest;
import com.live.vo.LoginVO;
import com.live.vo.RegisterRequest;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 用户业务逻辑（注册 / 账号密码登录）。
 *
 * <h3>安全修复要点（本轮）</h3>
 * <ol>
 *   <li><b>不再用实体接参</b>：入参改用 {@link RegisterRequest} / {@link LoginRequest}，
 *       客户端无法再通过 JSON 注入 {@code id / phone / avatar / createTime}（以及未来的
 *       {@code role / status / balance}）。</li>
 *   <li><b>服务端强制赋权</b>：注册时 {@code role/status} 一律由服务端写死为
 *       普通用户 + {@link UserStatus#NORMAL}，手机号也不允许在注册时写
 *       （必须走带短信验证码的绑定接口）。</li>
 *   <li><b>并发唯一键冲突</b>：先查再插只是"友好提示"，真正的防线是数据库唯一索引；
 *       并发下插库抛 {@link DataIntegrityViolationException} 时**翻译成业务异常**，
 *       而不是让它冒泡成 500 / "系统繁忙"。</li>
 *   <li><b>登录检查账号状态</b>：密码校验通过后还要判 {@link UserStatus#canLogin(String)}，
 *       封禁/注销/停用/锁定账号一律不签发 token。</li>
 * </ol>
 */
@Slf4j
@Service
public class UserService {

    /** 审计日志通道：独立 logger 名，便于单独落到审计文件，不与业务日志混在一起 */
    private static final Logger AUDIT = LoggerFactory.getLogger("com.live.audit");

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private BCryptPasswordEncoder passwordEncoder;

    @Autowired
    private JwtUtil jwtUtil;

    /**
     * 注册（密码 BCrypt 加密存储）。
     *
     * <p>只允许创建**普通用户**：状态/角色等敏感字段由服务端赋值，入参 DTO 里也不存在这些字段。
     */
    public Result<Long> register(RegisterRequest req) {
        String username = req.getUsername().trim();

        // ① 应用层预检：用户名是否重复（只为给出友好提示，不是并发防线）
        Long cnt = userMapper.selectCount(
                new LambdaQueryWrapper<User>().eq(User::getUsername, username));
        if (cnt > 0) {
            throw new BusinessException("用户名已存在");
        }

        // ② 组装实体：只取 DTO 允许的字段，其余全部服务端赋值
        User user = new User();
        user.setUsername(username);
        // ⚠️ 一定要"加密后才 insert"，否则数据库里存的就是明文了
        user.setPassword(passwordEncoder.encode(req.getPassword()));
        user.setName(req.getName().trim());
        user.setAvatar(req.getAvatar());
        user.setPhone(null);                      // 手机号必须走验证码绑定，注册接口不接受
        user.setStatus(UserStatus.NORMAL);        // 强制普通状态（防客户端提权）
        user.setCreateTime(LocalDateTime.now());

        // ③ 插入：真正的并发防线是唯一索引 uk_username。
        //    并发下"先查后插"两边都查不到，必然有一边在这里抛唯一键冲突 —— 翻译成业务异常，
        //    避免变成 500/系统繁忙（旧实现没有这层捕获）。
        try {
            userMapper.insert(user);
        } catch (DataIntegrityViolationException e) {
            log.warn("注册命唯一约束冲突 username={}", username);
            throw new BusinessException("用户名已存在");
        }
        return Result.ok(user.getId());
    }

    /**
     * 取用户的<b>展示昵称</b>（优先 name，其次 username，查不到返回"游客"）。
     *
     * <p>为什么需要这个入口：弹幕的 {@code nickname} 原来是**直接取前端消息体里的值**，
     * 于是任何人发弹幕都能显示成"主播""管理员"或别人的昵称 —— 身份（userId）虽然是真的，
     * 但展示名是假的，而且这个假名字会<b>落库并永久留在历史弹幕里</b>。
     * 服务端能查出来的东西，就不该由客户端决定。
     *
     * <p>代价：多一次主键查询。WebSocket 侧只在<b>握手时查一次</b>放进 session，
     * 不是每条消息都查；REST 侧则是一次请求一次（可接受）。
     */
    public String displayName(Long userId) {
        if (userId == null) {
            return "游客";
        }
        User u = userMapper.selectById(userId);
        if (u == null) {
            return "游客";
        }
        String name = u.getName();
        if (name != null && !name.trim().isEmpty()) {
            return name.trim();
        }
        String username = u.getUsername();
        return (username == null || username.trim().isEmpty()) ? "游客" : username.trim();
    }

    /**
     * 登录（BCrypt 校验 + 状态校验 + 签发 JWT）
     *
     * 为什么不能用 selectOne(username + password) 查库？
     * BCrypt 带随机盐：同一个明文密码每次加密结果都不同，数据库里存的是密文，
     * 拿明文去 WHERE password=？ 永远匹配不上。
     * 正确姿势：① 先按 username 查出用户 → ② 用 matches(明文, 库里的密文) 比对。
     */
    public Result<LoginVO> login(LoginRequest req) {
        return login(req.getUsername(), req.getPassword());
    }

    /** 登录实现（保留 String 重载，便于内部复用与单测） */
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

        // ④ ⭐ 状态校验：必须放在"密码校验通过之后"——
        //    否则未认证的调用方可以拿"账号被封禁"这个差异去探测账号是否存在/被处理。
        //    提示语统一，不告诉调用方具体是 BANNED 还是 DELETED（避免泄露风控信息）。
        if (!UserStatus.canLogin(u.getStatus())) {
            AUDIT.warn("event=LOGIN_REJECTED userId={} username={} reason=STATUS_{}",
                    u.getId(), u.getUsername(), u.getStatus());
            throw new BusinessException("账号当前不可用，请联系管理员");
        }

        // ⑤ BCrypt 校验通过 → 签发 JWT（把 userId/username 放进去，带过期时间）
        String token = jwtUtil.generateToken(u.getId(), u.getUsername());

        // ⑥ 组装登录返回体：token + 用户基本信息（不返密码）
        LoginVO vo = new LoginVO();
        vo.setToken(token);
        vo.setUserId(u.getId());
        vo.setUsername(u.getUsername());
        vo.setName(u.getName());
        return Result.ok(vo);
    }
}
