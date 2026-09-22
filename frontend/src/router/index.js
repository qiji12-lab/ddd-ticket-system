import { createRouter, createWebHistory } from 'vue-router'
import { useUserStore } from '@/stores/user'

const routes = [
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/LoginView.vue'),
    meta: { title: '登录', public: true }
  },
  {
    path: '/',
    component: () => import('@/layouts/BasicLayout.vue'),
    redirect: '/pool',
    children: [
      {
        path: 'pool',
        name: 'pool',
        component: () => import('@/views/TicketPoolView.vue'),
        meta: { title: '公共工单池' }
      },
      {
        path: 'tickets/:id',
        name: 'ticket-detail',
        component: () => import('@/views/TicketDetailView.vue'),
        meta: { title: '工单详情' }
      }
    ]
  },
  { path: '/:pathMatch(.*)*', redirect: '/pool' }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

/**
 * 全局前置守卫：
 * - 未登录访问受保护页面 → 跳登录页，并把原地址放进 query.redirect，登录后原路返回
 * - 已登录再访问登录页 → 直接回工单池，避免重复登录
 */
router.beforeEach((to) => {
  const userStore = useUserStore()

  if (!to.meta.public && !userStore.isLoggedIn) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }

  if (to.name === 'login' && userStore.isLoggedIn) {
    return { path: '/pool' }
  }

  document.title = to.meta.title ? `${to.meta.title} - TickeTide` : 'TickeTide 工单管理系统'
  return true
})

export default router
