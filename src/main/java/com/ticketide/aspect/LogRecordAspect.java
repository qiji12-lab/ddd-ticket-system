package com.ticketide.aspect;

import com.ticketide.annotation.LogRecord;
import com.ticketide.entity.Ticket;
import com.ticketide.mapper.TicketMapper;
import com.ticketide.service.OperatorLogService;
import com.ticketide.util.CurrentUserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.context.expression.BeanFactoryResolver;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.Ordered;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.annotation.Order;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * 工单操作日志切面：拦截 {@link LogRecord} 注解方法，自动记录"谁、何时、把工单从什么状态改成了什么状态、备注"。
 * <p>
 * 关键设计：
 * 1) 日志内容（操作人、时间、旧状态、新状态、备注）全部由切面推导，业务代码零侵入；
 * 2) 旧状态在方法执行前查库快照，新状态在方法成功返回后查库，因此不需要业务方法显式告知；
 * 3) 日志写入是<b>异步</b>的（OperatorLogService#saveLogAsync + 独立线程池），且注册在事务 afterCommit 之后，
 * 既不阻塞主流程，也避免"事务还没提交就写日志"导致的外键/脏数据问题；
 * 4) 方法抛异常时不记日志（业务已回滚，日志应保持干净）；
 * 5) 参数字段名通过 ParameterNameDiscoverer 获取，备注支持 SpEL（可调用容器内 Bean）；
 * 6) 通过 @Order(HIGHEST_PRECEDENCE) 让切面处于调用链最外层，即"事务提交之后"才读新状态、写日志，
 * 避免读到未提交数据。
 */
@Aspect
@Component
@RequiredArgsConstructor
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
public class LogRecordAspect {

    private final TicketMapper ticketMapper;
    private final OperatorLogService operatorLogService;
    private final CurrentUserContext currentUserContext;
    private final BeanFactory beanFactory;

    private final ExpressionParser expressionParser = new SpelExpressionParser();
    private final ParameterNameDiscoverer parameterNameDiscoverer = new DefaultParameterNameDiscoverer();

    /**
     * 注意：这里用 {@code @annotation(注解全类名)} 而不是把注解绑定成方法参数，
     * 因为 Spring AOP 在"接口 + CGLIB 代理"的 mix 场景下，绑定参数可能拿不到
     * JoinPointMatch（IllegalStateException: JoinPointMatch was NOT bound in invocation），
     * 手动从方法签名上取注解更稳。
     */
    @Around("@annotation(com.ticketide.annotation.LogRecord)")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        LogRecord logRecord = resolveLogRecord(joinPoint);
        if (logRecord == null) {
            return joinPoint.proceed();
        }

        Map<String, Object> variables = buildVariables(joinPoint);

        Long ticketIdBefore = evalLong(logRecord.ticketId(), variables);
        Long operatorId = evalLong(logRecord.operatorId(), variables);
        // 旧状态：方法执行前快照（一次轻量主键查询，换取业务代码零侵入）
        String beforeStatus = statusOf(ticketIdBefore);

        // 先执行目标方法：只有业务成功（未抛异常）才会走到下面的记日志逻辑
        Object result = joinPoint.proceed();

        variables.put("result", result);
        Long ticketId = evalLong(logRecord.ticketId(), variables);
        String afterStatus = statusOf(ticketId);

        if (!logRecord.statusChange()) {
            // 属性变更类操作（转交/升级/修改）不改变状态，前后状态保持一致
            afterStatus = beforeStatus != null ? beforeStatus : afterStatus;
            beforeStatus = afterStatus;
        }

        if (operatorId == null) {
            // 方法签名里没有操作人参数时，回退为当前登录用户（从请求头 JWT 解析）
            operatorId = currentUserContext.getCurrentUserId();
        }
        String remark = evalString(logRecord.remark(), variables);

