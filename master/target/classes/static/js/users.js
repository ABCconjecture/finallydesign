let currentPage = 0;
const pageSize = 15;
let totalUsers = 0;
let totalPages = 1;
let clusterOverviewCache = [];

function getFilters() {
    const clusterValue = document.getElementById('clusterInput')?.value || '';
    return {
        search: document.getElementById('searchInput')?.value.trim() || '',
        college: document.getElementById('collegeInput')?.value.trim() || '',
        major: document.getElementById('majorInput')?.value.trim() || '',
        clusterId: clusterValue === '' ? undefined : Number(clusterValue),
        status: document.getElementById('statusInput')?.value || ''
    };
}

document.addEventListener('DOMContentLoaded', async () => {
    try {
        await auth.requireLogin();
        await auth.renderSessionBar();
        await loadClusterOptions();
        bindFilterEvents();
        await loadUsers();
    } catch (error) {
        if (error.code === 401 || error.message === '请先登录') {
            auth.redirectToLogin('users.html');
            return;
        }
        console.error('初始化用户列表页面失败:', error);
        alert(error.message || '用户列表加载失败');
    }
});

function bindFilterEvents() {
    ['searchInput', 'collegeInput', 'majorInput'].forEach((id) => {
        const input = document.getElementById(id);
        if (input) {
            input.addEventListener('keydown', (event) => {
                if (event.key === 'Enter') {
                    searchUsers();
                }
            });
        }
    });

    ['clusterInput', 'statusInput'].forEach((id) => {
        const input = document.getElementById(id);
        if (input) {
            input.addEventListener('change', () => searchUsers());
        }
    });
}

async function loadClusterOptions() {
    const select = document.getElementById('clusterInput');
    if (!select) {
        return;
    }

    try {
        const selectedValue = select.value;
        clusterOverviewCache = await api.getClusterOverview();
        select.innerHTML = ['<option value="">全部群体</option>']
            .concat(clusterOverviewCache.map((item) => `
                <option value="${item.clusterId}">${escapeHtml(item.label || `群体 ${item.clusterId}`)}（${item.count || 0}人）</option>
            `))
            .join('');
        if (selectedValue) {
            select.value = selectedValue;
        }
    } catch (error) {
        console.error('加载群体选项失败:', error);
    }
}

async function loadUsers() {
    try {
        const filters = getFilters();
        const response = await api.getUsers({
            page: currentPage,
            size: pageSize,
            ...filters
        });
        const users = response.records || [];
        totalUsers = Number(response.total || 0);
        totalPages = Math.max(1, Math.ceil(totalUsers / pageSize));

        if (currentPage >= totalPages && totalUsers > 0) {
            currentPage = totalPages - 1;
            return loadUsers();
        }

        renderSummary(totalUsers, filters);
        renderTable(users);
        renderPager();
    } catch (error) {
        console.error('加载用户列表失败:', error);
        alert(error.message || '加载列表失败，请稍后重试');
    }
}

function renderSummary(total, filters) {
    const summary = document.getElementById('usersSummary');
    if (!summary) {
        return;
    }

    const descriptions = [];
    if (filters.search) descriptions.push(`关键词：${filters.search}`);
    if (filters.college) descriptions.push(`学院：${filters.college}`);
    if (filters.major) descriptions.push(`专业：${filters.major}`);
    if (filters.clusterId !== undefined) {
        const cluster = clusterOverviewCache.find((item) => Number(item.clusterId) === Number(filters.clusterId));
        descriptions.push(`群体：${cluster?.label || filters.clusterId}`);
    }
    if (filters.status) descriptions.push(`状态：${filters.status}`);

    summary.innerHTML = `
        <strong>当前结果：</strong> 共找到 <strong>${total}</strong> 名用户
        <span class="table-cell-sub">${descriptions.length ? `，筛选条件为 ${escapeHtml(descriptions.join(' / '))}` : '，当前为全量用户视图'}</span>
    `;
}

