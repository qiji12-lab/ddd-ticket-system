// TickeTide 前端应用
const API_BASE = '';
let currentPage = 1;
let currentPageSize = 10;

// 当前登录用户信息
let currentUser = null;

// 状态映射
const STATUS_MAP = {
    PENDING: { label: '待接单', class: 'bg-pending' },
    ASSIGNED: { label: '已分配', class: 'bg-assigned' },
    PROCESSING: { label: '处理中', class: 'bg-processing' },
    RESOLVED: { label: '已解决', class: 'bg-resolved' },
    PENDING_CONFIRM: { label: '待确认', class: 'bg-pending-confirm' },
    REOPENED: { label: '已重开', class: 'bg-reopened' },
    CLOSED: { label: '已关闭', class: 'bg-closed' },
    CANCELLED: { label: '已取消', class: 'bg-cancelled' }
};

const PRIORITY_MAP = {
    'HIGH': { label: '高', class: 'priority-high' },
    'MEDIUM': { label: '中', class: 'priority-medium' },
    'LOW': { label: '低', class: 'priority-low' }
};

/**
 * 角色功能权限矩阵 —— 前端可见性控制的唯一真相（最终安全边界由后端 @RequiresRole + 数据范围保证）
 * canCreate    : 是否可创建工单（导航入口）
 * showScope    : 工单列表是否显示"全部/我负责的"范围切换
 * dashboard    : 工作台统计口径 global / agent / customer
 */
const ROLE_PERMISSIONS = {
    ADMIN:    { canCreate: false, showScope: false, dashboard: 'global' },
    AGENT:    { canCreate: false, showScope: true,  dashboard: 'agent' },
    CUSTOMER: { canCreate: true,  showScope: false, dashboard: 'customer' }
};

function getPermissions() {
    return ROLE_PERMISSIONS[currentUser?.role] || { canCreate: false, showScope: false, dashboard: 'global' };
}

// 经办人列表缓存（仅 ADMIN 派单使用）
let agentListCache = null;

const ROLE_MAP = {
    'ADMIN': '管理员',
    'AGENT': '经办人',
    'CUSTOMER': '客户'
};

// API 调用封装
async function apiCall(url, options = {}) {
    const defaultOptions = {
        headers: {
            'Content-Type': 'application/json'
        }
    };

    // 携带 JWT Token（优先），兼容 X-User-Id（过渡期）
    const token = localStorage.getItem('jwt_token');
    if (token) {
        defaultOptions.headers['Authorization'] = 'Bearer ' + token;
    }
    if (currentUser) {
        defaultOptions.headers['X-User-Id'] = currentUser.userId;
    }

    const response = await fetch(API_BASE + url, {
        ...defaultOptions,
        ...options,
        headers: {
            ...defaultOptions.headers,
            ...options.headers
        }
    });

    // 401 处理：Token 过期或无效，清除登录态并跳转登录页
    if (response.status === 401) {
        localStorage.removeItem('jwt_token');
        localStorage.removeItem('current_user');
        currentUser = null;
        showLoginPage();
        throw new Error('登录已过期，请重新登录');
    }

    const data = await response.json();

    if (data.code !== 200 && data.code !== undefined) {
        throw new Error(data.message || '请求失败');
    }

    return data;
}

// Toast 通知
function showToast(message, type = 'info') {
    const toast = document.createElement('div');
    toast.className = `toast toast-${type}`;
    
    const icons = {
        success: 'bi-check-circle-fill',
        error: 'bi-x-circle-fill',
        warning: 'bi-exclamation-triangle-fill',
        info: 'bi-info-circle-fill'
    };
    
    toast.innerHTML = `<i class="bi ${icons[type]}"></i> ${message}`;
    document.getElementById('toastContainer').appendChild(toast);
    
    setTimeout(() => {
        toast.style.transition = 'opacity 0.3s';
        toast.style.opacity = '0';
        setTimeout(() => toast.remove(), 300);
    }, 3000);
}

// 填充测试账号
function fillLogin(username) {
    document.getElementById('loginUsername').value = username;
    document.getElementById('loginPassword').value = '123456';
}

// 登录/注册表单切换
function showRegisterForm() {
    document.getElementById('loginForm').classList.add('d-none');
    document.getElementById('showRegisterLink').closest('.text-center').classList.add('d-none');
    document.getElementById('loginTestAccounts').classList.add('d-none');
    document.getElementById('registerForm').classList.remove('d-none');
    document.getElementById('backToLoginWrap').classList.remove('d-none');
}

function showLoginForm() {
    document.getElementById('registerForm').classList.add('d-none');
    document.getElementById('backToLoginWrap').classList.add('d-none');
    document.getElementById('loginForm').classList.remove('d-none');
    document.getElementById('showRegisterLink').closest('.text-center').classList.remove('d-none');
    document.getElementById('loginTestAccounts').classList.remove('d-none');
}

document.getElementById('showRegisterLink').addEventListener('click', function(e) {
    e.preventDefault();
    showRegisterForm();
});

document.getElementById('showLoginLink').addEventListener('click', function(e) {
    e.preventDefault();
    showLoginForm();
});

// 注册
document.getElementById('registerForm').addEventListener('submit', async function(e) {
    e.preventDefault();

    const username = document.getElementById('regUsername').value.trim();
    const password = document.getElementById('regPassword').value;
    const email = document.getElementById('regEmail').value.trim();
    const role = document.querySelector('input[name="regRole"]:checked').value;

    try {
        await apiCall('/api/users/register', {
            method: 'POST',
            body: JSON.stringify({ username, password, email, role })
        });
        showToast('注册成功，请使用新账号登录', 'success');
        // 切换回登录表单并预填用户名
        showLoginForm();
        document.getElementById('loginUsername').value = username;
        document.getElementById('loginPassword').value = '';
        document.getElementById('loginPassword').focus();
    } catch (error) {
        showToast('注册失败: ' + error.message, 'error');
    }
});

