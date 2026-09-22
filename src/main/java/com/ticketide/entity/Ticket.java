package com.ticketide.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_ticket")
public class Ticket {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String title;

    private String description;

    private String priority;

    private String category;

    private String status;

    private Long customerId;

    private Long agentId;

    /**
     * 乐观锁版本号：MyBatis-Plus 的 OptimisticLockerInnerInterceptor 会自动将其加入更新条件
     * （WHERE id = ? AND version = ?）并在更新成功后 +1。
     * 作用是防止"丢失更新"——两个并发请求同时读到同一条数据时，后提交者会因版本号不匹配而更新失败，
     * 而不是静默覆盖前者的修改。它是分布式锁之外的第二道并发防线。
     */
    @Version
    private Integer version;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableField(exist = false)
    private String customerName;

    @TableField(exist = false)
    private String agentName;
}
