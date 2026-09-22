/**
 * 业务字典 —— 与后端枚举一一对应，是前端文案/颜色的唯一真相。
 * 后端 TicketResponse 只返回状态 code（statusDesc 等字段未填充），
 * 因此所有"code -> 中文/颜色"的映射都收敛在这里，避免各页面各写一份 switch。
 */

/** 工单状态：TicketStatus */
export const STATUS_MAP = {
  PENDING: { label: '待接单', tagType: 'danger' },
  ASSIGNED: { label: '已分配', tagType: 'warning' },
  PROCESSING: { label: '处理中', tagType: 'primary' },
  RESOLVED: { label: '已解决', tagType: 'success' },
  PENDING_CONFIRM: { label: '待确认', tagType: 'warning' },
  REOPENED: { label: '已重开', tagType: 'info' },
  CLOSED: { label: '已关闭', tagType: 'info' },
  CANCELLED: { label: '已取消', tagType: 'info' }
}

/** 优先级：TicketPriority */
export const PRIORITY_MAP = {
  HIGH: { label: '高', tagType: 'danger' },
  MEDIUM: { label: '中', tagType: 'warning' },
  LOW: { label: '低', tagType: 'info' }
}

/** 问题分类：TicketCategory */
export const CATEGORY_MAP = {
  TECHNICAL: '技术问题',
  ACCOUNT: '账号问题',
  COMPLAINT: '投诉建议'
}

/** 角色：UserRole */
export const ROLE_MAP = {
  ADMIN: '管理员',
  AGENT: '客服',
  CUSTOMER: '客户'
}

/**
 * 角色功能权限矩阵 —— 前端可见性控制的唯一真相。
 * 真正的安全边界在后端（@RequiresRole + Service 层数据范围校验），
 * 前端这里只负责"不给用户点不可能成功的按钮"，避免无效请求和困惑。
 * canViewPool : 是否展示公共工单池入口
 * canGrab     : 是否可接单（客服抢单 / 管理员指派给自己）
 * showScope   : 是否展示"公共池 / 我负责的"范围切换
 */
export const ROLE_PERMISSIONS = {
  ADMIN: { canViewPool: true, canGrab: true, showScope: false },
  AGENT: { canViewPool: true, canGrab: true, showScope: true },
  CUSTOMER: { canViewPool: true, canGrab: false, showScope: false }
}

export const DEFAULT_PERMISSIONS = { canViewPool: false, canGrab: false, showScope: false }

/** 操作日志 action -> el-timeline 节点颜色（TicketDetailResponse.LogResponse.action） */
export const LOG_TIMELINE_TYPE = {
  CREATE: 'primary',
  ASSIGN: 'warning',
  TAKE: 'warning',
  GRAB: 'warning',
  START_PROCESS: 'primary',
  RESOLVE: 'success',
  CLOSE: 'info',
  REOPEN: 'danger',
  UPDATE: 'primary',
  UPLOAD_ATTACHMENT: 'primary'
}

export const STATUS_OPTIONS = Object.entries(STATUS_MAP).map(([value, item]) => ({
  value,
  label: item.label
}))

export const PRIORITY_OPTIONS = Object.entries(PRIORITY_MAP).map(([value, item]) => ({
  value,
  label: item.label
}))

export const CATEGORY_OPTIONS = Object.entries(CATEGORY_MAP).map(([value, label]) => ({
  value,
  label
}))

/** code -> 中文，未知 code 原样返回，保证后端新增枚举时页面不会显示空白 */
export function statusLabel(code) {
  return STATUS_MAP[code]?.label || code || '-'
}

export function priorityLabel(code) {
  return PRIORITY_MAP[code]?.label || code || '-'
}

export function categoryLabel(code) {
  return CATEGORY_MAP[code] || code || '-'
}

export function statusTagType(code) {
  return STATUS_MAP[code]?.tagType || 'info'
}

export function priorityTagType(code) {
  return PRIORITY_MAP[code]?.tagType || 'info'
}
