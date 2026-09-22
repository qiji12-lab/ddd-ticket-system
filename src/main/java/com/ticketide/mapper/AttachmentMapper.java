package com.ticketide.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ticketide.entity.Attachment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface AttachmentMapper extends BaseMapper<Attachment> {

    @Select("SELECT * FROM t_ticket_attachment WHERE ticket_id = #{ticketId} ORDER BY create_time ASC")
    List<Attachment> selectByTicketId(@Param("ticketId") Long ticketId);
}
