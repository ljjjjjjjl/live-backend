package com.live.service;

/**
 * 点赞数变更的<b>异步投递出口</b>（MQ 预留接口）。
 *
 * <p><b>为什么要有这个接口？</b>
 * 视频点赞采用了「权威同步写 + 派生态异步刷回」的分层设计：
 * <ul>
 *   <li><b>权威数据</b>：{@code user_like} 表（"谁给谁点了赞"）—— <b>必须同步写、绝不能丢</b>，
 *       因为它是唯一的事实来源，丢了就再也算不回来；</li>
 *   <li><b>派生数据</b>：{@code video.like_count}（"有多少个赞"）—— 它是 user_like 的<b>聚合结果</b>，
 *       丢了大不了 {@code SELECT COUNT(*)} 重算一遍。</li>
 * </ul>
 * 既然派生数据可重算，就没必要让"点赞"这个高频动作去等数据库的 UPDATE ——
 * 于是把它扔给消息队列异步刷回，点赞接口只写权威表 + Redis 计数，响应飞快。
 *
 * <p><b>当前状态</b>：MQ 尚未接入，由 {@link NoopLikeCountPublisher} 占位（什么都不做）。
 * 等接入 RabbitMQ 时，只需新增一个实现类（如 {@code RabbitLikeCountPublisher}）并用
 * {@code @Primary} / {@code @ConditionalOnProperty} 切换即可，<b>调用方代码一行都不用改</b>。
 * 这就是"面向接口编程"的实际价值 —— 业务代码不依赖具体中间件。
 *
 * <p><b>兜底</b>：MQ 未接入期间，{@code video.like_count} 由定时对账任务
 * （{@code LikeCountReconcileTask}）从 user_like 重算，保证数据库最终正确。
 */
public interface LikeCountPublisher {

    /**
     * 投递一次点赞数增量。
     *
     * <p>注意语义：传的是 <b>delta（增量）</b>而不是"最新总数"。
     * 用增量才能让 MQ 消费者把多条消息<b>合并</b>成一次 UPDATE
     * （比如 1 秒内 100 个人点赞 → 一条 {@code SET like_count = like_count + 100}），
     * 这也是"写合并/削峰"的意义所在。
     *
     * @param videoId 视频 ID
     * @param delta   增量：+1 表示点赞，-1 表示取消点赞
     */
    void publishVideoLikeDelta(Long videoId, long delta);
}
