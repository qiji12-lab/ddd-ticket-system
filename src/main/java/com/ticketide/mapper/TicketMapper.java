package com.ticketide.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ticketide.entity.Ticket;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface TicketMapper extends BaseMapper<Ticket> {

    /**
     * 抢单专用条件更新（防超卖的最后一道防线）。
     * <p>
     * 把"校验状态"与"更新状态"合并成一条 SQL，由数据库的行锁保证原子性：
     * 只要工单不满足 status = PENDING 或已被分配或版本号已变，影响行数即为 0。
     * 相比"先 select 判断再 update"的写法，可杜绝"锁失效/事务未提交窗口"导致的两人抢到同一单。
     *
     * @return 影响行数，0 表示抢单失败（已被他人抢走）
     */
    @Update("UPDATE t_ticket SET status = #{targetStatus}, agent_id = #{agentId}, " +
            "version = version + 1, update_time = NOW() " +
            "WHERE id = #{id} AND status = #{expectStatus} AND agent_id IS NULL AND version = #{version}")
    int grabIfPending(@Param("id") Long id,
                      @Param("agentId") Long agentId,
                      @Param("expectStatus") String expectStatus,
                      @Param("targetStatus") String targetStatus,
                      @Param("version") Integer version);

    @Select("SELECT * FROM t_ticket WHERE status = #{status} ORDER BY priority DESC, create_time ASC")
    IPage<Ticket> selectPendingTickets(Page<Ticket> page, @Param("status") String status);

    @Select("SELECT * FROM t_ticket WHERE agent_id = #{agentId} ORDER BY update_time DESC")
    IPage<Ticket> selectByAgentId(Page<Ticket> page, @Param("agentId") Long agentId);

    @Select("SELECT * FROM t_ticket WHERE customer_id = #{customerId} ORDER BY update_time DESC")
    IPage<Ticket> selectByCustomerId(Page<Ticket> page, @Param("customerId") Long customerId);

    /**
     * 待接单超时工单（按创建时间判断：创建后一直没人接单）
     */
    @Select("SELECT * FROM t_ticket WHERE status = #{status} AND create_time <= #{deadline} ORDER BY create_time ASC LIMIT #{limit}")
    List<Ticket> selectPendingTimeoutTickets(@Param("status") String status,
                                             @Param("deadline") LocalDateTime deadline,
                                             @Param("limit") int limit);

    /**
     * 待确认超时工单ID（按更新时间判断：进入"待确认"后一直没人确认）
     */
    @Select("SELECT id FROM t_ticket WHERE status = #{status} AND update_time <= #{deadline} ORDER BY update_time ASC LIMIT #{limit}")
    List<Long> selectPendingConfirmTimeoutIds(@Param("status") String status,
                                              @Param("deadline") LocalDateTime deadline,
                                              @Param("limit") int limit);
}
