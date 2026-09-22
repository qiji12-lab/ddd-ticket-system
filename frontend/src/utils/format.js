/**
 * 后端 LocalDateTime 默认序列化为 ISO 字符串（2026-08-01T09:30:00），
 * 这里统一转成页面展示格式，避免各组件各写一套截取逻辑。
 */
export function formatDateTime(value) {
  if (!value) return '-'
  return String(value).replace('T', ' ').slice(0, 19)
}
