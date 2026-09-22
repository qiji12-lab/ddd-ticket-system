<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getTicketPage, grabTicket, takeTicket } from '@/api/ticket'
import { useUserStore } from '@/stores/user'
import { debounce } from '@/utils/debounce'
import { formatDateTime } from '@/utils/format'
import {
  CATEGORY_OPTIONS,
  PRIORITY_OPTIONS,
  STATUS_OPTIONS,
  categoryLabel,
  priorityLabel,
  priorityTagType,
  statusLabel,
  statusTagType
} from '@/utils/constants'

const router = useRouter()
const userStore = useUserStore()

// 仅客服展示"公共池 / 我负责的"切换；管理员看全量，客户只看自己的
const showScopeSwitch = computed(() => userStore.permissions.showScope)
// 客服抢单 / 管理员接单（后端接口不同，按钮文案一致）
const canTakeTicket = computed(() => userStore.permissions.canGrab)

const filters = reactive({
  status: 'PENDING',
  priority: '',
  category: '',
  keyword: '',
  scope: ''
})

const pagination = reactive({ pageNum: 1, pageSize: 10, total: 0 })

const loading = ref(false)
const tickets = ref([])
// 行级 Loading：记录"正在接单"的工单 ID，只禁用被点击的那一行，其余行不受影响。
// 用响应式 Set 承载，天然支持同时接不同的工单（并发 + 去重）
const grabbingIds = reactive(new Set())

async function fetchTickets() {
  loading.value = true
  try {
    const params = { pageNum: pagination.pageNum, pageSize: pagination.pageSize }
    Object.entries(filters).forEach(([key, value]) => {
      const normalized = typeof value === 'string' ? value.trim() : value
      if (normalized) params[key] = normalized
    })

    const page = await getTicketPage(params)
    tickets.value = page?.records ?? []
    pagination.total = Number(page?.total ?? 0)
  } catch (error) {
    // 错误提示已由响应拦截器统一处理，这里保证表格回到干净状态
    tickets.value = []
    pagination.total = 0
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  pagination.pageNum = 1
  fetchTickets()
}

/** 关键词输入防抖（尾执行）：停止输入 400ms 后才请求，避免每敲一个字打一次接口 */
const handleKeywordInput = debounce(handleSearch, 400)

function handleReset() {
  filters.status = 'PENDING'
  filters.priority = ''
  filters.category = ''
  filters.keyword = ''
  filters.scope = ''
  handleSearch()
}

function handleSizeChange(size) {
  pagination.pageSize = size
  handleSearch()
}

function handlePageChange(pageNum) {
  pagination.pageNum = pageNum
  fetchTickets()
}

async function doGrab(row) {
  // 双保险之一：同一工单已在请求中，直接忽略后续点击
  if (grabbingIds.has(row.id)) return

  grabbingIds.add(row.id)
  try {
    if (userStore.role === 'AGENT') {
      await grabTicket(row.id)
    } else {
      await takeTicket(row.id)
    }
    ElMessage.success('接单成功，工单已分配给你')
  } catch (error) {
    // 失败原因（如"该工单已被他人接单"）由响应拦截器统一弹出后端 message，这里不重复提示
  } finally {
    grabbingIds.delete(row.id)
    // 无论成功还是被别人抢先，都刷新列表：公共池里不应再出现已被接走的工单
    fetchTickets()
  }
}

/**
 * 双保险之二：立即执行的防抖。
 * 首次点击马上发请求（无延迟感），300ms 内的连点直接丢弃，避免超量请求触发后端限流。
 */
const handleGrab = debounce(doGrab, 300, true)

function goDetail(row) {
  router.push({ name: 'ticket-detail', params: { id: row.id } })
}

onMounted(fetchTickets)
</script>

<template>
  <div>
    <el-card class="page-card" shadow="never">
      <div class="filter-bar">
        <el-select v-model="filters.status" placeholder="全部状态" clearable @change="handleSearch">
          <el-option v-for="item in STATUS_OPTIONS" :key="item.value" :label="item.label" :value="item.value" />
        </el-select>

        <el-select v-model="filters.priority" placeholder="全部优先级" clearable @change="handleSearch">
          <el-option v-for="item in PRIORITY_OPTIONS" :key="item.value" :label="item.label" :value="item.value" />
        </el-select>

        <el-select v-model="filters.category" placeholder="全部分类" clearable @change="handleSearch">
          <el-option v-for="item in CATEGORY_OPTIONS" :key="item.value" :label="item.label" :value="item.value" />
        </el-select>

        <el-input
          v-model="filters.keyword"
          placeholder="标题 / 描述关键词"
          clearable
          @input="handleKeywordInput"
          @clear="handleSearch"
        />

        <el-radio-group v-if="showScopeSwitch" v-model="filters.scope" @change="handleSearch">
          <el-radio-button :value="''">公共池</el-radio-button>
          <el-radio-button value="mine">我负责的</el-radio-button>
        </el-radio-group>

        <el-button type="primary" @click="handleSearch">查询</el-button>
        <el-button @click="handleReset">重置</el-button>
      </div>
    </el-card>

    <el-card shadow="never">
      <el-table v-loading="loading" :data="tickets" border stripe>
        <el-table-column prop="id" label="工单号" width="90" />

        <el-table-column prop="title" label="标题" min-width="200" show-overflow-tooltip />

        <el-table-column label="优先级" width="90">
          <template #default="{ row }">
            <el-tag :type="priorityTagType(row.priority)" effect="dark" size="small">
              {{ priorityLabel(row.priority) }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column label="分类" width="110">
          <template #default="{ row }">{{ categoryLabel(row.category) }}</template>
        </el-table-column>

        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)" size="small">{{ statusLabel(row.status) }}</el-tag>
          </template>
        </el-table-column>

        <el-table-column prop="customerName" label="提交人" width="120">
          <template #default="{ row }">{{ row.customerName || '-' }}</template>
        </el-table-column>

        <el-table-column prop="agentName" label="经办人" width="120">
          <template #default="{ row }">{{ row.agentName || '-' }}</template>
        </el-table-column>

        <el-table-column label="提交时间" width="180">
          <template #default="{ row }">{{ formatDateTime(row.createTime) }}</template>
        </el-table-column>

        <el-table-column label="操作" width="170" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="canTakeTicket && row.status === 'PENDING'"
              type="primary"
              size="small"
              :loading="grabbingIds.has(row.id)"
              @click="handleGrab(row)"
            >
              接单
            </el-button>
            <el-button type="info" size="small" plain @click="goDetail(row)">详情</el-button>
          </template>
        </el-table-column>

        <template #empty>
          <el-empty description="暂无工单" />
        </template>
      </el-table>

      <div class="pagination-bar">
        <el-pagination
          v-model:current-page="pagination.pageNum"
          :page-size="pagination.pageSize"
          :total="pagination.total"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next"
          @size-change="handleSizeChange"
          @current-change="handlePageChange"
        />
      </div>
    </el-card>
  </div>
</template>