function renderTable(users) {
    const tbody = document.getElementById('usersTable');
    if (!tbody) {
        return;
    }

    if (!users.length) {
        tbody.innerHTML = '<tr><td colspan="8" class="table-empty-cell">当前筛选条件下没有匹配用户</td></tr>';
        return;
    }

    tbody.innerHTML = users.map((user) => {
        const healthState = resolveHealthState(user.healthScore);
        const reminderTag = buildHealthReminder(user.healthScore, user.warningCount);
        return `
            <tr>
                <td>
                    <div class="table-cell-main">${escapeHtml(user.studentId || '-')}</div>
                    <div class="table-cell-sub">${escapeHtml(user.name || '-')} / ${escapeHtml(user.gender || '未知')}</div>
                </td>
                <td>
                    <div class="table-cell-main">${escapeHtml(user.college || '-')}</div>
                    <div class="table-cell-sub">${escapeHtml(user.major || '-')}</div>
                </td>
                <td>
                    <span class="badge badge-info">${escapeHtml(user.clusterLabel || '未聚类')}</span>
                    <div class="table-cell-sub" title="${escapeHtml(user.clusterSummary || '')}">${escapeHtml(user.clusterSummary || '暂无群体说明')}</div>
                </td>
                <td>
                    <div class="table-cell-main"><span class="health-score ${healthState.className}">${formatHealth(user.healthScore)}</span></div>
                    <div class="table-cell-sub">${escapeHtml(user.healthLevel || '待分析')}</div>
                    ${reminderTag}
                </td>
                <td>
                    <div class="table-cell-main">${formatRisk(user.riskScore)}</div>
                    <div class="table-cell-sub">在线 ${formatNumber(user.avgOnlineHours, 2)}h / 学习 ${(Number(user.studyTrafficRatio || 0) * 100).toFixed(2)}%</div>
                </td>
                <td>
                    <span class="status-chip ${resolveStatusClass(user.status)}">${escapeHtml(user.status || '待分析')}</span>
                </td>
                <td>
                    <div class="table-cell-main">${user.warningCount || 0}</div>
                    <div class="table-cell-sub">${escapeHtml(user.topWarningType || '暂无开放预警')}</div>
                </td>
                <td>
                    <div class="table-actions">
                        <button class="btn btn-primary btn-small" onclick="viewDetail(${user.userId})">分析详情</button>
                        <button class="btn btn-warning btn-small" onclick="viewWarnings(${user.userId})">预警记录</button>
                        ${user.cluster != null ? `<button class="btn btn-info btn-small" onclick="viewCluster(${user.cluster})">群体详情</button>` : ''}
                    </div>
                </td>
            </tr>
        `;
    }).join('');
}

function buildHealthReminder(healthScore, warningCount) {
    const score = Number(healthScore);
    if (!Number.isFinite(score)) {
        return '<div class="table-cell-sub">待生成健康度分析</div>';
    }
    if (score < 60) {
        return `
            <div class="health-reminder-row">
                <span class="badge badge-danger">低健康提醒</span>
                <span class="table-cell-sub">${warningCount > 0 ? '建议优先查看预警记录。' : '建议优先查看分析详情并人工复核。'}</span>
            </div>
        `;
    }
    if (score < 75) {
        return `
            <div class="health-reminder-row">
                <span class="badge badge-warning">波动关注</span>
                <span class="table-cell-sub">健康度已进入波动区间，建议持续观察。</span>
            </div>
        `;
    }
    return '<div class="table-cell-sub">当前健康状态较稳定</div>';
}

function renderPager() {
    const pageInfo = document.getElementById('pageInfo');
    const prevButton = document.getElementById('usersPrevButton');
    const nextButton = document.getElementById('usersNextButton');

    if (pageInfo) {
        pageInfo.textContent = `第 ${currentPage + 1} 页 / 共 ${totalPages} 页，合计 ${totalUsers} 条`;
    }
    if (prevButton) {
        prevButton.disabled = currentPage <= 0;
    }
    if (nextButton) {
        nextButton.disabled = currentPage >= totalPages - 1 || totalUsers === 0;
    }
}

function searchUsers() {
    currentPage = 0;
    loadUsers();
}

function viewDetail(userId) {
    window.location.href = `analysis.html#user=${userId}`;
}

function viewWarnings(userId) {
    window.location.href = `warning.html#user=${userId}`;
}

function viewCluster(clusterId) {
    window.location.href = `profile.html#cluster=${clusterId}`;
}

function next() {
    if (currentPage < totalPages - 1) {
        currentPage += 1;
        loadUsers();
    }
}

function prev() {
    if (currentPage > 0) {
        currentPage -= 1;
        loadUsers();
    }
}

function jumpToPage() {
    const input = document.getElementById('jumpPageInput');
    if (!input) {
        return;
    }

    const pageNumber = Number(input.value);
    if (Number.isInteger(pageNumber) && pageNumber > 0 && pageNumber <= totalPages) {
        currentPage = pageNumber - 1;
        loadUsers();
    }
}

function resolveStatusClass(status) {
    switch (status) {
        case '重点预警':
            return 'danger';
        case '持续关注':
            return 'warning';
        case '轻度波动':
            return 'info';
        default:
            return 'success';
    }
}

function resolveHealthState(value) {
    const score = Number(value);
    if (!Number.isFinite(score)) {
        return { className: '' };
    }
    if (score < 60) {
        return { className: 'health-score-danger' };
    }
    if (score < 75) {
        return { className: 'health-score-warning' };
    }
    return { className: 'health-score-success' };
}

function formatHealth(value) {
    if (value === undefined || value === null) {
        return '--';
    }
    return Number(value).toFixed(7);
}

function formatRisk(value) {
    if (value === undefined || value === null) {
        return '--';
    }
    return Number(value).toFixed(2);
}

function formatNumber(value, digits) {
    const number = Number(value);
    return Number.isFinite(number) ? number.toFixed(digits) : '--';
}

function escapeHtml(value) {
    return String(value ?? '')
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
}
