import request from './request'

/**
 * 登录
 * @param {{ username: string, password: string }} data
 * @returns {Promise<{ userId, username, role, roleDesc, email, token }>}
 */
export function login(data) {
  return request.post('/users/login', data)
}

/** 获取当前登录用户信息（Token 失效时会被拦截器统一处理） */
export function getCurrentUser() {
  return request.get('/users/me')
}
