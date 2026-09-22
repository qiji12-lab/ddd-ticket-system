# JWT 认证改造计划

## Context

当前系统使用 `X-User-Id` 请求头做身份识别（明文数字 ID），`RoleInterceptor` 据此查库鉴权。这种方式存在严重安全隐患：任何人可以伪造 User-Id 冒充他人。用户要求改造为 JWT Token 认证体系，涵盖：注册（支持 CUSTOMER/AGENT 两种角色）、登录颁发 JWT、Token 过期处理、防盗用机制。

## 改造范围

### 1. 添加 JWT 依赖（pom.xml）

添加 `io.jsonwebtoken:jjwt-api/jjwt-impl/jjwt-jackson` 0.12.6 版本。

### 2. JWT 配置（application.yml）

```yaml
jwt:
  secret: "TickeTide2026SecretKeyForJwtTokenGenerationAndValidation!!"
  expiration: 86400000  # 24小时（毫秒）
```

### 3. 新建 JwtUtil 工具类

路径：`com.ticketide.util.JwtUtil`

* `generateToken(Long userId, String username, String role)` → 生成 JWT

  * Header: `{alg: HS256, typ: JWT}`

  * Payload: `{userId, username, role, iat, exp}`

  * Signature: HMAC-SHA256(secret)

* `parseToken(String token)` → 解析并返回 Claims，验证签名+过期

* `getUserIdFromToken(String token)` → 提取 userId

* 无效/过期时抛 `JwtException`

### 4. 改造 RoleInterceptor

路径：`com.ticketide.interceptor.RoleInterceptor`

* 从 `Authorization: Bearer <token>` 提取 JWT（保留对 `X-User-Id` 的兼容用于过渡）

* 调 `JwtUtil.parseToken` 验证签名和过期

* 解析出 userId 后照旧查库校验角色

* 验证失败返回 401（含 `WWW-Authenticate: Bearer` 响应头）

* **防盗用**：将 token 的 userId 与请求头 X-User-Id（如有）比对，不一致则拒绝

### 5. 改造 RateLimitInterceptor

路径：`com.ticketide.interceptor.RateLimitInterceptor` 第 110 行

* 限流 key 从 JWT 中提取 userId（优先），降级为 IP

### 6. 改造 UserServiceImpl.login

路径：`com.ticketide.service.impl.UserServiceImpl` 第 63-88 行

* 登录成功后调 `JwtUtil.generateToken` 生成真实 JWT

* 替换现有 `"token_" + userId + "_" + timestamp` 伪 token

### 7. 改造 UserRegisterRequest

路径：`com.ticketide.dto.request.UserRegisterRequest`

* 将 role 正则从 `^(ADMIN|AGENT|CUSTOMER)$` 改为 `^(AGENT|CUSTOMER)$`

* 防止用户自行注册 ADMIN 角色（安全约束）

* 默认值仍为 CUSTOMER

### 8. 改造 Controller 层

涉及文件：

* `UserController.java` — `/me` 端点从 JWT 获取 userId

* `TicketController.java` — 所有 `@RequestHeader("X-User-Id")` 改为从 JWT 解析

* `CommentController.java` — 同上

**策略**：新建 `@CurrentUser` 注解 + `HandlerMethodArgumentResolver`，从 `HttpServletRequest` 中 JwtUtil 解析出的 userId 自动注入，替换所有 `@RequestHeader("X-User-Id") Long userId`。

### 9. 前端改造（app.js）

* 登录成功后将 `token` 存入 `localStorage`

* `apiCall()` 中 `Authorization: Bearer ${token}` 替换 `X-User-Id` 头

* Token 过期（401）时自动跳转登录页

* 刷新页面时从 `localStorage` 恢复登录态

### 10. Token 过期处理

* JWT 自身携带 `exp` 声明，解析时自动校验

* 前端收到 401 时清除 localStorage 并跳转登录页，提示"登录已过期"

* 不做 refresh token（轻量级系统，24 小时过期后重新登录即可）

### 11. 防盗用机制

| 机制        | 说明                                    |
| --------- | ------------------------------------- |
| HTTPS 传输  | 生产环境必须 HTTPS，Token 不被中间人截获            |
| 签名校验      | HMAC-SHA256 签名，篡改任意字节即失效              |
| 过期时间      | 24 小时自动失效                             |
| Bearer 前缀 | 标准 `Authorization: Bearer <token>` 格式 |
| 限流保护      | 登录接口已有 RateLimit（10次/60秒），防暴力破解       |

## 新增/修改文件清单

| 操作 | 文件                                                               |
| -- | ---------------------------------------------------------------- |
| 新建 | `util/JwtUtil.java`                                              |
| 新建 | `annotation/CurrentUser.java`                                    |
| 新建 | `config/CurrentUserArgumentResolver.java`（注册到 WebMvcConfig）      |
| 修改 | `pom.xml`（加 jjwt 依赖）                                             |
| 修改 | `application.yml`（加 jwt 配置）                                      |
| 修改 | `interceptor/RoleInterceptor.java`（JWT 解析替换 X-User-Id）           |
| 修改 | `interceptor/RateLimitInterceptor.java`（限流 key 从 JWT 提取）         |
| 修改 | `service/impl/UserServiceImpl.java`（login 生成真实 JWT）              |
| 修改 | `dto/request/UserRegisterRequest.java`（禁止注册 ADMIN）               |
| 修改 | `controller/UserController.java`（@CurrentUser 替换 @RequestHeader） |
| 修改 | `controller/TicketController.java`（同上）                           |
| 修改 | `controller/CommentController.java`（同上）                          |
| 修改 | `config/WebMvcConfig.java`（注册 ArgumentResolver）                  |
| 修改 | `static/js/app.js`（localStorage + Bearer Token + 401 处理）         |

## 验证步骤

1. `mvn clean compile` 确认编译通过
2. 启动应用，curl 测试：

   * 注册 CUSTOMER → 登录 → 获取 JWT → 携带 Token 访问 `/api/users/me` → 返回用户信息

   * 注册 AGENT → 同上

   * 尝试注册 ADMIN → 返回 400

   * 不带 Token 访问受保护接口 → 401

   * 篡改 Token → 401 签名错误
3. 浏览器验证：登录后刷新页面不丢失登录态，24h 后 Token 过期自动跳转登录

