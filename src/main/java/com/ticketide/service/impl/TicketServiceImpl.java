package com.ticketide.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ticketide.annotation.LogRecord;
import com.ticketide.dto.message.TicketNotificationMessage;
import com.ticketide.dto.request.TicketCreateRequest;
import com.ticketide.dto.request.TicketEscalateRequest;
import com.ticketide.dto.request.TicketQueryRequest;
import com.ticketide.dto.request.TicketStatusChangeRequest;
import com.ticketide.dto.request.TicketTransferRequest;
import com.ticketide.dto.request.TicketUpdateRequest;
import com.ticketide.dto.response.AttachmentResponse;
import com.ticketide.dto.response.RatingResponse;
import com.ticketide.dto.response.TicketDetailResponse;
import com.ticketide.entity.OperatorLog;
import com.ticketide.entity.Ticket;
import com.ticketide.entity.User;
import com.ticketide.enums.TicketCategory;
import com.ticketide.enums.TicketPriority;
import com.ticketide.enums.TicketStatus;
import com.ticketide.enums.UserRole;
import com.ticketide.exception.BusinessException;
import com.ticketide.mapper.OperatorLogMapper;
import com.ticketide.mapper.TicketMapper;
import com.ticketide.service.AttachmentService;
import com.ticketide.service.RabbitMQService;
import com.ticketide.service.RatingService;
import com.ticketide.service.RedisService;
import com.ticketide.service.TicketService;
import com.ticketide.service.UserService;
import com.ticketide.state.TicketStatusMachine;
import com.ticketide.util.DataMaskUtil;
import com.ticketide.util.RedisLockUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Slf4j
public class TicketServiceImpl implements TicketService {

    /**
     * 允许"转交/升级"的在办状态集合：
     * 只有工单还在处理链路上时才需要换人处理或升级优先级；
     * 已解决、待客户确认、已关闭、已取消的工单再转交/升级属于无效操作。
     */
    private static final Set<TicketStatus> OPERABLE_STATUSES =
            Set.of(TicketStatus.ASSIGNED, TicketStatus.PROCESSING, TicketStatus.REOPENED);

    /**
     * 待接单超时提醒的幂等标记前缀：同一工单 24 小时内只提醒一次，避免每轮扫描重复发邮件
     */
    private static final String REMIND_KEY_PREFIX = "ticket:remind:pending:";
    private static final long REMIND_TTL_SECONDS = 24 * 60 * 60L;

    /**
     * 单次扫描最多处理的工单数，防止一次拉太多数据把内存/下游打满
     */
    private static final int TIMEOUT_SCAN_LIMIT = 200;

    private final TicketMapper ticketMapper;
    private final OperatorLogMapper operatorLogMapper;
    private final UserService userService;
    private final TicketStatusMachine statusMachine;
    private final RedisLockUtil redisLockUtil;
    private final AttachmentService attachmentService;
    private final RatingService ratingService;
    private final RedisService redisService;
    private RabbitMQService rabbitMQService;

    @Autowired
    public void setRabbitMQService(Optional<RabbitMQService> rabbitMQService) {
        rabbitMQService.ifPresent(service -> this.rabbitMQService = service);
    }

    @Override
    public String getUserRole(Long userId) {
        if (userId == null) {
            return null;
        }
        User user = userService.getUserById(userId);
        return user == null ? null : user.getRole();
    }

    @Override
    @Transactional
    @LogRecord(action = "CREATE", remark = "创建工单", ticketId = "#result.id")
    public Ticket createTicket(TicketCreateRequest request, Long creatorId) {
        User creator = userService.getUserById(creatorId);
        if (creator == null) {
            throw new BusinessException("创建人不存在");
        }

        Ticket ticket = new Ticket();
        ticket.setTitle(request.getTitle());
        ticket.setDescription(request.getDescription());
        ticket.setPriority(request.getPriority());
        ticket.setCategory(request.getCategory());
        ticket.setStatus(TicketStatus.PENDING.getCode());
        ticket.setCustomerId(creatorId);

        ticketMapper.insert(ticket);

        return ticket;
    }

