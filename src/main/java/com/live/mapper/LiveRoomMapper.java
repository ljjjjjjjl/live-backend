package com.live.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.live.entity.LiveRoom;
import com.live.vo.LiveRoomCategoryPreference;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 直播间 Mapper（直播推荐专用 SQL）。
 * 和 VideoMapper 完全同构，唯一差异：所有推荐查询都加了 is_living=1（只推直播中的直播间）。
 */
public interface LiveRoomMapper extends BaseMapper<LiveRoom> {

    /**
     * 查询用户看过（观看记录）的直播间，按分区聚合，返回"用户常看的分区"。
     * 原理与视频推荐一样：把用户所有观看记录按 category 分组计数，count 越大多常看。
     */
    @Select("""
            SELECT lr.category AS category, COUNT(*) AS cnt
            FROM live_user_watch luw
            JOIN live_room lr ON luw.room_id = lr.id
            WHERE luw.user_id = #{userId}
            GROUP BY lr.category
            ORDER BY cnt DESC
            """)
    List<LiveRoomCategoryPreference> selectUserCategoryPreference(@Param("userId") Long userId);

    /**
     * 按分区查询**直播中**的直播间，按在线人数降序，且排除用户已看过的。
     */
    @Select("""
            <script>
            SELECT * FROM live_room
            WHERE is_living = 1 AND category = #{category}
            <if test="excludeRoomIds != null and excludeRoomIds.size() > 0">
                AND id NOT IN
                <foreach collection="excludeRoomIds" item="rid" open="(" separator="," close=")">
                    #{rid}
                </foreach>
            </if>
            ORDER BY online_count DESC
            LIMIT #{limit}
            </script>
            """)
    List<LiveRoom> selectByCategoryExcludeAndOrderByHot(@Param("category") String category,
                                                        @Param("excludeRoomIds") List<Long> excludeRoomIds,
                                                        @Param("limit") int limit);

    /**
     * 查询全站最热（在线人数最多）且**正在直播**的直播间（冷启动兜底）。
     * 只取 is_living=1，排除已看过的。
     */
    @Select("""
            <script>
            SELECT * FROM live_room
            WHERE is_living = 1
            <if test="excludeRoomIds != null and excludeRoomIds.size() > 0">
                AND id NOT IN
                <foreach collection="excludeRoomIds" item="rid" open="(" separator="," close=")">
                    #{rid}
                </foreach>
            </if>
            ORDER BY (online_count * 3 + like_count) DESC
            LIMIT #{limit}
            </script>
            """)
    List<LiveRoom> selectHotExclude(@Param("excludeRoomIds") List<Long> excludeRoomIds,
                                    @Param("limit") int limit);
}
