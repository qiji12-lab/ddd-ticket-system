<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  cancelTicket,
  closeTicket,
  getTicketDetail,
  grabTicket,
  reopenTicket,
  resolveTicket,
  startProcessing,
  takeTicket
} from '@/api/ticket'
import { useUserStore } from '@/stores/user'
import { formatDateTime } from '@/utils/format'
import {
  LOG_TIMELINE_TYPE,
  categoryLabel,
  priorityLabel,
  priorityTagType,
  statusLabel,
  statusTagType
} from '@/utils/constants'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const loading = ref(false)
// 操作按钮统一 Loading：任意状态流转请求在途时，禁用全部按钮，避免重复提交非法流转
const acting = ref(false)
const ticket = ref(null)

async function fetchDetail() {
  loading.value = true
  try {
    ticket.value = await getTicketDetail(route.params.id)
  } catch (error) {
    // 无权限（客户查看他人工单）或工单不存在：提示已由拦截器弹出，这里退回列表
    ticket.value = null
    router.replace({ name: 'pool' })
  } finally {
    loading.value = false
  }
}

/**
 * 按「当前状态 + 当前角色」计算可见操作按钮。
 * 与后端 TicketStatusMachine 的合法流转表保持一致：
 * 前端只决定"按钮展不展示"（避免用户点不可能成功的按钮），
 * 真正的合法性校验由后端状态机 + @RequiresRole 兜底，前端永远不能作为安全边界。
 */
const actions = computed(() => {
  const current = ticket.value
  if (!current) return []

  const role = userStore.role
  const isAgent = role === 'AGENT'
  const isAdmin = role === 'ADMIN'
  const isCustomer = role === 'CUSTOMER'

  const list = []
  const pushGrab = () => {
    // 客服走抢单接口（Redis 分布式锁），管理员没有抢单语义，走接单接口直接指派给自己
    list.push(
      isAgent
        ? { key: 'grab', label: '接单', type: 'primary', run: () => grabTicket(current.id), successText: '接单成功' }
        : { key: 'take', label: '接单', type: 'primary', run: () => takeTicket(current.id), successText: '接单成功' }
    )
  }
  const pushStart = () =>
    list.push({
      key: 'start',
      label: '开始处理',
      type: 'primary',
      run: () => startProcessing(current.id),
      successText: '已开始处理'
    })
  const pushCancel = () =>
    list.push({
      key: 'cancel',
      label: '取消工单',
      type: 'danger',
      confirm: '取消后工单不可恢复，确定取消吗？',
      run: () => cancelTicket(current.id),
      successText: '工单已取消'
    })
  const pushClose = () =>
    list.push({
      key: 'close',
      label: '确认解决',
      type: 'success',
      confirm: '确认问题已解决并关闭工单吗？',
      run: () => closeTicket(current.id),
      successText: '工单已关闭'
    })

  switch (current.status) {
    case 'PENDING':
      if (isAgent || isAdmin) pushGrab()
      if (isCustomer || isAdmin) pushCancel()
      break
    case 'ASSIGNED':
      if (isAgent || isAdmin) pushStart()
      break
    case 'PROCESSING':
      if (isAgent || isAdmin) {
        list.push({
          key: 'resolve',
          label: '标记已解决',
          type: 'success',
          run: () => resolveTicket(current.id),
          successText: '已标记为已解决，等待客户确认'
        })
      }
      if (isCustomer || isAdmin) pushCancel()
      break
    case 'PENDING_CONFIRM':
      // 需求核心场景：待确认时客户看到【确认解决】与【驳回】
      if (isCustomer || isAdmin) pushClose()
      if (isCustomer) {
        list.push({
          key: 'reopen',
          label: '驳回',
          type: 'warning',
          confirm: '驳回后工单将回到处理中，确定驳回吗？',
          run: () => reopenTicket(current.id),
          successText: '已驳回，工单回到处理中'
        })
      }
      break
    case 'RESOLVED':
      // 兼容历史数据：已解决可直接关闭
      if (isAdmin) pushClose()
      break
    case 'CLOSED':
      if (isAdmin) {
        list.push({
          key: 'reopen',
          label: '重新打开',
          type: 'warning',
          run: () => reopenTicket(current.id),
          successText: '工单已重新打开'
        })
      }
      break
    case 'REOPENED':
      if (isAgent || isAdmin) pushStart()
      break
    default:
      // CANCELLED 为终态，无任何可用操作
      break
  }

  return list
})

