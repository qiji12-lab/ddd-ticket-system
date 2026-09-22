package com.ticketide.state;

import com.ticketide.entity.Ticket;
import com.ticketide.enums.TicketStatus;
import com.ticketide.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工单状态机单元测试（纯业务规则，不依赖数据库与日志）。
 * 合法流转路径：
 * PENDING -> ASSIGNED/PROCESSING -> PROCESSING -> RESOLVED -> PENDING_CONFIRM -> CLOSED/REOPENED
 * 以及取消分支：PENDING/PROCESSING -> CANCELLED（终态）
 * <p>
 * 说明：日志记录已由 @LogRecord + AOP 承担，因此这里只验证"状态能否流转"。
 */
class TicketStatusMachineTest {

    private final TicketStatusMachine statusMachine = new TicketStatusMachine();

    private Ticket buildTicket(Long id, TicketStatus status) {
        Ticket ticket = new Ticket();
        ticket.setId(id);
        ticket.setStatus(status.getCode());
        return ticket;
    }

    // ==================== 合法流转 ====================

    @Nested
    @DisplayName("合法流转路径")
    class LegalTransitions {

        @ParameterizedTest(name = "{0} -> {1} 应放行")
        @CsvSource({
                "PENDING,         ASSIGNED",
                "PENDING,         PROCESSING",
                "ASSIGNED,        PROCESSING",
                "PROCESSING,      RESOLVED",
                "RESOLVED,        PENDING_CONFIRM",
                "PENDING_CONFIRM, CLOSED",
                "PENDING_CONFIRM, REOPENED",
                "REOPENED,        PROCESSING",
                "CLOSED,          REOPENED",
                // 客户主动取消：待接单 / 处理中均可取消
                "PENDING,         CANCELLED",
                "PROCESSING,      CANCELLED",
                // 兼容历史数据：历史 RESOLVED 工单可直接关闭/重开
                "RESOLVED,        CLOSED",
                "RESOLVED,        REOPENED"
        })
        void shouldAllowLegalTransition(String from, String to) {
            Ticket ticket = buildTicket(1L, TicketStatus.valueOf(from));

            Ticket result = statusMachine.changeStatus(ticket, TicketStatus.valueOf(to));

            assertEquals(to, result.getStatus());
        }

        @Test
        @DisplayName("完整主链路：待接单→处理中→已解决→待确认→已关闭")
        void shouldWalkFullMainPath() {
            Ticket ticket = buildTicket(100L, TicketStatus.PENDING);

            statusMachine.changeStatus(ticket, TicketStatus.PROCESSING);
            assertEquals("PROCESSING", ticket.getStatus());

            statusMachine.changeStatus(ticket, TicketStatus.RESOLVED);
            assertEquals("RESOLVED", ticket.getStatus());

            statusMachine.changeStatus(ticket, TicketStatus.PENDING_CONFIRM);
            assertEquals("PENDING_CONFIRM", ticket.getStatus());

            statusMachine.changeStatus(ticket, TicketStatus.CLOSED);
            assertEquals("CLOSED", ticket.getStatus());
        }

        @Test
        @DisplayName("重开分支：待确认→已重开→处理中→已解决→待确认")
        void shouldWalkReopenBranch() {
            Ticket ticket = buildTicket(200L, TicketStatus.PENDING_CONFIRM);

            statusMachine.changeStatus(ticket, TicketStatus.REOPENED);
            statusMachine.changeStatus(ticket, TicketStatus.PROCESSING);
            statusMachine.changeStatus(ticket, TicketStatus.RESOLVED);
            statusMachine.changeStatus(ticket, TicketStatus.PENDING_CONFIRM);

            assertEquals("PENDING_CONFIRM", ticket.getStatus());
        }

        @Test
        @DisplayName("取消分支：待接单/处理中取消后进入终态，不可再流转")
        void shouldCancelAndEnterTerminalState() {
            Ticket pendingTicket = buildTicket(301L, TicketStatus.PENDING);
            statusMachine.changeStatus(pendingTicket, TicketStatus.CANCELLED);
            assertEquals("CANCELLED", pendingTicket.getStatus());
            // 终态不可再被接单
            assertThrows(BusinessException.class,
                    () -> statusMachine.changeStatus(pendingTicket, TicketStatus.PROCESSING));

            Ticket processingTicket = buildTicket(302L, TicketStatus.PROCESSING);
            statusMachine.changeStatus(processingTicket, TicketStatus.CANCELLED);
            assertEquals("CANCELLED", processingTicket.getStatus());

            assertTrue(TicketStatus.CANCELLED.isTerminal(), "已取消应为终态");
        }
    }

    // ==================== 非法流转 ====================

    @Nested
    @DisplayName("非法流转必须被拒绝")
    class IllegalTransitions {

        @ParameterizedTest(name = "{0} -> {1} 应抛出BusinessException")
        @CsvSource({
                "PENDING,         RESOLVED",
                "PENDING,         CLOSED",
                "PENDING,         PENDING_CONFIRM",
                "ASSIGNED,        CLOSED",
                "ASSIGNED,        RESOLVED",
                "PROCESSING,      PENDING",
                "PROCESSING,      CLOSED",
                "PROCESSING,      PENDING_CONFIRM",
                "RESOLVED,        PROCESSING",
                "PENDING_CONFIRM, PROCESSING",
                "PENDING_CONFIRM, RESOLVED",
                "REOPENED,        CLOSED",
                "CLOSED,          PROCESSING",
                "CLOSED,          PENDING_CONFIRM",
                // 取消的边界：已分配（已派单）不可取消；已解决/待确认/已关闭不可取消；已取消为终态
                "ASSIGNED,        CANCELLED",
                "RESOLVED,        CANCELLED",
                "PENDING_CONFIRM, CANCELLED",
                "CLOSED,          CANCELLED",
                "CANCELLED,       PROCESSING",
                "CANCELLED,       PENDING",
                "CANCELLED,       CLOSED"
        })
        void shouldRejectIllegalTransition(String from, String to) {
            Ticket ticket = buildTicket(1L, TicketStatus.valueOf(from));

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> statusMachine.changeStatus(ticket, TicketStatus.valueOf(to)));

            assertTrue(ex.getMessage().contains("不能流转到"),
                    "异常信息应说明非法流转，实际: " + ex.getMessage());
            // 状态保持不变
            assertEquals(from, ticket.getStatus());
        }

        @Test
        @DisplayName("不可跳跃：待接单不能直接关闭")
        void pendingCannotJumpToClosed() {
            Ticket ticket = buildTicket(1L, TicketStatus.PENDING);
            assertThrows(BusinessException.class,
                    () -> statusMachine.changeStatus(ticket, TicketStatus.CLOSED));
        }

        @Test
        @DisplayName("终态后只能重开：已关闭不能再次关闭")
        void closedCannotCloseAgain() {
            Ticket ticket = buildTicket(1L, TicketStatus.CLOSED);
            assertThrows(BusinessException.class,
                    () -> statusMachine.changeStatus(ticket, TicketStatus.CLOSED));
        }
    }
}
