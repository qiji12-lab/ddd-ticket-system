<script setup>
import { computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { useUserStore } from '@/stores/user'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

// 客户看的是自己的工单，客服/管理员看的是公共工单池，文案按角色区分
const poolMenuTitle = computed(() => (userStore.role === 'CUSTOMER' ? '我的工单' : '公共工单池'))

// 详情页属于工单池的下级页面，仍高亮工单池菜单
const activeMenu = computed(() => (route.name === 'ticket-detail' ? 'pool' : route.name))

async function handleLogout() {
  const confirmed = await ElMessageBox.confirm('确定退出登录吗？', '提示', {
    type: 'warning',
    confirmButtonText: '退出',
    cancelButtonText: '取消'
  })
    .then(() => true)
    .catch(() => false)

  if (!confirmed) return
  userStore.logout()
  router.replace({ name: 'login' })
}

onMounted(() => {
  // 本地缓存的用户信息可能已过期（角色被管理员修改），静默拉一次服务端数据校正；
  // 失败（如 Token 过期）交给响应拦截器统一处理，不阻塞页面渲染
  userStore.refreshCurrentUser().catch(() => {})
})
</script>

<template>
  <el-container class="layout">
    <el-header class="layout-header">
      <div class="layout-brand">TickeTide 工单管理系统</div>
      <div class="layout-user">
        <el-tag type="info" effect="plain">{{ userStore.roleDesc }}</el-tag>
        <span class="layout-username">{{ userStore.username }}</span>
        <el-button link type="primary" @click="handleLogout">退出登录</el-button>
      </div>
    </el-header>

    <el-container>
      <el-aside width="200px" class="layout-aside">
        <el-menu :default-active="activeMenu" router>
          <el-menu-item v-if="userStore.permissions.canViewPool" index="pool" :route="{ name: 'pool' }">
            <el-icon><Tickets /></el-icon>
            <span>{{ poolMenuTitle }}</span>
          </el-menu-item>
        </el-menu>
      </el-aside>

      <el-main class="layout-main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<style scoped>
.layout {
  height: 100%;
}

.layout-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background-color: #fff;
  border-bottom: 1px solid #e4e7ed;
}

.layout-brand {
  font-size: 18px;
  font-weight: 600;
}

.layout-user {
  display: flex;
  align-items: center;
  gap: 12px;
}

.layout-username {
  color: #606266;
}

.layout-aside {
  background-color: #fff;
  border-right: 1px solid #e4e7ed;
}

.layout-main {
  background-color: #f5f7fa;
}
</style>
