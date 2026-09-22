package com.ticketide.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ticketide.entity.OperatorLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface OperatorLogMapper extends BaseMapper<OperatorLog> {

    @Select("SELECT * FROM t_ticket_log WHERE ticket_id = #{ticketId} ORDER BY create_time DESC")
    IPage<OperatorLog> selectByTicketId(Page<OperatorLog> page, @Param("ticketId") Long ticketId);

    @Select("SELECT * FROM t_ticket_log WHERE operator_id = #{operatorId} ORDER BY create_time DESC")
    IPage<OperatorLog> selectByOperatorId(Page<OperatorLog> page, @Param("operatorId") Long operatorId);
}
