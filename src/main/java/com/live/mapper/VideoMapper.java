package com.live.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.live.entity.Video;
import com.live.vo.CategoryPreference;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 推荐系统视频 Mapper。
 *
 * 继承了 MyBatis-Plus 的 BaseMapper，自动有普通 CRUD（insert/selectById/selectList...）。
 * 这里自定义了几个"推荐专用的 SQL"，用 @Select 注解直接写在方法上，不用写 XML，清晰好懂。
 */
public interface VideoMapper extends BaseMapper<Video> {

    /**
     * 查询用户看过（点赞过）的视频，按分类聚合，返回"用户最偏爱的分类"及其权重。
     *
     * 原理（这就是简化版协同过滤的思路）：
     *   用户点过赞的视频都有 category，
     *   我们把该用户所有点赞视频按 category 分组计数，count 越大说明越爱看这个分类。
     *   结果按 count 降序，取前几个分类，就是用户的"兴趣画像"。
     *
     * SQL 说明：
     *   JOIN user_like：把"用户点赞记录"和"视频"关联起来
     *   WHERE ul.user_id=#{userId}：只看该用户的点赞
     *   GROUP BY v.category：按分类分组
     *   ORDER BY cnt DESC：按被点赞次数降序
     *
     * 注意：UserLikeMapper 不需要额外写成 XML，这条 JOIN 涉及两张表，放在这里最合适。
     */
    @Select("""
            SELECT v.category AS category, COUNT(*) AS cnt
            FROM user_like ul
            JOIN video v ON ul.video_id = v.id
            WHERE ul.user_id = #{userId}
            GROUP BY v.category
            ORDER BY cnt DESC
            """)
    List<CategoryPreference> selectUserCategoryPreference(@Param("userId") Long userId);

    /**
     * 按分类查询视频，按热度分降序，且排除用户已看过的视频。
     *
     * 用途：拿到用户偏好分类后，推荐该分类里"最热门、且用户还没看过的"视频。
     *
     * NOT IN 的作用：把用户已经点过赞的 video_id 排除掉，避免推荐重复的"看过的内容"，
     * 这一步保证"推荐的列表是新的"，是推荐的基本要求。
     */
    @Select("""
            <script>
            SELECT * FROM video
            WHERE category = #{category}
            <if test="excludeVideoIds != null and excludeVideoIds.size() > 0">
                AND id NOT IN
                <foreach collection="excludeVideoIds" item="vid" open="(" separator="," close=")">
                    #{vid}
                </foreach>
            </if>
            ORDER BY play_count DESC
            LIMIT #{limit}
            </script>
            """)
    List<Video> selectByCategoryExcludeAndOrderByHot(@Param("category") String category,
                                                     @Param("excludeVideoIds") List<Long> excludeVideoIds,
                                                     @Param("limit") int limit);

    /**
     * 查询整库最热的视频（冷启动兜底：不知道用户喜欢什么时先推荐全站热门）。
     * 排除已看过的，按热度综合分降序。
     */
    @Select("""
            <script>
            SELECT * FROM video
            <where>
                <if test="excludeVideoIds != null and excludeVideoIds.size() > 0">
                    id NOT IN
                    <foreach collection="excludeVideoIds" item="vid" open="(" separator="," close=")">
                        #{vid}
                    </foreach>
                </if>
            </where>
            ORDER BY (play_count + like_count*5 + favorite_count*10 + comment_count*8 + forward_count*6) DESC
            LIMIT #{limit}
            </script>
            """)
    List<Video> selectHotExclude(@Param("excludeVideoIds") List<Long> excludeVideoIds,
                                 @Param("limit") int limit);

    /**
     * 【对账】把 video.like_count 按权威表 user_like 重新算一遍，只更新"对不上"的行。
     *
     * <p>为什么需要它？
     * 点赞走的是"权威同步写 user_like + 派生态 video.like_count 异步刷回"。
     * 在 MQ 接入之前（{@code NoopLikeCountPublisher} 什么都不做），
     * 如果没有这个任务，video.like_count 就会<b>永远停在旧值</b>，
     * 导致热度排序（{@code selectHotExclude} 里用了 like_count）和展示数字都是错的。
     *
     * <p>SQL 要点：
     * <ul>
     *   <li>{@code LEFT JOIN} 派生表：把 user_like 按 video_id 分组统计，一次扫描拿到所有计数，
     *       比在 SET 里对每行写一个相关子查询（每行查一次）快得多；</li>
     *   <li>{@code LEFT JOIN} 而不是 INNER JOIN：没有任何点赞的视频也要被刷成 0
     *       （比如用户把赞取消了，计数必须回落）；</li>
     *   <li>{@code IFNULL(t.c, 0)}：没有点赞记录时 LEFT JOIN 出来是 NULL，要当 0 处理；</li>
     *   <li>{@code WHERE v.like_count <> IFNULL(t.c, 0)}：<b>只更新有差异的行</b>。
     *       对账任务是高频定时跑的，如果每次都全表 UPDATE，会白白产生大量无意义的 binlog 和行锁，
     *       把从库和磁盘拖垮。加上这个条件后，稳态下影响行数 = 0，几乎零开销。</li>
     * </ul>
     *
     * @return 被修正的行数（0 表示数据本来就是一致的）
     */
    @Update("""
            UPDATE video v
            LEFT JOIN (
                SELECT video_id, COUNT(*) AS c
                FROM user_like
                GROUP BY video_id
            ) t ON t.video_id = v.id
            SET v.like_count = IFNULL(t.c, 0)
            WHERE v.like_count <> IFNULL(t.c, 0)
            """)
    int reconcileLikeCount();
}