async function handleAction(action) {
  // 二次确认：不可逆操作（取消/驳回/关闭）必须让用户明确确认
  if (action.confirm) {
    const confirmed = await ElMessageBox.confirm(action.confirm, '操作确认', {
      type: 'warning',
      confirmButtonText: '确定',
      cancelButtonText: '取消'
    })
      .then(() => true)
      .catch(() => false)
    if (!confirmed) return
  }

  acting.value = true
  try {
    await action.run()
    ElMessage.success(action.successText)
    await fetchDetail()
  } catch (error) {
    // 非法流转、越权操作等后端错误提示由响应拦截器统一弹出（如"该工单已被他人接单"）
  } finally {
    acting.value = false
  }
}

onMounted(fetchDetail)
</script>

<template>
  <div v-loading="loading">
    <template v-if="ticket">
      <el-card class="page-card" shadow="never">
        <template #header>
          <div class="detail-header">
            <div>
              <span class="detail-title">{{ ticket.title }}</span>
              <el-tag :type="statusTagType(ticket.status)" class="detail-status">
                {{ statusLabel(ticket.status) }}
              </el-tag>
            </div>
            <div class="detail-actions">
              <el-button
                v-for="action in actions"
                :key="action.key"
                :type="action.type"
                :loading="acting"
                @click="handleAction(action)"
              >
                {{ action.label }}
              </el-button>
              <el-button @click="router.back()">返回</el-button>
            </div>
          </div>
        </template>

        <el-descriptions :column="2" border>
          <el-descriptions-item label="工单号">{{ ticket.id }}</el-descriptions-item>
          <el-descriptions-item label="优先级">
            <el-tag :type="priorityTagType(ticket.priority)" size="small" effect="dark">
              {{ priorityLabel(ticket.priority) }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="问题分类">{{ categoryLabel(ticket.category) }}</el-descriptions-item>
          <el-descriptions-item label="提交人">{{ ticket.customerName || '-' }}</el-descriptions-item>
          <el-descriptions-item label="经办人">{{ ticket.agentName || '待接单' }}</el-descriptions-item>
          <el-descriptions-item label="提交时间">{{ formatDateTime(ticket.createTime) }}</el-descriptions-item>
          <el-descriptions-item label="最近更新">{{ formatDateTime(ticket.updateTime) }}</el-descriptions-item>
          <el-descriptions-item label="问题描述" :span="2">
            <div class="detail-description">{{ ticket.description }}</div>
          </el-descriptions-item>
        </el-descriptions>
      </el-card>

      <el-card shadow="never">
        <template #header>
          <span>操作日志（最新操作在最上方）</span>
        </template>

        <el-timeline v-if="ticket.logs?.length">
          <el-timeline-item
            v-for="log in ticket.logs"
            :key="log.id"
            :type="LOG_TIMELINE_TYPE[log.action] || 'primary'"
            :timestamp="formatDateTime(log.createTime)"
            placement="top"
          >
            <div class="log-action">{{ log.actionDesc || log.action }}</div>
            <div class="log-meta">
              <span>{{ log.operatorName || (log.operatorId ? `用户#${log.operatorId}` : '系统') }}</span>
              <template v-if="log.beforeStatus || log.afterStatus">
                <el-tag v-if="log.beforeStatus" size="small" type="info" effect="plain">
                  {{ statusLabel(log.beforeStatus) }}
                </el-tag>
                <span>→</span>
                <el-tag v-if="log.afterStatus" size="small" effect="plain">
                  {{ statusLabel(log.afterStatus) }}
                </el-tag>
              </template>
            </div>
            <div v-if="log.remark" class="log-remark">备注：{{ log.remark }}</div>
          </el-timeline-item>
        </el-timeline>

        <el-empty v-else description="暂无操作日志" />
      </el-card>
    </template>
  </div>
</template>

<style scoped>
.detail-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
}

.detail-title {
  font-size: 16px;
  font-weight: 600;
  margin-right: 8px;
}

.detail-actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.detail-description {
  white-space: pre-wrap;
  line-height: 1.6;
}

.log-action {
  font-weight: 600;
}

.log-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 4px;
  color: #909399;
  font-size: 13px;
}

.log-remark {
  margin-top: 4px;
  color: #606266;
  font-size: 13px;
}
</style>
