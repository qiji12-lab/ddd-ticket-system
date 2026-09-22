import axios from 'axios'
import { ElMessage } from 'element-plus'

export const TOKEN_KEY = 'jwt_token'
export const USER_KEY = 'ticketide_user'

const service = axios.create({
  baseURL: '/api',
  timeout: 15000,
  headers: { 'Content-Type': 'application/json' }
})

/* ------------------------------------------------------------------ *
 * 请求拦截器：统一携带 JWT
 * 调用方无需关心 Token，业务代码里只写 request.get('/tickets') 即可
 * ------------------------------------------------------------------ */
service.interceptors.request.use(
  (config) => {
    const token = localStorage.getItem(TOKEN_KEY)
    if (token) {
      config.headers.Authorization = `Bearer ${token}`
    }
    return config
  },
  (error) => Promise.reject(error)
)

// 401 并发去重：页面同时发出多个请求且 Token 过期时，只提示一次、只跳转一次
let handlingUnauthorized = false

function handleUnauthorized(message = '登录已过期，请重新登录') {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(USER_KEY)
  if (handlingUnauthorized) return
  handlingUnauthorized = true
  ElMessage.error(message)

  // 动态 import router：避免 request → router → store → api → request 的循环依赖
  import('@/router').then(({ default: router }) => {
    const current = router.currentRoute.value
    const redirect = current.name === 'login' ? undefined : current.fullPath
    router
      .replace({ name: 'login', query: redirect ? { redirect } : {} })
      .finally(() => {
        handlingUnauthorized = false
      })
  })
}

/**
 * 统一失败处理：把后端 message 转成 Error 抛出，由拦截器统一提示。
 */
function resolveErrorMessage(error) {
  const { response, code } = error
  if (!response) {
    return code === 'ECONNABORTED' ? '请求超时，请稍后重试' : '网络异常，请检查网络连接'
  }
  // 后端业务异常（BusinessException）返回 HTTP 4xx + { code, message }，优先展示 message
  return response.data?.message || `请求失败（HTTP ${response.status}）`
}

/* ------------------------------------------------------------------ *
 * 响应拦截器：统一拆包后端 { code, message, data }
 * - code === 200：直接 resolve(data)，业务代码不用再写一层 res.data.data
 * - code === 400：ElMessage.error(message) + reject（如"该工单已被他人接单"）
 * - code === 401：清登录态并跳转登录页
 * ------------------------------------------------------------------ */
service.interceptors.response.use(
  (response) => {
    const res = response.data

    if (res.code === 200) {
      return res.data
    }

    const message = res.message || '请求失败'
    if (res.code === 401) {
      handleUnauthorized(message)
    } else {
      ElMessage.error(message)
    }
    return Promise.reject(new Error(message))
  },
  (error) => {
    if (error.response?.status === 401) {
      handleUnauthorized()
      return Promise.reject(new Error('登录已过期，请重新登录'))
    }

    const message = resolveErrorMessage(error)
    ElMessage.error(message)
    return Promise.reject(new Error(message))
  }
)

export default service
