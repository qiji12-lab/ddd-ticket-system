package com.ticketide.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ticketide.entity.Comment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface CommentMapper extends BaseMapper<Comment> {

    @Select("SELECT * FROM t_ticket_comment WHERE ticket_id = #{ticketId} ORDER BY create_time ASC")
    List<Comment> selectByTicketId(@Param("ticketId") Long ticketId);
}
