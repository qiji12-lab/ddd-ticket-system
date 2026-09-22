package com.ticketide.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 工单操作日志注解（AOP 记录，业务代码零侵入）。
 * <p>
 * 使用方式：标注在 Service 方法上，方法成功返回后由 {@code LogRecordAspect} 自动写入 t_ticket_log。
 * 业务方法内部不再出现任何"记日志"的代码，日志逻辑与业务逻辑彻底解耦。
 * <p>
 * 注意：只有方法正常返回才会记日志；抛异常时（业务回滚）不记录，避免留下脏日志。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface LogRecord {

    /**
     * 动作名，取值约定：CREATE/ASSIGN/TAKE/START_PROCESS/RESOLVE/CLOSE/REOPEN/CANCEL/TRANSFER/ESCALATE/UPDATE
     */
    String action();

    /**
     * 备注内容，支持 SpEL：
     * 可引用方法参数名（#id、#operatorId、#request）、返回值 #result，
     * 也可调用容器中的 Bean，例如 {@code @logRemarkHelper.transfer(#request)}。
     * 留空表示不写备注。
     */
    String remark() default "";

    /**
     * 取工单ID 的 SpEL 表达式；新建工单场景可用 {@code #result.id}（返回值中的ID）
     */
    String ticketId() default "#id";

    /**
     * 取操作人ID 的 SpEL 表达式；表达式取不到时自动回退为"当前登录用户"
     */
    String operatorId() default "#operatorId";

    /**
     * 是否为状态流转操作：
     * true —— 记录"旧状态 -> 新状态"（如抢单、标记解决）；
     * false —— 属性变更类操作（转交、升级、修改工单），不改变状态，日志中前后状态保持一致，
     * 避免时间线里出现"处理中 -> 处理中"这种噪音
     */
    boolean statusChange() default true;
}