        saveLogAfterCommit(ticketId, operatorId, logRecord.action(), beforeStatus, afterStatus, remark);
        return result;
    }

    /**
     * 从目标方法上解析 @LogRecord（兼容接口方法未标注、仅实现类标注的情况）
     */
    private LogRecord resolveLogRecord(ProceedingJoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        LogRecord logRecord = AnnotationUtils.findAnnotation(method, LogRecord.class);
        if (logRecord == null && joinPoint.getTarget() != null) {
            Method targetMethod = AopUtils.getMostSpecificMethod(method, joinPoint.getTarget().getClass());
            logRecord = AnnotationUtils.findAnnotation(targetMethod, LogRecord.class);
        }
        return logRecord;
    }

    /**
     * 把方法参数按名字放进 SpEL 上下文，这样注解里可以直接写 #id、#operatorId、#request
     */
    private Map<String, Object> buildVariables(ProceedingJoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        String[] parameterNames = parameterNameDiscoverer.getParameterNames(signature.getMethod());
        Object[] args = joinPoint.getArgs();

        Map<String, Object> variables = new HashMap<>();
        if (parameterNames != null) {
            for (int i = 0; i < parameterNames.length && i < args.length; i++) {
                variables.put(parameterNames[i], args[i]);
            }
        }
        return variables;
    }

    private StandardEvaluationContext buildContext(Map<String, Object> variables) {
        StandardEvaluationContext context = new StandardEvaluationContext();
        context.setVariables(variables);
        // 支持在注解里以 @beanName.method(...) 的形式调用容器中的 Bean（如 @logRemarkHelper.transfer(#request)）
        context.setBeanResolver(new BeanFactoryResolver(beanFactory));
        return context;
    }

    private Long evalLong(String expression, Map<String, Object> variables) {
        Object value = eval(expression, variables);
        if (value instanceof Number number) {
            return number.longValue();
        }
        return null;
    }

    private String evalString(String expression, Map<String, Object> variables) {
        if (!StringUtils.hasText(expression)) {
            return null;
        }
        // 纯文案（如"创建工单"）直接返回，不交给 SpEL——
        // 否则 SpEL 会把它当成属性引用去解析，找不到属性直接抛异常，备注就丢了。
        // 只有以 SpEL 特征字符开头的写法才当表达式：
        //   '文本' + #result.status   /   #request.remark   /   @logRemarkHelper.transfer(#request)
        if (!isSpelExpression(expression)) {
            return expression;
        }
        Object value = eval(expression, variables);
        return value == null ? null : value.toString();
    }

    private boolean isSpelExpression(String expression) {
        char first = expression.trim().charAt(0);
        return first == '\'' || first == '"' || first == '#' || first == '@';
    }

    private Object eval(String expression, Map<String, Object> variables) {
        if (!StringUtils.hasText(expression)) {
            return null;
        }
        try {
            return expressionParser.parseExpression(expression).getValue(buildContext(variables));
        } catch (Exception e) {
            // 表达式解析失败不影响主流程，日志降级为"无该字段"
            log.warn("解析 @LogRecord 表达式失败: {}，原因: {}", expression, e.getMessage());
            return null;
        }
    }

    private String statusOf(Long ticketId) {
        if (ticketId == null) {
            return null;
        }
        Ticket ticket = ticketMapper.selectById(ticketId);
        return ticket == null ? null : ticket.getStatus();
    }

    /**
     * 日志落库时机：事务提交之后再异步写。
     * 若在事务提交前写，日志里的工单ID可能因为同事务的数据尚未提交而触发外键失败（如新建工单场景）。
     */
    private void saveLogAfterCommit(Long ticketId, Long operatorId, String action,
                                   String beforeStatus, String afterStatus, String remark) {
        if (ticketId == null) {
            log.warn("未解析到工单ID，跳过操作日志记录: action={}", action);
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    operatorLogService.saveLogAsync(ticketId, operatorId, action, beforeStatus, afterStatus, remark);
                }
            });
        } else {
            operatorLogService.saveLogAsync(ticketId, operatorId, action, beforeStatus, afterStatus, remark);
        }
    }
}