    @Override
    public Ticket getTicketById(Long id) {
        return ticketMapper.selectById(id);
    }

    @Override
    public TicketDetailResponse getTicketDetail(Long id) {
        return getTicketDetail(id, null, null);
    }

    @Override
    public TicketDetailResponse getTicketDetail(Long id, Long operatorId, String role) {
        Ticket ticket = ticketMapper.selectById(id);
        if (ticket == null) {
            throw new BusinessException("工单不存在");
        }

        // 数据权限：客户只能查看自己创建的工单
        if ("CUSTOMER".equals(role) && !ticket.getCustomerId().equals(operatorId)) {
            throw new BusinessException("无权查看此工单");
        }

        LambdaQueryWrapper<OperatorLog> logWrapper = new LambdaQueryWrapper<>();
        logWrapper.eq(OperatorLog::getTicketId, id);
        logWrapper.orderByDesc(OperatorLog::getCreateTime);
        List<OperatorLog> logs = operatorLogMapper.selectList(logWrapper);

        List<TicketDetailResponse.LogResponse> logResponses = logs.stream()
                .map(TicketDetailResponse.LogResponse::fromEntity)
                .collect(Collectors.toList());

        TicketDetailResponse response = TicketDetailResponse.fromEntity(ticket, logResponses);

        User customer = userService.getUserById(ticket.getCustomerId());
        if (customer != null) {
            response.setCustomerName(customer.getUsername());
        }

        if (ticket.getAgentId() != null) {
            User agent = userService.getUserById(ticket.getAgentId());
            if (agent != null) {
                response.setAgentName(agent.getUsername());
            }
        }

        response.setStatusDesc(TicketStatus.fromCode(ticket.getStatus()).getDesc());
        if (ticket.getCategory() != null) {
            try {
                response.setCategoryDesc(TicketCategory.fromCode(ticket.getCategory()).getDesc());
            } catch (Exception ignored) {
                response.setCategoryDesc(ticket.getCategory());
            }
        }

        // 加载附件列表
        List<AttachmentResponse> attachments = attachmentService.listByTicketId(id);
        response.setAttachments(attachments);

        // 加载评价记录（未评价返回 null，前端据此展示"去评价"入口）；
        // 评价内容属于用户自由填写，可能夹带联系方式，随详情返回给客服/管理员时统一脱敏
        RatingResponse rating = ratingService.getByTicketId(id);
        if (rating != null) {
            rating.setContent(DataMaskUtil.mask(rating.getContent()));
        }
        response.setRating(rating);

        return response;
    }

    @Override
    public IPage<Ticket> getTicketsByPage(Page<Ticket> page, TicketQueryRequest query, Long operatorId, String role) {
        LambdaQueryWrapper<Ticket> wrapper = new LambdaQueryWrapper<>();

        // 【SQL 注入防护】所有筛选条件都走条件构造器，最终生成的是 #{...} 预编译占位符，
        // 前端传入的值只作为参数绑定、永远不会拼接进 SQL 文本。
        if (StringUtils.hasText(query.getStatus())) {
            wrapper.eq(Ticket::getStatus, query.getStatus());
        }
        if (StringUtils.hasText(query.getPriority())) {
            wrapper.eq(Ticket::getPriority, query.getPriority());
        }
        if (StringUtils.hasText(query.getCategory())) {
            wrapper.eq(Ticket::getCategory, query.getCategory());
        }
        if (StringUtils.hasText(query.getKeyword())) {
            String keyword = escapeLike(query.getKeyword().trim());
            wrapper.and(w -> w.like(Ticket::getTitle, keyword).or().like(Ticket::getDescription, keyword));
        }
        // 提交时间范围：结束日期按"次日 00:00 之前"处理，保证用户选当天也能查到当天的数据
        if (query.getStartTime() != null) {
            wrapper.ge(Ticket::getCreateTime, query.getStartTime().atStartOfDay());
        }
        if (query.getEndTime() != null) {
            wrapper.lt(Ticket::getCreateTime, query.getEndTime().plusDays(1).atStartOfDay());
        }

        applyDataScope(wrapper, operatorId, role, query.getScope());

        wrapper.orderByDesc(Ticket::getUpdateTime);
        IPage<Ticket> resultPage = ticketMapper.selectPage(page, wrapper);

        // 批量填充创建人/经办人名称（避免 N+1 查询）
        Map<Long, String> userNameMap = userService.getAllUsers().stream()
                .collect(Collectors.toMap(User::getId, User::getUsername, (a, b) -> a));
        resultPage.getRecords().forEach(t -> {
            t.setCustomerName(userNameMap.get(t.getCustomerId()));
            if (t.getAgentId() != null) {
                t.setAgentName(userNameMap.get(t.getAgentId()));
            }
        });

        return resultPage;
    }

