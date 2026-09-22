<script setup>
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/stores/user'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const formRef = ref()
const loading = ref(false)

const form = reactive({
  username: '',
  password: ''
})

const rules = {
  username: [{ required: true, message: '请输入账号', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
}

// 演示账号（对应 init.sql 初始化数据，密码均为 123456），点击卡片一键填充便于验证三种角色
const demoAccounts = [
  { username: 'admin', roleDesc: '管理员' },
  { username: 'agent01', roleDesc: '客服' },
  { username: 'customer01', roleDesc: '客户' }
]

function fillDemoAccount(account) {
  form.username = account.username
  form.password = '123456'
}

async function handleLogin() {
  // 表单校验失败时 validate 会 reject，catch 成 false 后提前返回，避免发出无效请求
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  loading.value = true
  try {
    const data = await userStore.login({ ...form })
    ElMessage.success(`欢迎回来，${data.username}`)
    // 优先回到被守卫拦截前的页面，其次进入工单池
    router.replace(route.query.redirect || '/pool')
  } catch (error) {
    // 失败提示（如"用户名或密码错误"）已由 Axios 响应拦截器统一弹出，这里只负责收起 Loading
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <el-card class="login-card">
      <template #header>
        <div class="login-title">TickeTide 工单管理系统</div>
      </template>

      <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @keyup.enter="handleLogin">
        <el-form-item label="账号" prop="username">
          <el-input v-model.trim="form.username" placeholder="请输入账号" clearable />
        </el-form-item>

        <el-form-item label="密码" prop="password">
          <el-input v-model="form.password" type="password" placeholder="请输入密码" show-password />
        </el-form-item>

        <el-form-item>
          <el-button type="primary" class="login-submit" :loading="loading" @click="handleLogin">
            登 录
          </el-button>
        </el-form-item>
      </el-form>

      <el-divider>演示账号（密码均为 123456）</el-divider>
      <div class="demo-accounts">
        <el-tag
          v-for="account in demoAccounts"
          :key="account.username"
          class="demo-tag"
          effect="plain"
          @click="fillDemoAccount(account)"
        >
          {{ account.username }}（{{ account.roleDesc }}）
        </el-tag>
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.login-page {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
  background-color: #f0f2f5;
}

.login-card {
  width: 420px;
}

.login-title {
  font-size: 18px;
  font-weight: 600;
  text-align: center;
}

.login-submit {
  width: 100%;
}

.demo-accounts {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  justify-content: center;
}

.demo-tag {
  cursor: pointer;
}
</style>