// 登录
document.getElementById('loginForm').addEventListener('submit', async function(e) {
    e.preventDefault();
    
    const username = document.getElementById('loginUsername').value;
    const password = document.getElementById('loginPassword').value;

    if (!username || !password) {
        showToast('请输入用户名和密码', 'warning');
        return;
    }

    try {
        const data = await apiCall('/api/users/login', {
            method: 'POST',
            body: JSON.stringify({ username, password })
        });
        
        currentUser = data.data;
        // 持久化 JWT Token 和用户信息（刷新页面不丢失登录态）
        localStorage.setItem('jwt_token', currentUser.token);
        localStorage.setItem('current_user', JSON.stringify(currentUser));
        showToast(`欢迎回来，${currentUser.username}！角色：${ROLE_MAP[currentUser.role]}`, 'success');

        // 显示主应用
        document.getElementById('loginPage').classList.add('d-none');
        document.getElementById('mainApp').classList.remove('d-none');

        // 更新用户信息显示
        document.getElementById('currentUser').textContent = currentUser.username;
        document.getElementById('currentRole').textContent = ROLE_MAP[currentUser.role];

        // 更新欢迎横幅
        const hour = new Date().getHours();
        const greeting = hour < 6 ? '凌晨好' : hour < 12 ? '早上好' : hour < 14 ? '中午好' : hour < 18 ? '下午好' : '晚上好';
        document.getElementById('welcomeText').textContent = `${greeting}，${currentUser.username}`;
        document.getElementById('welcomeSubText').textContent =
            `${ROLE_MAP[currentUser.role]}工作台 · 在这里管理你的工单，跟踪每一个问题的处理进度`;

        // 根据角色显示/隐藏功能
        updateRolePermissions();

        // 加载工作台
        switchPage('dashboard');

    } catch (error) {
        showToast('登录失败: ' + error.message, 'error');
    }
});

// 退出登录
function logout() {
    currentUser = null;
    agentListCache = null;
    // 清除本地登录态
    localStorage.removeItem('jwt_token');
    localStorage.removeItem('current_user');
    document.getElementById('loginPage').classList.remove('d-none');
    document.getElementById('mainApp').classList.add('d-none');
    document.getElementById('loginForm').reset();
    // 重置筛选器，避免下个账号继承
    ['statusFilter', 'priorityFilter', 'scopeFilter', 'searchInput'].forEach(id => {
        const el = document.getElementById(id);
        if (el) el.value = '';
    });
    showToast('已退出登录', 'info');
}

// 显示登录页
function showLoginPage() {
    document.getElementById('loginPage').classList.remove('d-none');
    document.getElementById('mainApp').classList.add('d-none');
    document.getElementById('loginForm').reset();
}

// 进入主应用（恢复登录态时复用）
function enterWorkbench() {
    document.getElementById('loginPage').classList.add('d-none');
    document.getElementById('mainApp').classList.remove('d-none');
    document.getElementById('currentUser').textContent = currentUser.username;
    document.getElementById('currentRole').textContent = ROLE_MAP[currentUser.role];

    const hour = new Date().getHours();
    const greeting = hour < 6 ? '凌晨好' : hour < 12 ? '早上好' : hour < 14 ? '中午好' : hour < 18 ? '下午好' : '晚上好';
    document.getElementById('welcomeText').textContent = `${greeting}，${currentUser.username}`;
    document.getElementById('welcomeSubText').textContent =
        `${ROLE_MAP[currentUser.role]}工作台 · 在这里管理你的工单，跟踪每一个问题的处理进度`;

    updateRolePermissions();
    switchPage('dashboard');
}

// 页面加载时尝试恢复登录态（JWT 过期则由后续请求 401 处理）
function restoreSession() {
    const savedUser = localStorage.getItem('current_user');
    const token = localStorage.getItem('jwt_token');
    if (savedUser && token) {
        try {
            currentUser = JSON.parse(savedUser);
            enterWorkbench();
            return;
        } catch (e) {
            localStorage.removeItem('jwt_token');
            localStorage.removeItem('current_user');
        }
    }
    showLoginPage();
}

document.addEventListener('DOMContentLoaded', restoreSession);

// 根据角色更新功能可见性（统一由 ROLE_PERMISSIONS 驱动）
function updateRolePermissions() {
    const perm = getPermissions();

    // 创建工单入口：仅客户
    const display = perm.canCreate ? '' : 'none';
    document.getElementById('navCreate').style.display = display;
    document.getElementById('btnCreateTicket').style.display = display;

    // 工单列表"范围"切换：仅经办人
    const scopeCol = document.getElementById('scopeFilterCol');
    if (scopeCol) scopeCol.classList.toggle('d-none', !perm.showScope);

    // 管理员预加载经办人列表（派单下拉用）
    if (currentUser.role === 'ADMIN' && !agentListCache) {
        loadAgentList();
    }
}

// 加载经办人列表
async function loadAgentList() {
    try {
        const data = await apiCall('/api/users/role/AGENT');
        agentListCache = data.data || [];
    } catch (error) {
        agentListCache = [];
    }
}

// 页面切换
function switchPage(page) {
    document.querySelectorAll('.page').forEach(p => p.classList.add('d-none'));
    document.querySelectorAll('.nav-link').forEach(l => l.classList.remove('active'));
    
    const pageElement = document.getElementById(page);
    if (pageElement) {
        pageElement.classList.remove('d-none');
    }
    
    const navLink = document.querySelector(`[data-page="${page}"]`);
    if (navLink) {
        navLink.classList.add('active');
    }
    
    if (page === 'dashboard') {
        loadDashboard();
    } else if (page === 'tickets') {
        loadTickets();
    } else if (page === 'create') {
        document.getElementById('createForm').reset();
    }
}