    /**
     * 数据权限范围（服务端强制，不信任前端传参）：
     * - CUSTOMER：只能看自己创建的工单；
     * - AGENT：默认只能看"公共抢单池（待接单）"和"自己负责的工单"，不能围观其他客服正在处理的工单；
     *          scope=mine 时进一步收窄为只看自己负责的；
     * - ADMIN：不受限。
     */
    private void applyDataScope(LambdaQueryWrapper<Ticket> wrapper, Long operatorId, String role, String scope) {
        if (UserRole.CUSTOMER.getCode().equals(role)) {
            wrapper.eq(Ticket::getCustomerId, operatorId);
        } else if (UserRole.AGENT.getCode().equals(role)) {
            if ("mine".equals(scope)) {
                wrapper.eq(Ticket::getAgentId, operatorId);
            } else {
                wrapper.and(w -> w.eq(Ticket::getStatus, TicketStatus.PENDING.getCode())
                        .or().eq(Ticket::getAgentId, operatorId));
            }
        }
    }

    /**
     * 转义 LIKE 通配符。
     * 关键词虽然是参数绑定（无注入风险），但 % 和 _ 会被数据库当成通配符：
     * 用户输入一个 % 就能匹配全表（越权窥探 + 索引失效导致全表扫描），因此按字面量转义。
     * MySQL 默认的 LIKE 转义符是反斜杠，无需额外声明 ESCAPE。
     */
    private String escapeLike(String keyword) {
        return keyword.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    @Override
    public IPage<Ticket> getPendingTickets(Page<Ticket> page) {
        return ticketMapper.selectPendingTickets(page, TicketStatus.PENDING.getCode());
    }

    @Override
    public IPage<Ticket> getTicketsByAssigneeId(Long assigneeId, Page<Ticket> page) {
        return ticketMapper.selectByAgentId(page, assigneeId);
    }

    @Override
    public IPage<Ticket> getTicketsByCreatorId(Long creatorId, Page<Ticket> page) {
        return ticketMapper.selectByCustomerId(page, creatorId);
    }

    @Override
    @Transactional
    @LogRecord(action = "UPDATE", statusChange = false, remark = "更新工单信息")
    public Ticket updateTicket(Long id, TicketUpdateRequest request) {
        Ticket ticket = ticketMapper.selectById(id);
        if (ticket == null) {
            throw new BusinessException("工单不存在");
        }

        if (StringUtils.hasText(request.getTitle())) {
            ticket.setTitle(request.getTitle());
        }
        if (StringUtils.hasText(request.getDescription())) {
            ticket.setDescription(request.getDescription());
        }
        if (StringUtils.hasText(request.getPriority())) {
            ticket.setPriority(request.getPriority());
        }

        assertUpdated(ticketMapper.updateById(ticket), "工单已被他人修改，请刷新后重试");

        return ticket;
    }

    @Override
    @Transactional
    @LogRecord(action = "CHANGE_STATUS", remark = "'状态变更为：' + #result.status")
    public Ticket updateTicketStatus(Long id, TicketStatusChangeRequest request, Long operatorId) {
        Ticket ticket = ticketMapper.selectById(id);
        if (ticket == null) {
            throw new BusinessException("工单不存在");
        }

        TicketStatus targetStatus = TicketStatus.fromCode(request.getTargetStatus());

        // 取消属于"客户主动终止"的独立用例，必须走 /cancel 接口以执行"仅创建人本人或管理员"的权限校验，
        // 这里直接拒绝，防止有人通过通用状态接口绕过权限取消他人的工单
        if (TicketStatus.CANCELLED == targetStatus) {
            throw new BusinessException("请通过取消工单接口执行取消操作");
        }

        statusMachine.changeStatus(ticket, targetStatus);

        assertUpdated(ticketMapper.updateById(ticket), "工单已被他人修改，请刷新后重试");

        // 进入"待确认"（或直接标记已解决）时通知客户
        if (TicketStatus.PENDING_CONFIRM == targetStatus || TicketStatus.RESOLVED == targetStatus) {
            notifyCustomer(ticket, null);
        }

        return ticket;
    }

    @Override
    @Transactional
    @LogRecord(action = "ASSIGN", remark = "管理员派单")
    public Ticket assignTicket(Long id, Long assigneeId, Long operatorId) {
        Ticket ticket = ticketMapper.selectById(id);
        if (ticket == null) {
            throw new BusinessException("工单不存在");
        }

        User assignee = userService.getUserById(assigneeId);
        if (assignee == null) {
            throw new BusinessException("经办人不存在");
        }

        ticket.setAgentId(assigneeId);
        statusMachine.changeStatus(ticket, TicketStatus.ASSIGNED);

        assertUpdated(ticketMapper.updateById(ticket), "工单已被他人修改，请刷新后重试");

        return ticket;
    }

    @Override
    @Transactional
    @LogRecord(action = "TAKE", remark = "客服抢单成功")
    public Ticket grabTicket(Long id, Long operatorId) {
        // 【第一道防线：Redisson 分布式锁】
        // 锁粒度为"工单ID"，不同工单的抢单互不阻塞；同一工单的抢单请求被串行化，
        // 让第一个客服走完"查状态 -> 改状态"的完整流程后再放行下一个，避免把无效请求打到数据库。
        // 使用看门狗模式加锁（不传 leaseTime）：业务耗时超过默认 30 秒租期时锁会自动续期，
        // 不会出现"业务还没提交、锁先过期、第二个客服抢到同一单"的情况。
        if (!redisLockUtil.tryLockWithWatchdog(id, 3, TimeUnit.SECONDS)) {
            throw new BusinessException("工单正在被抢，请稍后重试");
        }

        try {
            Ticket ticket = ticketMapper.selectById(id);
            if (ticket == null) {
                throw new BusinessException("工单不存在");
            }

            // 快速失败：非"待接单"状态直接返回友好提示，避免无意义的更新与日志
            TicketStatus currentStatus = TicketStatus.fromCode(ticket.getStatus());
            if (TicketStatus.PENDING != currentStatus) {
                throw new BusinessException("只有待接单状态的工单才能抢单");
            }

            User operator = userService.getUserById(operatorId);
            if (operator == null) {
                throw new BusinessException("用户不存在");
            }
            if (!UserRole.AGENT.getCode().equals(operator.getRole())) {
                throw new BusinessException("只有客服才能抢单");
            }

            // 【第二道防线：数据库条件更新（CAS）防超卖】
            // 为什么分布式锁之外还需要它？因为 @Transactional 的提交发生在方法返回之后，
            // 而 finally 里的 unlock 在事务提交前就执行了，"锁已释放但事务未提交"的窗口期内，
            // 第二个客服可能拿到锁并读到尚未变更的旧状态（PENDING），从而两个人同时抢到同一单。
            // 把状态判断下沉到 UPDATE 的 WHERE 条件中，由数据库行锁保证原子性：
            // 影响行数为 0 就说明工单已被别人抢走，直接抛业务异常并回滚。
            Integer version = ticket.getVersion() == null ? 0 : ticket.getVersion();
            int rows = ticketMapper.grabIfPending(id, operatorId,
                    TicketStatus.PENDING.getCode(), TicketStatus.PROCESSING.getCode(), version);
            if (rows == 0) {
                throw new BusinessException("手慢了，该工单已被其他客服抢走");
            }

            // 同步内存中的实体，保证返回值与数据库一致（version 已在 SQL 中自增）
            ticket.setAgentId(operatorId);
            ticket.setStatus(TicketStatus.PROCESSING.getCode());
            ticket.setVersion(version + 1);

            // 操作日志由 @LogRecord 注解 + AOP 异步写入，这里不再有任何记日志的代码
            // 通知客户：工单已被受理（MQ 异步，失败不影响抢单结果）
            notifyCustomer(ticket, "您的工单已被客服受理，正在处理中");
            log.info("客服[{}]成功抢单: ticketId={}", operatorId, id);
            return ticket;
        } finally {
            // 无论成功失败都要释放锁，且 unlock 内部会校验锁的持有者，避免误释放他人持有的锁
            redisLockUtil.unlock(id);
        }
    }

    @Override
    @Transactional
    @LogRecord(action = "CANCEL", remark = "客户主动取消工单")
    public Ticket cancelTicket(Long id, Long operatorId) {
        Ticket ticket = ticketMapper.selectById(id);
        if (ticket == null) {
            throw new BusinessException("工单不存在");
        }

        User operator = userService.getUserById(operatorId);
        if (operator == null) {
            throw new BusinessException("用户不存在");
        }

        // 数据权限在服务端强制校验：只有工单创建人本人或管理员能取消，前端隐藏按钮不作为安全依据
        if (!isAdmin(operator) && !ticket.getCustomerId().equals(operatorId)) {
            throw new BusinessException("只能取消自己创建的工单");
        }

        // PENDING / PROCESSING -> CANCELLED；已解决、已关闭等状态由状态机直接拒绝
        statusMachine.changeStatus(ticket, TicketStatus.CANCELLED);

        assertUpdated(ticketMapper.updateById(ticket), "工单已被他人修改，请刷新后重试");

        log.info("用户[{}]取消工单: ticketId={}", operatorId, id);
        return ticket;
    }

    @Override
    @Transactional
    @LogRecord(action = "TRANSFER", statusChange = false,
            remark = "@logRemarkHelper.transfer(#request)")
    public Ticket transferTicket(Long id, TicketTransferRequest request, Long operatorId) {
        Ticket ticket = ticketMapper.selectById(id);
        if (ticket == null) {
            throw new BusinessException("工单不存在");
        }

        Long targetAgentId = request.getTargetAgentId();
        if (targetAgentId.equals(operatorId)) {
            throw new BusinessException("不能把工单转交给自己");
        }

        User targetAgent = userService.getUserById(targetAgentId);
        if (targetAgent == null) {
            throw new BusinessException("被转交的客服不存在");
        }
        if (!UserRole.AGENT.getCode().equals(targetAgent.getRole())) {
            throw new BusinessException("只能转交给客服（AGENT）角色");
        }

        User operator = userService.getUserById(operatorId);
        if (operator == null) {
            throw new BusinessException("用户不存在");
        }
        // 只有工单当前经办人或管理员有权转交，防止客服之间互相"抢"别人的工单
        if (!isAdmin(operator) && !operatorId.equals(ticket.getAgentId())) {
            throw new BusinessException("只能转交自己负责的工单");
        }

        TicketStatus currentStatus = TicketStatus.fromCode(ticket.getStatus());
        if (!OPERABLE_STATUSES.contains(currentStatus)) {
            throw new BusinessException(String.format("工单状态[%s]不允许转交", currentStatus.getDesc()));
        }

        Long fromAgentId = ticket.getAgentId();
        // 注意：转交只变更"经办人"这一领域属性，工单状态不变，因此不能走状态机
        //（状态机把 PROCESSING -> PROCESSING 视为非法流转）；
        // 但变更仍通过乐观锁 version 提交，避免与并发的状态变更互相覆盖。
        ticket.setAgentId(targetAgentId);
        assertUpdated(ticketMapper.updateById(ticket), "工单已被他人修改，请刷新后重试");

        log.info("客服[{}]将工单[{}]转交给客服[{}]（原经办人 {}）", operatorId, id, targetAgentId, fromAgentId);
        return ticket;
    }

    @Override
    @Transactional
    @LogRecord(action = "ESCALATE", statusChange = false,
            remark = "@logRemarkHelper.escalate(#request)")
    public Ticket escalateTicket(Long id, TicketEscalateRequest request, Long operatorId) {
        Ticket ticket = ticketMapper.selectById(id);
        if (ticket == null) {
            throw new BusinessException("工单不存在");
        }

        User operator = userService.getUserById(operatorId);
        if (operator == null) {
            throw new BusinessException("用户不存在");
        }
        if (!isAdmin(operator) && !operatorId.equals(ticket.getAgentId())) {
            throw new BusinessException("只能升级自己负责的工单");
        }

        TicketStatus currentStatus = TicketStatus.fromCode(ticket.getStatus());
        if (!OPERABLE_STATUSES.contains(currentStatus)) {
            throw new BusinessException(String.format("工单状态[%s]不允许升级", currentStatus.getDesc()));
        }
        if (TicketPriority.HIGH.getCode().equals(ticket.getPriority())) {
            throw new BusinessException("工单已是高优先级，无需重复升级");
        }

        String beforeStatus = ticket.getStatus();
        ticket.setPriority(TicketPriority.HIGH.getCode());
        assertUpdated(ticketMapper.updateById(ticket), "工单已被他人修改，请刷新后重试");

        // 通知主管：走 MQ 异步通道，不阻塞客服的升级操作；RabbitMQ 未启用时降级为日志
        notifySupervisors(ticket, operator);

        log.info("客服[{}]将工单[{}]升级为高优先级（原状态 {}）", operatorId, id, beforeStatus);
        return ticket;
    }

    @Override
    @Transactional
    @LogRecord(action = "TAKE", remark = "客服接单")
    public Ticket takeTicket(Long id, Long operatorId) {
        Ticket ticket = ticketMapper.selectById(id);
        if (ticket == null) {
            throw new BusinessException("工单不存在");
        }

        User operator = userService.getUserById(operatorId);
        if (operator == null) {
            throw new BusinessException("用户不存在");
        }

        ticket.setAgentId(operatorId);
        statusMachine.changeStatus(ticket, TicketStatus.PROCESSING);

        assertUpdated(ticketMapper.updateById(ticket), "工单已被他人修改，请刷新后重试");

        // 通知客户：工单已被受理
        notifyCustomer(ticket, "您的工单已被客服受理，正在处理中");
        return ticket;
    }

    @Override
    @Transactional
    @LogRecord(action = "START_PROCESS", remark = "开始处理")
    public Ticket startProcessing(Long id, Long operatorId) {
        Ticket ticket = ticketMapper.selectById(id);
        if (ticket == null) {
            throw new BusinessException("工单不存在");
        }

        User operator = userService.getUserById(operatorId);
        if (operator == null) {
            throw new BusinessException("用户不存在");
        }

        // 管理员可以处理任何工单，经办人只能处理自己负责的工单
        if (!"ADMIN".equals(operator.getRole()) && !ticket.getAgentId().equals(operatorId)) {
            throw new BusinessException("只能处理自己负责的工单");
        }

        // 如果管理员处理且工单未分配经办人，则分配给管理员
        if (ticket.getAgentId() == null) {
            ticket.setAgentId(operatorId);
        }

        statusMachine.changeStatus(ticket, TicketStatus.PROCESSING);

        assertUpdated(ticketMapper.updateById(ticket), "工单已被他人修改，请刷新后重试");

        return ticket;
    }

    @Override
    @Transactional
    @LogRecord(action = "RESOLVE", remark = "处理完成，提交客户确认")
    public Ticket resolveTicket(Long id, Long operatorId) {
        Ticket ticket = ticketMapper.selectById(id);
        if (ticket == null) {
            throw new BusinessException("工单不存在");
        }

        User operator = userService.getUserById(operatorId);
        if (operator == null) {
            throw new BusinessException("用户不存在");
        }

        // 管理员可以解决任何工单，经办人只能解决自己负责的工单
        if (!"ADMIN".equals(operator.getRole()) && !ticket.getAgentId().equals(operatorId)) {
            throw new BusinessException("只能解决自己负责的工单");
        }

        // PROCESSING -> RESOLVED：经办人标记已解决
        statusMachine.changeStatus(ticket, TicketStatus.RESOLVED);
        // RESOLVED -> PENDING_CONFIRM：自动流转，等待客户确认（关闭或重开）
        statusMachine.changeStatus(ticket, TicketStatus.PENDING_CONFIRM);

        assertUpdated(ticketMapper.updateById(ticket), "工单已被他人修改，请刷新后重试");

        // 进入"待确认"后通知客户确认
        notifyCustomer(ticket, "您的工单已解决，请登录系统确认：满意则关闭，不满意可重新打开");

        return ticket;
    }

    @Override
    @Transactional
    @LogRecord(action = "CLOSE", remark = "客户确认关闭工单")
    public Ticket closeTicket(Long id, Long operatorId) {
        Ticket ticket = ticketMapper.selectById(id);
        if (ticket == null) {
            throw new BusinessException("工单不存在");
        }

        User operator = userService.getUserById(operatorId);
        if (operator == null) {
            throw new BusinessException("用户不存在");
        }
        // 客户只能确认关闭自己创建的工单；管理员不受限
        if (!"ADMIN".equals(operator.getRole()) && !ticket.getCustomerId().equals(operatorId)) {
            throw new BusinessException("只能关闭自己创建的工单");
        }

        statusMachine.changeStatus(ticket, TicketStatus.CLOSED);

        assertUpdated(ticketMapper.updateById(ticket), "工单已被他人修改，请刷新后重试");

        return ticket;
    }

    @Override
    @Transactional
    @LogRecord(action = "REOPEN", remark = "工单被重新打开")
    public Ticket reopenTicket(Long id, Long operatorId) {
        Ticket ticket = ticketMapper.selectById(id);
        if (ticket == null) {
            throw new BusinessException("工单不存在");
        }

        User operator = userService.getUserById(operatorId);
        if (operator == null) {
            throw new BusinessException("用户不存在");
        }
        // 客户只能对自己创建的工单申请重开；管理员可重新打开任意工单
        if (!"ADMIN".equals(operator.getRole()) && !ticket.getCustomerId().equals(operatorId)) {
            throw new BusinessException("只能重新打开自己创建的工单");
        }

        statusMachine.changeStatus(ticket, TicketStatus.REOPENED);

        assertUpdated(ticketMapper.updateById(ticket), "工单已被他人修改，请刷新后重试");

        // 通知经办客服：客户驳回了处理结果
        notifyAssignedAgent(ticket, "客户驳回了处理结果，请重新处理该工单");
        return ticket;
    }

    @Override
    @Transactional
    public void deleteTicket(Long id) {
        Ticket ticket = ticketMapper.selectById(id);
        if (ticket == null) {
            throw new BusinessException("工单不存在");
        }
        ticketMapper.deleteById(id);
    }

    @Override
    public int remindPendingTimeoutTickets(int hours) {
        LocalDateTime deadline = LocalDateTime.now().minusHours(hours);
        List<Ticket> timeoutTickets = ticketMapper.selectPendingTimeoutTickets(
                TicketStatus.PENDING.getCode(), deadline, TIMEOUT_SCAN_LIMIT);

        int reminded = 0;
        for (Ticket ticket : timeoutTickets) {
            // 幂等：同一工单 24 小时内只提醒一次，否则每 10 分钟一轮扫描会把主管的邮箱刷爆
            if (!redisService.setIfAbsent(REMIND_KEY_PREFIX + ticket.getId(), REMIND_TTL_SECONDS)) {
                continue;
            }
            notifyAdmins(ticket, String.format("工单已待接单超过 %d 小时，请及时指派客服", hours));
            reminded++;
        }
        return reminded;
    }

    @Override
    public List<Long> findPendingConfirmTimeoutTicketIds(int hours) {
        return ticketMapper.selectPendingConfirmTimeoutIds(TicketStatus.PENDING_CONFIRM.getCode(),
                LocalDateTime.now().minusHours(hours), TIMEOUT_SCAN_LIMIT);
    }

    @Override
    @Transactional
    @LogRecord(action = "AUTO_CLOSE", remark = "待确认超时，系统自动关闭并默认好评")
    public Ticket autoCloseTimeoutTicket(Long id) {
        Ticket ticket = ticketMapper.selectById(id);
        if (ticket == null) {
            throw new BusinessException("工单不存在");
        }
        // 状态机保证只有"待确认"的工单能被自动关闭；
        // 若客户刚好抢先手动确认，这里会抛业务异常，由定时任务捕获后跳过
        statusMachine.changeStatus(ticket, TicketStatus.CLOSED);
        assertUpdated(ticketMapper.updateById(ticket), "工单已被他人修改，请刷新后重试");

        // 默认好评：客户超时未确认视为默认满意，保证满意度数据不缺失
        ratingService.rateByDefault(id, "系统超时自动关闭，默认好评");
        return ticket;
    }

    /**
     * 是否为管理员（主管）：管理员不受"只能操作自己负责的工单"限制
     */
    private boolean isAdmin(User user) {
        return UserRole.ADMIN.getCode().equals(user.getRole());
    }

    /**
     * 乐观锁更新结果校验。
     * updateById 在版本号不匹配时返回 0 行，如果忽略返回值就会被误认为"操作成功"，
     * 实际数据库并未变更（静默失败），因此统一在这里抛出业务异常，让前端提示用户刷新重试。
     */
    private void assertUpdated(int rows, String message) {
        if (rows == 0) {
            throw new BusinessException(message);
        }
    }

    /**
     * 通知主管（ADMIN 角色）——升级、待接单超时等需要人工介入的场景
     */
    private void notifySupervisors(Ticket ticket, User operator) {
        notifyAdmins(ticket, String.format("客服[%s]已将工单升级为高优先级，请及时关注",
                operator.getUsername()));
    }

    /**
     * 通知全部主管
     */
    private void notifyAdmins(Ticket ticket, String statusDesc) {
        List<User> admins = userService.getUsersByRole(UserRole.ADMIN.getCode());
        if (admins == null || admins.isEmpty()) {
            log.warn("未找到主管（ADMIN）用户，跳过通知: ticketId={}", ticket.getId());
            return;
        }
        admins.forEach(admin -> sendNotification(admin, ticket, statusDesc));
    }

    /**
     * 通知工单创建人（客户）
     */
    private void notifyCustomer(Ticket ticket, String statusDesc) {
        sendNotification(userService.getUserById(ticket.getCustomerId()), ticket, statusDesc);
    }

    /**
     * 通知当前经办客服（客户驳回场景）
     */
    private void notifyAssignedAgent(Ticket ticket, String statusDesc) {
        if (ticket.getAgentId() == null) {
            return;
        }
        sendNotification(userService.getUserById(ticket.getAgentId()), ticket, statusDesc);
    }

    /**
     * 发送通知：只负责组装消息投递到 MQ，发送动作异步且失败不影响主流程
     *
     * @param statusDesc 通知文案；为空时使用状态描述
     */
    private void sendNotification(User receiver, Ticket ticket, String statusDesc) {
        if (rabbitMQService == null) {
            log.warn("RabbitMQ服务未启用，跳过发送通知: ticketId={}", ticket.getId());
            return;
        }
        if (receiver == null || !StringUtils.hasText(receiver.getEmail())) {
            log.warn("收件人邮箱为空，跳过发送通知: ticketId={}", ticket.getId());
            return;
        }
        TicketNotificationMessage message = new TicketNotificationMessage();
        message.setTicketId(ticket.getId());
        message.setReceiverEmail(receiver.getEmail());
        message.setTicketTitle(ticket.getTitle());
        message.setStatus(ticket.getStatus());
        message.setStatusDesc(StringUtils.hasText(statusDesc)
                ? statusDesc
                : TicketStatus.fromCode(ticket.getStatus()).getDesc());
        rabbitMQService.sendNotification(message);
    }
}