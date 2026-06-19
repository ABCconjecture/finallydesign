const API_BASE = '/api/campus';
const AUTH_BASE = '/api/auth';

function buildQuery(params = {}) {
    const searchParams = new URLSearchParams();
    Object.entries(params).forEach(([key, value]) => {
        if (value !== undefined && value !== null && value !== '') {
            searchParams.set(key, value);
        }
    });
    const query = searchParams.toString();
    return query ? `?${query}` : '';
}

async function request(url, options = {}, base = API_BASE) {
    const headers = {
        'Content-Type': 'application/json',
        ...(options.headers || {})
    };

    const response = await fetch(base + url, {
        credentials: 'same-origin',
        ...options,
        headers
    });

    let json;
    try {
        json = await response.json();
    } catch (error) {
        const parseError = new Error('服务器返回的数据格式无法解析');
        parseError.code = response.status || 500;
        throw parseError;
    }

    if (json.code === 200) {
        return json.data !== undefined ? json.data : json;
    }

    const requestError = new Error(json.message || '请求失败');
    requestError.code = json.code || response.status || 500;
    throw requestError;
}

const api = {
    request,
    getStats: () => request('/stats'),
    getTrend: () => request('/trend'),
    getLowHealthFocus: (params = {}) => request(`/attention/low-health${buildQuery({
        size: params.size ?? 6
    })}`),
    getUsers: (params = {}) => request(`/users${buildQuery({
        page: params.page ?? 0,
        size: params.size ?? 50,
        search: params.search,
        college: params.college,
        major: params.major,
        clusterId: params.clusterId,
        status: params.status
    })}`),
    getProfile: (keyword) => request(`/users/${encodeURIComponent(keyword)}/profile`),
    getAnalysis: (userId) => request(`/analysis/${userId}`),
    getAnalysisInsight: (userId) => request(`/analysis/${userId}/insight`),
    getAnalysisHistory: (userId) => request(`/analysis/${userId}/history`),
    getUserWarnings: (userId) => request(`/warning${buildQuery({ userId })}`),
    getWarningPage: (params = {}) => request(`/warning/page${buildQuery({
        page: params.page ?? 0,
        size: params.size ?? 10,
        userId: params.userId
    })}`),
    getWarningStats: () => request('/warning/stats'),
    getWarningDashboard: () => request('/warning/dashboard'),
    getHighRiskUsers: () => request('/warning/high-risk'),
    getHighRiskUsersPage: (params = {}) => request(`/warning/high-risk/page${buildQuery({
        page: params.page ?? 0,
        size: params.size ?? 8
    })}`),
    getLowHealthUnwarned: (params = {}) => request(`/warning/low-health-unwarned${buildQuery({
        size: params.size ?? 6
    })}`),
    getClusterCounts: () => request('/cluster/counts'),
    getClusterOverview: () => request('/cluster/overview'),
    getClusterUsers: (clusterId) => request(`/cluster/${clusterId}/users`),
    getClusterUsersPage: (clusterId, params = {}) => request(`/cluster/${clusterId}/users/page${buildQuery({
        page: params.page ?? 0,
        size: params.size ?? 10
    })}`),
    getClusterInsight: (clusterId) => request(`/cluster/${clusterId}/insight`),
    getTaskStatus: () => request('/tasks/status'),
    getManualTaskStatus: () => request('/manual-tasks/status'),
    getTaskLogs: (taskKey, params = {}) => request(`/tasks/${encodeURIComponent(taskKey)}/logs${buildQuery({
        page: params.page ?? 0,
        size: params.size ?? 5
    })}`),
    getTaskAudits: (taskKey, params = {}) => request(`/tasks/${encodeURIComponent(taskKey)}/audits${buildQuery({
        page: params.page ?? 0,
        size: params.size ?? 5,
        action: params.action,
        operator: params.operator,
        result: params.result,
        startTime: params.startTime,
        endTime: params.endTime
    })}`),
    triggerTask: (taskKey) => request(`/tasks/${encodeURIComponent(taskKey)}/trigger`, { method: 'POST' }),
    retryTask: (taskKey, payload = {}) => request(`/tasks/${encodeURIComponent(taskKey)}/retry`, {
        method: 'POST',
        body: JSON.stringify(payload || {})
    }),
    pauseTask: (taskKey) => request(`/tasks/${encodeURIComponent(taskKey)}/pause`, { method: 'POST' }),
    resumeTask: (taskKey) => request(`/tasks/${encodeURIComponent(taskKey)}/resume`, { method: 'POST' }),
    copyTaskLatestFailure: (taskKey) => request(`/tasks/${encodeURIComponent(taskKey)}/copy-latest-failure`, { method: 'POST' }),
    copyTaskAuditDetail: (taskKey, auditId) => request(`/tasks/${encodeURIComponent(taskKey)}/audits/${auditId}/copy-detail`, { method: 'POST' }),
    triggerAnalysisUpdate: () => request('/analysis/trigger-all', { method: 'POST' }),
    triggerUserAnalysis: (userId) => request(`/analysis/${userId}/trigger`, { method: 'POST' }),
    triggerProfileUpdate: () => request('/cluster/trigger', { method: 'POST' }),
    triggerWarningCheck: () => request('/warning/trigger', { method: 'POST' }),
    handleWarning: (warningId, payload = {}) => request(`/warning/${warningId}/handle`, {
        method: 'POST',
        body: JSON.stringify(payload)
    }),
    login: (payload) => request('/login', {
        method: 'POST',
        body: JSON.stringify(payload || {})
    }, AUTH_BASE),
    logout: () => request('/logout', { method: 'POST' }, AUTH_BASE),
    getCurrentUser: () => request('/me', {}, AUTH_BASE),
    updateCurrentProfile: (payload) => request('/me/profile', {
        method: 'PUT',
        body: JSON.stringify(payload || {})
    }, AUTH_BASE),
    changeCurrentPassword: (payload) => request('/me/password', {
        method: 'POST',
        body: JSON.stringify(payload || {})
    }, AUTH_BASE),
    getAccountUsers: () => request('/users', {}, AUTH_BASE),
    createAccountUser: (payload) => request('/users', {
        method: 'POST',
        body: JSON.stringify(payload || {})
    }, AUTH_BASE),
    updateAccountStatus: (userId, payload) => request(`/users/${userId}/status`, {
        method: 'PATCH',
        body: JSON.stringify(payload || {})
    }, AUTH_BASE),
    updateAccountRole: (userId, payload) => request(`/users/${userId}/role`, {
        method: 'PATCH',
        body: JSON.stringify(payload || {})
    }, AUTH_BASE),
    resetAccountPassword: (userId, payload) => request(`/users/${userId}/password/reset`, {
        method: 'POST',
        body: JSON.stringify(payload || {})
    }, AUTH_BASE)
};