// 加载工作台数据（统计口径随角色变化）
async function loadDashboard() {
    try {
        const data = await apiCall('/api/tickets?pageNum=1&pageSize=100');
        const records = data.data.records;
        const me = currentUser.userId;
        const inStatus = (t, statuses) => statuses.includes(t.status);

        // 各角色的四张卡片：[标签, 图标, 计数]
        let cards;
        if (currentUser.role === 'AGENT') {
            cards = [
                ['待抢单', 'bi-hand-index-thumb',
                    records.filter(t => t.status === 'PENDING').length],
                ['我处理中', 'bi-play-circle',
                    records.filter(t => t.agentId === me && inStatus(t, ['ASSIGNED', 'PROCESSING', 'REOPENED'])).length],
                ['待客户确认', 'bi-check2-square',
                    records.filter(t => t.agentId === me && inStatus(t, ['PENDING_CONFIRM', 'RESOLVED'])).length],
                ['我已完成', 'bi-x-circle',
                    records.filter(t => t.agentId === me && t.status === 'CLOSED').length]
            ];
        } else if (currentUser.role === 'CUSTOMER') {
            // 后端已强制只返回自己创建的工单
            cards = [
                ['待响应', 'bi-hourglass-split',
                    records.filter(t => inStatus(t, ['PENDING', 'ASSIGNED'])).length],
                ['处理中', 'bi-play-circle',
                    records.filter(t => inStatus(t, ['PROCESSING', 'REOPENED'])).length],
                ['待我确认', 'bi-check2-square',
                    records.filter(t => inStatus(t, ['PENDING_CONFIRM', 'RESOLVED'])).length],
                ['已关闭', 'bi-x-circle',
                    records.filter(t => t.status === 'CLOSED').length]
            ];
        } else {
            // ADMIN：全局口径
            cards = [
                ['待接单', 'bi-hourglass-split',
                    records.filter(t => t.status === 'PENDING').length],
                ['处理中', 'bi-play-circle',
                    records.filter(t => inStatus(t, ['ASSIGNED', 'PROCESSING'])).length],
                ['待确认', 'bi-check2-square',
                    records.filter(t => inStatus(t, ['PENDING_CONFIRM', 'RESOLVED'])).length],
                ['已关闭', 'bi-x-circle',
                    records.filter(t => t.status === 'CLOSED').length]
            ];
        }

        const labelIds = ['statLabel1', 'statLabel2', 'statLabel3', 'statLabel4'];
        const valueIds = ['statPending', 'statProcessing', 'statResolved', 'statClosed'];
        const iconIds = ['statIcon1', 'statIcon2', 'statIcon3', 'statIcon4'];
        cards.forEach((card, i) => {
            document.getElementById(labelIds[i]).textContent = card[0];
            document.getElementById(valueIds[i]).textContent = card[2];
            document.getElementById(iconIds[i]).className = card[1];
        });

        // 最近工单
        const recentTickets = records.slice(0, 5);
        const recentList = document.getElementById('recentTickets');
        recentList.innerHTML = recentTickets.map(ticket => `
            <div class="ticket-item" onclick="viewTicketDetail(${ticket.id})">
                <span class="ticket-id">#${ticket.id}</span>
                <span class="ticket-title">${escapeHtml(ticket.title)}</span>
                <span class="ticket-status-badge">
                    <span class="badge ${STATUS_MAP[ticket.status]?.class || 'bg-closed'}">
                        ${STATUS_MAP[ticket.status]?.label || ticket.status}
                    </span>
                </span>
                <span class="${PRIORITY_MAP[ticket.priority]?.class}">
                    ${PRIORITY_MAP[ticket.priority]?.label || ticket.priority}
                </span>
            </div>
        `).join('') || '<div class="empty-state"><i class="bi bi-inbox"></i>暂无工单</div>';

    } catch (error) {
        showToast('加载工作台数据失败: ' + error.message, 'error');
    }
}

// 加载工单列表
async function loadTickets() {
    try {
        const status = document.getElementById('statusFilter').value;
        const priority = document.getElementById('priorityFilter').value;
        const category = document.getElementById('categoryFilter').value;
        const startTime = document.getElementById('startTimeFilter').value;
        const endTime = document.getElementById('endTimeFilter').value;
        const keyword = document.getElementById('searchInput').value;
        const scopeEl = document.getElementById('scopeFilter');
        const scope = scopeEl && !scopeEl.closest('.d-none') ? scopeEl.value : '';
        currentPageSize = parseInt(document.getElementById('pageSize').value);

        let url = `/api/tickets?pageNum=${currentPage}&pageSize=${currentPageSize}`;
        if (status) url += `&status=${encodeURIComponent(status)}`;
        if (priority) url += `&priority=${encodeURIComponent(priority)}`;
        if (category) url += `&category=${encodeURIComponent(category)}`;
        if (startTime) url += `&startTime=${encodeURIComponent(startTime)}`;
        if (endTime) url += `&endTime=${encodeURIComponent(endTime)}`;
        if (keyword) url += `&keyword=${encodeURIComponent(keyword)}`;
        if (scope) url += `&scope=${encodeURIComponent(scope)}`;

        const data = await apiCall(url);
        const { records, total, current, pages } = data.data;

        // 客户视角隐藏"创建人"列（列表均为自己的工单）
        const isCustomerView = currentUser.role === 'CUSTOMER';
        document.getElementById('thCustomer').classList.toggle('d-none', isCustomerView);
        const colCount = isCustomerView ? 7 : 8;

        // 渲染表格
        const tbody = document.getElementById('ticketTable');
        tbody.innerHTML = records.map(ticket => `
            <tr onclick="viewTicketDetail(${ticket.id})">
                <td>#${ticket.id}</td>
                <td>${escapeHtml(ticket.title)}</td>
                <td>
                    <span class="badge ${STATUS_MAP[ticket.status]?.class || 'bg-closed'}">
                        ${STATUS_MAP[ticket.status]?.label || ticket.status}
                    </span>
                </td>
                <td class="${PRIORITY_MAP[ticket.priority]?.class}">
                    ${PRIORITY_MAP[ticket.priority]?.label || ticket.priority}
                </td>
                ${isCustomerView ? '' : `<td>${escapeHtml(ticket.customerName || ('用户' + ticket.customerId))}</td>`}
                <td>${ticket.agentId ? escapeHtml(ticket.agentName || ('用户' + ticket.agentId)) : '<span class="text-muted">未分配</span>'}</td>
                <td>${formatDate(ticket.updateTime)}</td>
                <td>
                    <button class="btn btn-sm btn-outline-primary" onclick="event.stopPropagation();viewTicketDetail(${ticket.id})">
                        <i class="bi bi-eye"></i> 详情
                    </button>
                </td>
            </tr>
        `).join('') || `<tr><td colspan="${colCount}"><div class="empty-state"><i class="bi bi-inbox"></i>暂无符合条件的工单</div></td></tr>`;

        // 分页信息
        document.getElementById('pageInfo').textContent = `共 ${total} 条记录，第 ${current} / ${pages} 页`;
        document.getElementById('prevPage').disabled = current <= 1;
        document.getElementById('nextPage').disabled = current >= pages;

    } catch (error) {
        showToast('加载工单列表失败: ' + error.message, 'error');
    }
}

