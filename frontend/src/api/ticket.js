import request from './request'

/**
 * 分页查询工单
 * @param {{ pageNum, pageSize, status?, priority?, category?, keyword?, scope? }} params
 * scope = 'mine' 时仅返回当前客服负责的工单（数据范围由后端强制过滤）
 */
export function getTicketPage(params) {
  return request.get('/tickets', { params })
}

/** 工单详情（含操作日志日志、附件、评价） */
export function getTicketDetail(id) {
  return request.get(`/tickets/${id}`)
}

/** 抢单（客服）：后端 Redis 分布式锁 + 乐观锁，失败返回 400「该工单已被他人接单」 */
export function grabTicket(id) {
  return request.post(`/tickets/${id}/grab`)
}

/** 接单（管理员）：管理员没有抢单语义，等价于把工单指派给自己 */
export function takeTicket(id) {
  return request.post(`/tickets/${id}/take`)
}

/** 开始处理：ASSIGNED / REOPENED -> PROCESSING */
export function startProcessing(id) {
  return request.post(`/tickets/${id}/start`)
}

/** 标记已解决：PROCESSING -> RESOLVED */
export function resolveTicket(id) {
  return request.post(`/tickets/${id}/resolve`)
}

/** 确认解决并关闭：PENDING_CONFIRM -> CLOSED */
export function closeTicket(id) {
  return request.post(`/tickets/${id}/close`)
}

/** 驳回/重新打开：PENDING_CONFIRM -> REOPENED */
export function reopenTicket(id) {
  return request.post(`/tickets/${id}/reopen`)
}

/** 取消工单：PENDING / PROCESSING -> CANCELLED */
export function cancelTicket(id) {
  return request.post(`/tickets/${id}/cancel`)
}
