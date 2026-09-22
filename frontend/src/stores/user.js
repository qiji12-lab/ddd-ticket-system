import { defineStore } from 'pinia'
import { login as loginApi, getCurrentUser } from '@/api/user'
import { DEFAULT_PERMISSIONS, ROLE_MAP, ROLE_PERMISSIONS } from '@/utils/constants'
import { TOKEN_KEY, USER_KEY } from '@/api/request'

/**
 * 登录态集中管理：
 * - Token 与用户信息持久化到 LocalStorage，刷新页面不掉登录
 * - 权限矩阵通过 getter 暴露给页面，页面不直接读 role 判断权限
 */
export const useUserStore = defineStore('user', {
  state: () => ({
    token: localStorage.getItem(TOKEN_KEY) || '',
    userInfo: JSON.parse(localStorage.getItem(USER_KEY) || 'null')
  }),

  getters: {
    isLoggedIn: (state) => Boolean(state.token),
    role: (state) => state.userInfo?.role || '',
    username: (state) => state.userInfo?.username || '',
    roleDesc: (state) => state.userInfo?.roleDesc || ROLE_MAP[state.userInfo?.role] || '未知角色',
    /** 当前角色的功能权限，未知角色返回保守默认值（全部不可用） */
    permissions: (state) => ROLE_PERMISSIONS[state.userInfo?.role] || DEFAULT_PERMISSIONS
  },

  actions: {
    /** 账号密码登录：成功后保存 JWT Token 到 LocalStorage */
    async login(form) {
      const data = await loginApi(form)
      this.token = data.token
      this.userInfo = data
      localStorage.setItem(TOKEN_KEY, data.token)
      localStorage.setItem(USER_KEY, JSON.stringify(data))
      return data
    },

    /** 登出：清空内存与本地存储，Token 由前端丢弃（后端无状态 JWT） */
    logout() {
      this.token = ''
      this.userInfo = null
      localStorage.removeItem(TOKEN_KEY)
      localStorage.removeItem(USER_KEY)
    },

    /** 用服务端数据校正本地缓存的用户信息（角色变更、资料修改场景） */
    async refreshCurrentUser() {
      const data = await getCurrentUser()
      if (data) {
        this.userInfo = { ...this.userInfo, ...data }
        localStorage.setItem(USER_KEY, JSON.stringify(this.userInfo))
      }
      return this.userInfo
    }
  }
})