// 查看工单详情
async function viewTicketDetail(id) {
    try {
        const data = await apiCall(`/api/tickets/${id}`);
        const ticket = data.data;
        const statusInfo = STATUS_MAP[ticket.status];
        const priorityInfo = PRIORITY_MAP[ticket.priority];

        // 可用操作 - 根据当前用户角色和工单状态
        const availableActions = getAvailableActions(ticket.status, currentUser.role, ticket);

        // 转交需要选择目标客服：客服角色首次进入详情时按需加载经办人列表
        if (availableActions.some(a => a.action === 'transfer') && !agentListCache) {
            await loadAgentList();
        }

        const actionButtons = availableActions.map(action => `
            <button class="btn ${action.style}" onclick="${action.handler}">
                <i class="${action.icon}"></i> ${action.label}
            </button>
        `).join('');

        // 满意度评价：已评价展示结果；已关闭且为工单创建人且未评价时展示评价入口
        const rating = ticket.rating;
        let ratingHtml;
        if (rating) {
            ratingHtml = `
                <div class="rating-result">
                    <div class="rating-stars">
                        ${'★'.repeat(rating.score)}${'☆'.repeat(5 - rating.score)}
                        <span class="text-muted ms-1">${rating.score} 分</span>
                    </div>
                    <div class="log-desc">${escapeHtml(rating.content || '客户未填写评价内容')}</div>
                    <div class="log-time">${formatDate(rating.createTime)}</div>
                </div>
            `;
        } else if (ticket.status === 'CLOSED' && currentUser.role === 'CUSTOMER' && ticket.customerId === currentUser.userId) {
            ratingHtml = `
                <div class="rating-form">
                    <div class="mb-2" id="ratingStars">
                        ${[1, 2, 3, 4, 5].map(n => `<i class="bi bi-star rating-star" data-score="${n}" onclick="selectRatingStar(${n})"></i>`).join('')}
                        <span class="text-muted ms-2">点击星星评分</span>
                    </div>
                    <textarea class="form-control mb-2" id="ratingContent" rows="2" maxlength="500" placeholder="说说这次服务的体验（选填）"></textarea>
                    <button class="btn btn-warning btn-sm" onclick="submitRating(${ticket.id})">
                        <i class="bi bi-star-fill"></i> 提交评价
                    </button>
                </div>
            `;
        } else {
            ratingHtml = '<div class="text-muted">暂无评价</div>';
        }

        // 操作日志时间线
        const logsHtml = (ticket.logs && ticket.logs.length > 0) ? `
            <div class="log-timeline">
                ${ticket.logs.map(log => `
                    <div class="log-item">
                        <div class="log-action">${log.actionDesc || log.action}</div>
                        <div class="log-time">${formatDate(log.createTime)}</div>
                        <div class="log-desc">${escapeHtml(log.remark || '')}</div>
                    </div>
                `).join('')}
            </div>
        ` : '<div class="text-muted">暂无操作日志</div>';

        // 负责人选择（派单时使用，经办人列表动态加载）
        let agentSelectHtml = '';
        if (availableActions.some(a => a.action === 'assign')) {
            const options = (agentListCache || []).map(a =>
                `<option value="${a.id}">${escapeHtml(a.username)}（${a.email || ''}）</option>`
            ).join('');
            agentSelectHtml = `
                <div class="mb-3">
                    <label class="form-label">选择经办人</label>
                    <select class="form-select" id="agentSelect">
                        ${options || '<option value="">暂无可用经办人</option>'}
                    </select>
                </div>
            `;
        }

        // 转交目标客服选择（排除自己）
        let transferSelectHtml = '';
        if (availableActions.some(a => a.action === 'transfer')) {
            const options = (agentListCache || [])
                .filter(a => a.id !== currentUser.userId)
                .map(a => `<option value="${a.id}">${escapeHtml(a.username)}（${a.email || ''}）</option>`)
                .join('');
            transferSelectHtml = `
                <div class="mb-3">
                    <label class="form-label">转交给（客服）</label>
                    <select class="form-select" id="transferAgentSelect">
                        ${options || '<option value="">暂无其他客服</option>'}
                    </select>
                    <input type="text" class="form-control mt-2" id="transferRemark" maxlength="200" placeholder="转交原因（选填）">
                </div>
            `;
        }

        document.getElementById('ticketDetailBody').innerHTML = `
            <div class="detail-section">
                <h6>基本信息</h6>
                <div class="detail-item">
                    <span class="detail-label">工单ID</span>
                    <span class="detail-value">#${ticket.id}</span>
                </div>
                <div class="detail-item">
                    <span class="detail-label">标题</span>
                    <span class="detail-value">${escapeHtml(ticket.title)}</span>
                </div>
                <div class="detail-item">
                    <span class="detail-label">描述</span>
                    <span class="detail-value">${escapeHtml(ticket.description || '-')}</span>
                </div>
                <div class="detail-item">
                    <span class="detail-label">状态</span>
                    <span class="detail-value">
                        <span class="badge ${statusInfo?.class || 'bg-secondary'}">${statusInfo?.label || ticket.status}</span>
                    </span>
                </div>
                <div class="detail-item">
                    <span class="detail-label">优先级</span>
                    <span class="detail-value ${priorityInfo?.class}">${priorityInfo?.label || ticket.priority}</span>
                </div>
                <div class="detail-item">
                    <span class="detail-label">问题分类</span>
                    <span class="detail-value">
                        <span class="badge bg-info">${ticket.categoryDesc || ticket.category || '-'}</span>
                    </span>
                </div>
                <div class="detail-item">
                    <span class="detail-label">创建人</span>
                    <span class="detail-value">${ticket.customerName || '用户' + ticket.customerId}</span>
                </div>
                <div class="detail-item">
                    <span class="detail-label">经办人</span>
                    <span class="detail-value">${ticket.agentName || (ticket.agentId ? '用户' + ticket.agentId : '未分配')}</span>
                </div>
                <div class="detail-item">
                    <span class="detail-label">创建时间</span>
                    <span class="detail-value">${formatDate(ticket.createTime)}</span>
                </div>
                <div class="detail-item">
                    <span class="detail-label">更新时间</span>
                    <span class="detail-value">${formatDate(ticket.updateTime)}</span>
                </div>
            </div>

            <div class="detail-section">
                <h6>操作</h6>
                ${agentSelectHtml}
                ${transferSelectHtml}
                <div class="action-buttons">
                    ${actionButtons || '<span class="text-muted">当前状态无可用操作</span>'}
                </div>
            </div>

            <div class="detail-section">
                <h6><i class="bi bi-paperclip"></i> 附件</h6>
                <div id="attachmentList" class="attachment-list">
                    <div class="text-muted">加载中...</div>
                </div>
                <div class="attachment-upload-area mt-3">
                    <input type="file" class="form-control form-control-sm" id="detailAttachmentInput" style="display:inline-block;width:auto;">
                    <button class="btn btn-sm btn-outline-primary" onclick="uploadAttachmentFromDetail(${ticket.id})">
                        <i class="bi bi-upload"></i> 上传
                    </button>
                </div>
            </div>

            <div class="detail-section">
                <h6>操作日志</h6>
                ${logsHtml}
            </div>

            <div class="detail-section">
                <h6><i class="bi bi-star"></i> 满意度评价</h6>
                ${ratingHtml}
            </div>

            <div class="detail-section">
                <h6><i class="bi bi-chat-dots"></i> 沟通记录</h6>
                <div class="comment-input-area mb-3">
                    <textarea class="form-control mb-2" id="commentInput" rows="2" placeholder="输入沟通内容..." maxlength="1000"></textarea>
                    <button class="btn btn-primary btn-sm" onclick="postComment(${ticket.id})">
                        <i class="bi bi-send"></i> 发送
                    </button>
                </div>
                <div id="commentList" class="comment-list">
                    <div class="text-muted">加载中...</div>
                </div>
            </div>
        `;

        const modalElement = document.getElementById('ticketDetailModal');
        const modal = bootstrap.Modal.getOrCreateInstance(modalElement);
        modal.show();

        // 保存当前工单ID
        window.currentTicketId = id;
        // 重置评分选择状态（重新渲染后星星默认未选中）
        selectedRatingScore = 0;

        // 加载评论
        loadComments(id);

        // 加载附件列表
        loadAttachments(id);

    } catch (error) {
        showToast('加载工单详情失败: ' + error.message, 'error');
    }
}

