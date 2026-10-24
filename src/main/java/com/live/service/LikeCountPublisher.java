package com.live.service;

/**
 * 点赞数变更的<b>投递出口</b>：把「这次点赞/取消」交给下游去落到派生计数上。
 *
 * <p><b>为什么要有这个接口？</b>
 * 视频点赞采用「权威同步写 + 派生态异步刷回」的分层设计：
 * <ul>
 *   <li><b>权威数据</b>：{@code user_like} 表（"谁给谁点了赞"）—— <b>必须同步写、绝不能丢</b>，
 *       因为它是唯一的事实来源，丢了就再也算不回来；</li>
 *   <li><b>派生数据</b>：{@code video.like_count} / Redis 计数（"有多少个赞"）——
 *       它是 user_like 的<b>聚合结果</b>，丢了大不了 {@code SELECT COUNT(*)} 重算一遍。</li>
 * </ul>
 * 既然派生数据可重算，就没必要让"点赞"这个高频动作在原地把它算完 ——
 * 于是把这件事抽成一个出口，具体是"当场算"还是"扔给 MQ 异步算"由实现决定。
 *
 * <h2>两个实现（用配置开关切换，调用方代码一行都不用改）</h2>
 * <table border="1">
 *   <tr><th>配置</th><th>实现</th><th>行为</th></tr>
 *   <tr><td>{@code app.mq.enabled=false}（默认）</td><td>{@link SyncLikeCountPublisher}</td>
 *       <td>请求线程内直接 {@code applyDelta} + 标脏，接口返回的计数立即正确</td></tr>
 *   <tr><td>{@code app.mq.enabled=true}</td><td>{@link RocketLikeCountPublisher}</td>
 *       <td>发一条<b>顺序消息</b>（按 videoId 分区），由 {@code VideoLikeCountConsumer}
 *           异步 {@code applyDelta} + 标脏；接口返回值可能滞后几毫秒（最终一致）</td></tr>
 * </table>
 * 这就是"面向接口编程"的实际价值 —— 业务代码不依赖具体中间件。
 *
 * <h2>为什么"算计数"和"标脏"都要放进实现里</h2>
 * <p>改造前这两件事是散在 {@code VideoLikeService} 里的三行代码（applyDelta / publish / markDirty），
 * 任何一行被漏掉都会让计数或对账<b>悄悄失效</b>且很难发现。
 * 现在把「算 + 标脏」打包成一个不可分割的动作交给实现，调用点只剩一句
 * {@code likeCountPublisher.publishVideoLikeDelta(...)}，从结构上杜绝了漏写。
 *
 * <h2>兜底</h2>
 * <p>不管走哪条路，{@code video.like_count} 最终都由定时对账任务
 * （{@code LikeCountReconcileTask}：脏集合 30 秒一轮 + 每天 3 点全量）从 user_like 重算校正。
 * 所以"消息丢了"最坏结果是延迟几分钟才正确，而不是永久错。
 */
public interface LikeCountPublisher {

    /**
     * 投递一次点赞数增量。
     *
     * <p>注意语义：传的是 <b>delta（增量）</b>而不是"最新总数"。
     * 用增量才能让下游把多条消息<b>合并</b>成一次写（比如 1 秒内 100 个人点赞 →
     * 一条 {@code INCRBY key 100}），这也是"写合并/削峰"的意义所在；
     * 而绝对值一旦乱序或重复，就会用旧值覆盖新值，把计数写错。
     *
     * @param videoId 视频 ID
     * @param delta   增量：+1 表示点赞，-1 表示取消点赞
     */
    void publishVideoLikeDelta(Long videoId, long delta);
}