// 刷新工单详情（操作后调用，先关闭再重新打开）
async function refreshTicketDetail(id) {
    const modalElement = document.getElementById('ticketDetailModal');
    const modal = bootstrap.Modal.getInstance(modalElement);
    if (modal) {
        modal.hide();
        // 等待模态框关闭动画完成
        await new Promise(resolve => setTimeout(resolve, 400));
    }
    // 清除可能残留的 backdrop
    document.querySelectorAll('.modal-backdrop').forEach(el => el.remove());
    document.body.classList.remove('modal-open');
    document.body.style.removeProperty('overflow');
    document.body.style.removeProperty('padding-right');
    // 重新加载详情
    await viewTicketDetail(id);
}

// 获取可用操作 - 根据角色和状态
function getAvailableActions(currentStatus, role, ticket) {
    const actions = [];
    const ticketId = window.currentTicketId;
    const isAdmin = role === 'ADMIN';
    const isAgent = role === 'AGENT';
    const isCustomer = role === 'CUSTOMER';
    const isAssignedAgent = isAgent && ticket.agentId === currentUser.userId;
    const isOwner = isCustomer && ticket.customerId === currentUser.userId;

    switch (currentStatus) {
        case 'PENDING':
            if (isAdmin) {
                actions.push({
                    label: '派单',
                    icon: 'bi-person-plus',
                    style: 'btn-primary',
                    action: 'assign',
                    handler: `assignTicket(${ticketId})`
                });
                actions.push({
                    label: '直接处理',
                    icon: 'bi-play-circle',
                    style: 'btn-info',
                    action: 'take',
                    handler: `takeTicket(${ticketId})`
                });
            }
            if (isAgent) {
                actions.push({
                    label: '抢单',
                    icon: 'bi-hand-index-thumb',
                    style: 'btn-success',
                    action: 'grab',
                    handler: `grabTicket(${ticketId})`
                });
            }
            if (isAdmin || isOwner) {
                actions.push({
                    label: '取消工单',
                    icon: 'bi-x-octagon',
                    style: 'btn-outline-danger',
                    action: 'cancel',
                    handler: `cancelTicket(${ticketId})`
                });
            }
            break;
        case 'ASSIGNED':
            if (isAdmin) {
                actions.push({
                    label: '重新派单',
                    icon: 'bi-person-plus',
                    style: 'btn-primary',
                    action: 'assign',
                    handler: `assignTicket(${ticketId})`
                });
                actions.push({
                    label: '开始处理',
                    icon: 'bi-play-circle',
                    style: 'btn-info',
                    action: 'start',
                    handler: `startTicket(${ticketId})`
                });
            }
            if (isAssignedAgent) {
                actions.push({
                    label: '开始处理',
                    icon: 'bi-play-circle',
                    style: 'btn-primary',
                    action: 'start',
                    handler: `startTicket(${ticketId})`
                });
            }
            if (isAdmin || isAssignedAgent) {
                actions.push({
                    label: '转交',
                    icon: 'bi-arrow-left-right',
                    style: 'btn-outline-primary',
                    action: 'transfer',
                    handler: `transferTicket(${ticketId})`
                });
            }
            break;
        case 'PROCESSING':
            if (isAdmin || isAssignedAgent) {
                actions.push({
                    label: '标记已解决',
                    icon: 'bi-check-circle',
                    style: 'btn-success',
                    action: 'resolve',
                    handler: `resolveTicket(${ticketId})`
                });
                actions.push({
                    label: '转交',
                    icon: 'bi-arrow-left-right',
                    style: 'btn-outline-primary',
                    action: 'transfer',
                    handler: `transferTicket(${ticketId})`
                });
            }
            if (isAdmin || isOwner) {
                actions.push({
                    label: '取消工单',
                    icon: 'bi-x-octagon',
                    style: 'btn-outline-danger',
                    action: 'cancel',
                    handler: `cancelTicket(${ticketId})`
                });
            }
            break;
        case 'RESOLVED':
        case 'PENDING_CONFIRM':
            // 已解决/待确认：工单所有者（客户）或管理员可确认关闭、申请重开
            if (isAdmin || isOwner) {
                actions.push({
                    label: '确认关闭',
                    icon: 'bi-check2-circle',
                    style: 'btn-success',
                    action: 'close',
                    handler: `closeTicket(${ticketId})`
                });
                actions.push({
                    label: '不满意，重新打开',
                    icon: 'bi-arrow-counterclockwise',
                    style: 'btn-warning',
                    action: 'reopen',
                    handler: `reopenTicket(${ticketId})`
                });
            }
            break;
        case 'REOPENED':
            if (isAdmin) {
                actions.push({
                    label: '重新派单',
                    icon: 'bi-person-plus',
                    style: 'btn-primary',
                    action: 'assign',
                    handler: `assignTicket(${ticketId})`
                });
                actions.push({
                    label: '开始处理',
                    icon: 'bi-play-circle',
                    style: 'btn-info',
                    action: 'start',
                    handler: `startTicket(${ticketId})`
                });
            }
            if (isAssignedAgent) {
                actions.push({
                    label: '标记处理中',
                    icon: 'bi-play-circle',
                    style: 'btn-primary',
                    action: 'start',
                    handler: `startTicket(${ticketId})`
                });
            }
            if (isAdmin || isAssignedAgent) {
                actions.push({
                    label: '转交',
                    icon: 'bi-arrow-left-right',
                    style: 'btn-outline-primary',
                    action: 'transfer',
                    handler: `transferTicket(${ticketId})`
                });
            }
            break;
        case 'CLOSED':
            if (isAdmin) {
                actions.push({
                    label: '重新打开',
                    icon: 'bi-arrow-counterclockwise',
                    style: 'btn-warning',
                    action: 'reopen',
                    handler: `reopenTicket(${ticketId})`
                });
            }
            break;
    }

    // 升级：在办状态（已分配/处理中/已重开）且非高优先级时，经办人或管理员可升级并通知主管
    const isOperable = ['ASSIGNED', 'PROCESSING', 'REOPENED'].includes(currentStatus);
    if (isOperable && ticket.priority !== 'HIGH' && (isAdmin || isAssignedAgent)) {
        actions.push({
            label: '升级为高优先级',
            icon: 'bi-arrow-up-circle',
            style: 'btn-outline-warning',
            action: 'escalate',
            handler: `escalateTicket(${ticketId})`
        });
    }

    // 管理员在任意状态下均可删除工单
    if (isAdmin) {
        actions.push({
            label: '删除工单',
            icon: 'bi-trash3',
            style: 'btn-outline-danger',
            action: 'delete',
            handler: `deleteTicket(${ticketId})`
        });
    }

    return actions;
}

// 工单操作
async function assignTicket(id) {
    const assigneeId = document.getElementById('agentSelect').value;
    try {
        await apiCall(`/api/tickets/${id}/assign?assigneeId=${assigneeId}`, {
            method: 'POST'
        });
        showToast('派单成功', 'success');
        await refreshTicketDetail(id);
    } catch (error) {
        showToast('派单失败: ' + error.message, 'error');
    }
}

async function grabTicket(id) {
    try {
        await apiCall(`/api/tickets/${id}/grab`, { method: 'POST' });
        showToast('抢单成功', 'success');
        await refreshTicketDetail(id);
    } catch (error) {
        showToast('抢单失败: ' + error.message, 'error');
    }
}

async function takeTicket(id) {
    try {
        await apiCall(`/api/tickets/${id}/take`, { method: 'POST' });
        showToast('接单成功', 'success');
        await refreshTicketDetail(id);
    } catch (error) {
        showToast('接单失败: ' + error.message, 'error');
    }
}

async function resolveTicket(id) {
    try {
        await apiCall(`/api/tickets/${id}/resolve`, { method: 'POST' });
        showToast('已解决工单', 'success');
        await refreshTicketDetail(id);
    } catch (error) {
        showToast('操作失败: ' + error.message, 'error');
    }
}

async function closeTicket(id) {
    if (!confirm('确定要关闭这个工单吗？')) return;
    try {
        await apiCall(`/api/tickets/${id}/close`, { method: 'POST' });
        showToast('工单已关闭', 'success');
        await refreshTicketDetail(id);
    } catch (error) {
        showToast('操作失败: ' + error.message, 'error');
    }
}

async function reopenTicket(id) {
    try {
        await apiCall(`/api/tickets/${id}/reopen`, { method: 'POST' });
        showToast('工单已重新打开', 'success');
        await refreshTicketDetail(id);
    } catch (error) {
        showToast('操作失败: ' + error.message, 'error');
    }
}

async function startTicket(id) {
    try {
        await apiCall(`/api/tickets/${id}/start`, { method: 'POST' });
        showToast('已开始处理', 'success');
        await refreshTicketDetail(id);
    } catch (error) {
        showToast('操作失败: ' + error.message, 'error');
    }
}

// 取消工单（仅创建人本人或管理员）
async function cancelTicket(id) {
    if (!confirm('确定要取消这个工单吗？取消后不可恢复。')) return;
    try {
        await apiCall(`/api/tickets/${id}/cancel`, { method: 'POST' });
        showToast('工单已取消', 'success');
        await refreshTicketDetail(id);
    } catch (error) {
        showToast('取消失败: ' + error.message, 'error');
    }
}

// 转交工单给其他客服
async function transferTicket(id) {
    const selectEl = document.getElementById('transferAgentSelect');
    if (!selectEl || !selectEl.value) {
        showToast('请先选择要转交的客服', 'error');
        return;
    }
    const remark = (document.getElementById('transferRemark')?.value || '').trim();
    try {
        await apiCall(`/api/tickets/${id}/transfer`, {
            method: 'POST',
            body: JSON.stringify({ targetAgentId: Number(selectEl.value), remark })
        });
        showToast('工单已转交', 'success');
        await refreshTicketDetail(id);
    } catch (error) {
        showToast('转交失败: ' + error.message, 'error');
    }
}

// 升级为高优先级并通知主管
async function escalateTicket(id) {
    const remark = (prompt('请输入升级原因（选填）：') || '').trim();
    try {
        await apiCall(`/api/tickets/${id}/escalate`, {
            method: 'POST',
            body: JSON.stringify({ remark })
        });
        showToast('已升级为高优先级，并通知主管', 'success');
        await refreshTicketDetail(id);
    } catch (error) {
        showToast('升级失败: ' + error.message, 'error');
    }
}

// 满意度评价：当前选中的星级（重新渲染详情时重置为 0）
let selectedRatingScore = 0;

function selectRatingStar(score) {
    selectedRatingScore = score;
    document.querySelectorAll('#ratingStars .rating-star').forEach(el => {
        const star = Number(el.dataset.score);
        el.classList.toggle('bi-star-fill', star <= score);
        el.classList.toggle('bi-star', star > score);
    });
}

async function submitRating(ticketId) {
    if (!selectedRatingScore) {
        showToast('请先选择 1-5 星评分', 'error');
        return;
    }
    const content = (document.getElementById('ratingContent')?.value || '').trim();
    try {
        await apiCall(`/api/tickets/${ticketId}/rating`, {
            method: 'POST',
            body: JSON.stringify({ score: selectedRatingScore, content })
        });
        showToast('评价成功，感谢您的反馈', 'success');
        selectedRatingScore = 0;
        await refreshTicketDetail(ticketId);
    } catch (error) {
        showToast('评价失败: ' + error.message, 'error');
    }
}

// 评论/沟通记录
async function loadComments(ticketId) {
    const listEl = document.getElementById('commentList');
    if (!listEl) return;
    try {
        const data = await apiCall(`/api/comments?ticketId=${ticketId}`);
        const comments = data.data || [];
        if (comments.length === 0) {
            listEl.innerHTML = '<div class="text-muted"><i class="bi bi-inbox"></i> 暂无沟通记录</div>';
            return;
        }
        listEl.innerHTML = comments.map(c => {
            const roleLabel = ROLE_MAP[c.userRole] || c.userRole || '';
            const isMe = c.userId === currentUser.userId;
            const isAdmin = currentUser.role === 'ADMIN';
            const canDelete = isMe || isAdmin;
            return `
                <div class="comment-item">
                    <div class="comment-header">
                        <span class="comment-author">
                            <i class="bi bi-person-circle"></i>
                            ${escapeHtml(c.username || ('用户' + c.userId))}
                            <span class="badge bg-secondary ms-1">${roleLabel}</span>
                        </span>
                        <span class="comment-time">${formatDate(c.createTime)}</span>
                    </div>
                    <div class="comment-content">${escapeHtml(c.content)}</div>
                    ${canDelete ? `<button class="btn btn-sm btn-link text-danger p-0 mt-1" onclick="deleteComment(${c.id}, ${ticketId})"><i class="bi bi-trash3"></i> 删除</button>` : ''}
                </div>
            `;
        }).join('');
    } catch (error) {
        listEl.innerHTML = '<div class="text-danger">评论加载失败</div>';
    }
}

async function postComment(ticketId) {
    const input = document.getElementById('commentInput');
    const content = input.value.trim();
    if (!content) {
        showToast('请输入评论内容', 'warning');
        return;
    }
    try {
        await apiCall(`/api/comments?ticketId=${ticketId}`, {
            method: 'POST',
            body: JSON.stringify({ content })
        });
        input.value = '';
        showToast('评论已发送', 'success');
        await loadComments(ticketId);
    } catch (error) {
        showToast('评论失败: ' + error.message, 'error');
    }
}

async function deleteComment(commentId, ticketId) {
    if (!confirm('确认删除这条评论？')) return;
    try {
        await apiCall(`/api/comments/${commentId}`, { method: 'DELETE' });
        showToast('评论已删除', 'success');
        await loadComments(ticketId);
    } catch (error) {
        showToast('删除失败: ' + error.message, 'error');
    }
}

// 加载工单附件列表
async function loadAttachments(ticketId) {
    const listEl = document.getElementById('attachmentList');
    if (!listEl) return;
    try {
        const data = await apiCall(`/api/tickets/${ticketId}/attachments`);
        const attachments = data.data || [];
        if (attachments.length === 0) {
            listEl.innerHTML = '<div class="text-muted"><i class="bi bi-inbox"></i> 暂无附件</div>';
            return;
        }
        listEl.innerHTML = attachments.map(a => {
            const sizeText = a.fileSize > 1024 * 1024
                ? `${(a.fileSize / 1024 / 1024).toFixed(2)}MB`
                : `${(a.fileSize / 1024).toFixed(1)}KB`;
            const isMe = a.uploaderId === currentUser.userId;
            const canDelete = isMe || currentUser.role === 'ADMIN';
            return `
                <div class="attachment-item">
                    <i class="bi bi-file-earmark"></i>
                    <span class="attachment-name" title="${escapeHtml(a.fileName)}">${escapeHtml(a.fileName)}</span>
                    <span class="text-muted">${sizeText}</span>
                    <button class="btn btn-sm btn-link p-0" onclick="downloadAttachment(${ticketId}, ${a.id})">
                        <i class="bi bi-download"></i> 下载
                    </button>
                    ${canDelete ? `<button class="btn btn-sm btn-link text-danger p-0" onclick="deleteAttachment(${ticketId}, ${a.id})">
                        <i class="bi bi-trash3"></i> 删除
                    </button>` : ''}
                </div>
            `;
        }).join('');
    } catch (error) {
        listEl.innerHTML = '<div class="text-danger">附件加载失败</div>';
    }
}

// 从详情页面上传附件
async function uploadAttachmentFromDetail(ticketId) {
    const input = document.getElementById('detailAttachmentInput');
    if (!input || !input.files || input.files.length === 0) {
        showToast('请先选择文件', 'warning');
        return;
    }
    const file = input.files[0];
    try {
        await uploadAttachment(ticketId, file);
        showToast('附件上传成功', 'success');
        input.value = '';
        await loadAttachments(ticketId);
    } catch (error) {
        showToast('附件上传失败: ' + error.message, 'error');
    }
}

// 删除附件
async function deleteAttachment(ticketId, attachmentId) {
    if (!confirm('确认删除此附件？')) return;
    try {
        await apiCall(`/api/tickets/${ticketId}/attachments/${attachmentId}`, { method: 'DELETE' });
        showToast('附件已删除', 'success');
        await loadAttachments(ticketId);
    } catch (error) {
        showToast('删除失败: ' + error.message, 'error');
    }
}

// 下载附件（走浏览器原生下载，需要带 Authorization 头）
async function downloadAttachment(ticketId, attachmentId) {
    try {
        const token = localStorage.getItem('jwt_token');
        const headers = {};
        if (token) headers['Authorization'] = 'Bearer ' + token;

        const response = await fetch(`/api/tickets/${ticketId}/attachments/${attachmentId}/download`, { headers });
        if (response.status === 401) {
            localStorage.removeItem('jwt_token');
            localStorage.removeItem('current_user');
            currentUser = null;
            showLoginPage();
            showToast('登录已过期，请重新登录', 'warning');
            return;
        }
        if (!response.ok) {
            const errData = await response.json().catch(() => ({}));
            throw new Error(errData.message || '下载失败');
        }
        const blob = await response.blob();
        // 从 Content-Disposition 解析文件名
        const disposition = response.headers.get('Content-Disposition') || '';
        const match = disposition.match(/filename\*=UTF-8''([^;]+)/i);
        const fileName = match ? decodeURIComponent(match[1]) : 'attachment';
        const url = window.URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = fileName;
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
        window.URL.revokeObjectURL(url);
    } catch (error) {
        showToast('下载失败: ' + error.message, 'error');
    }
}

// 删除工单（仅管理员）
async function deleteTicket(id) {
    if (!confirm(`确认删除工单 #${id} 吗？删除后不可恢复。`)) {
        return;
    }
    try {
        await apiCall(`/api/tickets/${id}`, { method: 'DELETE' });
        showToast('工单已删除', 'success');
        // 关闭详情弹窗并清理遮罩
        const modal = bootstrap.Modal.getInstance(document.getElementById('ticketDetailModal'));
        if (modal) modal.hide();
        document.querySelectorAll('.modal-backdrop').forEach(el => el.remove());
        document.body.classList.remove('modal-open');
        document.body.style.removeProperty('overflow');
        document.body.style.removeProperty('padding-right');
        // 回到列表页刷新
        switchPage('tickets');
    } catch (error) {
        showToast('删除失败: ' + error.message, 'error');
    }
}

// 创建工单
document.getElementById('createForm').addEventListener('submit', async function(e) {
    e.preventDefault();

    const title = document.getElementById('title').value.trim();
    const description = document.getElementById('description').value.trim();
    const category = document.getElementById('category').value;
    const priority = document.getElementById('priority').value;
    const attachments = document.getElementById('attachments').files;

    if (!title || !category || !priority) {
        showToast('请填写必填项', 'warning');
        return;
    }

    const submitBtn = this.querySelector('button[type="submit"]');
    const originalText = submitBtn.innerHTML;
    submitBtn.disabled = true;

    try {
        submitBtn.innerHTML = '<i class="bi bi-hourglass"></i> 提交中...';

        // 1. 创建工单（JSON，含分类）
        const res = await apiCall('/api/tickets', {
            method: 'POST',
            body: JSON.stringify({ title, description, priority, category })
        });
        const ticketId = res.data.id;

        // 2. 上传附件（如选择）
        if (attachments && attachments.length > 0) {
            submitBtn.innerHTML = `<i class="bi bi-cloud-upload"></i> 上传附件 0/${attachments.length}...`;
            let success = 0;
            for (let i = 0; i < attachments.length; i++) {
                submitBtn.innerHTML = `<i class="bi bi-cloud-upload"></i> 上传附件 ${i}/${attachments.length}...`;
                try {
                    await uploadAttachment(ticketId, attachments[i]);
                    success++;
                } catch (err) {
                    console.error('附件上传失败:', err);
                    showToast(`附件 "${attachments[i].name}" 上传失败: ${err.message}`, 'warning');
                }
            }
            if (success > 0) {
                showToast(`工单创建成功，${success}/${attachments.length} 个附件已上传`, 'success');
            } else {
                showToast('工单创建成功（附件上传失败）', 'success');
            }
        } else {
            showToast('工单创建成功', 'success');
        }

        // 重置表单
        document.getElementById('createForm').reset();
        document.getElementById('attachmentPreview').innerHTML = '';
        switchPage('tickets');
    } catch (error) {
        showToast('创建工单失败: ' + error.message, 'error');
    } finally {
        submitBtn.disabled = false;
        submitBtn.innerHTML = originalText;
    }
});

// 上传附件到指定工单（FormData，绕过 apiCall 的 JSON 默认头）
async function uploadAttachment(ticketId, file) {
    const formData = new FormData();
    formData.append('file', file);

    const token = localStorage.getItem('jwt_token');
    const headers = {};
    if (token) headers['Authorization'] = 'Bearer ' + token;

    const response = await fetch(`/api/tickets/${ticketId}/attachments`, {
        method: 'POST',
        headers,
        body: formData
    });

    if (response.status === 401) {
        localStorage.removeItem('jwt_token');
        localStorage.removeItem('current_user');
        currentUser = null;
        showLoginPage();
        throw new Error('登录已过期，请重新登录');
    }

    const data = await response.json();
    if (data.code !== 200 && data.code !== undefined) {
        throw new Error(data.message || '附件上传失败');
    }
    return data;
}

// 附件选择后预览
document.getElementById('attachments').addEventListener('change', function() {
    const preview = document.getElementById('attachmentPreview');
    const files = this.files;
    if (!files || files.length === 0) {
        preview.innerHTML = '';
        return;
    }
    preview.innerHTML = Array.from(files).map((f, i) => {
        const size = (f.size / 1024).toFixed(1);
        const sizeText = f.size > 1024 * 1024 ? `${(f.size / 1024 / 1024).toFixed(2)}MB` : `${size}KB`;
        return `<div class="attachment-chip"><i class="bi bi-file-earmark"></i> ${escapeHtml(f.name)} <span class="text-muted">${sizeText}</span></div>`;
    }).join('');
});

// 导航点击
document.querySelectorAll('.nav-link[data-page]').forEach(link => {
    link.addEventListener('click', (e) => {
        e.preventDefault();
        switchPage(link.dataset.page);
    });
});

// 分页按钮
document.getElementById('prevPage').addEventListener('click', () => {
    if (currentPage > 1) {
        currentPage--;
        loadTickets();
    }
});

document.getElementById('nextPage').addEventListener('click', () => {
    currentPage++;
    loadTickets();
});

// 筛选
function searchTickets() {
    currentPage = 1;
    loadTickets();
}

// 重置全部筛选条件
function resetFilters() {
    ['searchInput', 'statusFilter', 'priorityFilter', 'categoryFilter', 'startTimeFilter', 'endTimeFilter']
        .forEach(id => {
            const el = document.getElementById(id);
            if (el) el.value = '';
        });
    const scopeEl = document.getElementById('scopeFilter');
    if (scopeEl) scopeEl.value = '';
    currentPage = 1;
    loadTickets();
}

document.getElementById('searchInput').addEventListener('keyup', (e) => {
    if (e.key === 'Enter') {
        currentPage = 1;
        loadTickets();
    }
});

// 范围/状态/优先级切换立即重新查询
['scopeFilter', 'statusFilter', 'priorityFilter'].forEach(elId => {
    document.getElementById(elId).addEventListener('change', () => {
        currentPage = 1;
        loadTickets();
    });
});

document.getElementById('pageSize').addEventListener('change', () => {
    currentPage = 1;
    loadTickets();
});

// 工具函数
function formatDate(dateStr) {
    if (!dateStr) return '-';
    const date = new Date(dateStr);
    const pad = (n) => n.toString().padStart(2, '0');
    return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

function escapeHtml(text) {
    const div = document.createElement('div');
    div.textContent = text || '';
    return div.innerHTML;
}

// 初始化 - 默认显示登录页
document.addEventListener('DOMContentLoaded', () => {
    document.getElementById('loginPage').classList.remove('d-none');
    document.getElementById('mainApp').classList.add('d-none');
    
    // 模态框关闭时清理残留元素
    const modalElement = document.getElementById('ticketDetailModal');
    if (modalElement) {
        modalElement.addEventListener('hidden.bs.modal', function () {
            document.querySelectorAll('.modal-backdrop').forEach(el => el.remove());
            document.body.classList.remove('modal-open');
            document.body.style.removeProperty('overflow');
            document.body.style.removeProperty('padding-right');
        });
    }
});
