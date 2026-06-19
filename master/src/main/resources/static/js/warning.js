const warningPageState = {
    page: 0,
    size: 10,
    total: 0,
    totalPages: 1
};

const highRiskPageState = {
    page: 0,
    size: 8,
    total: 0,
    totalPages: 1
};

let currentWarningUserId = null;
let warningTypeChart = null;
let warningLevelChart = null;
let warningFunnelChart = null;
let chartResizeBound = false;
const WARNING_PALETTE = {
    primary: '#c96a4a',
    accent: '#748c5f',
    secondary: '#8d7360',
    warning: '#cb9543',
    danger: '#b65d4c',
    sand: '#d8b999'
};

function readWarningUserFilter() {
    const hash = window.location.hash.replace(/^#/, '');
    const params = new URLSearchParams(hash);
    const user = params.get('user');
    if (!user) {
        return null;
    }
    const userId = Number(user);
    return Number.isFinite(userId) ? userId : null;
}

document.addEventListener('DOMContentLoaded', async () => {
    try {
        await auth.requireLogin();
        currentWarningUserId = readWarningUserFilter();
        await refreshPageData();
        window.addEventListener('hashchange', async () => {
            currentWarningUserId = readWarningUserFilter();
            warningPageState.page = 0;
            await refreshPageData();
        });
    } catch (error) {
        if (error.code === 401 || error.message === '请先登录') {
            auth.redirectToLogin('warning.html');
            return;
        }
        console.error('初始化预警页面失败:', error);
        alert(error.message || '预警页面加载失败');
    }
});

async function refreshPageData() {
    try {
        await auth.renderSessionBar();
        await Promise.all([
            loadWarningStats(),
            loadWarningDashboard(),
            loadLowHealthUnwarned(),
            loadHighRiskUsers(),
            loadUnhandledWarnings(),
            updateWarningPermissionHint(),
            updateFilterBanner()
        ]);
    } catch (error) {
        console.error('加载预警页面失败:', error);
    }
}

async function updateWarningPermissionHint() {
    const hint = document.getElementById('warningActionHint');
    if (!hint) {
        return;
    }

    const currentUser = await auth.getCurrentUser();
    if (!currentUser) {
        hint.textContent = '当前未登录，无法查看预警数据。';
        return;
    }

    hint.textContent = auth.isAdmin(currentUser)
        ? '当前为管理员账号，可处理预警、查看高风险分页、规则命中排行与低健康待复核对象。'
        : '当前为查看员账号，仅可浏览预警统计、风险分布、分页列表和详情说明。';
}

async function updateFilterBanner() {
    const banner = document.getElementById('warningFilterBanner');
    if (!banner) {
        return;
    }

    if (!currentWarningUserId) {
        banner.innerHTML = '当前展示全部开放预警记录。';
        return;
    }

    try {
        const profileData = await api.getProfile(currentWarningUserId);
        const user = profileData.user || {};
        banner.innerHTML = `
            <strong>当前筛选：</strong>仅展示 ${escapeHtml(user.name || '-')}（${escapeHtml(user.studentId || String(currentWarningUserId))}）的开放预警
            <button type="button" class="btn btn-small btn-info" style="margin-left: 12px;" onclick="clearWarningFilter()">查看全部</button>
        `;
    } catch (error) {
        banner.innerHTML = `
            <strong>当前筛选：</strong>仅展示用户 ${currentWarningUserId} 的开放预警
            <button type="button" class="btn btn-small btn-info" style="margin-left: 12px;" onclick="clearWarningFilter()">查看全部</button>
        `;
    }
}

async function loadWarningStats() {
    try {
        const [stats, typeStats] = await Promise.all([
            api.getStats(),
            api.getWarningStats()
        ]);

        const summary = typeStats.summary || {};
        setText('unhandledCount', stats.unhandledWarnings || 0);
        setText('highRiskUserCount', stats.highRiskUsers || 0);
        setText('unwarnedUserCount', summary.unwarnedUsers || 0);
        setText('highestRiskLevel', `最高风险等级：${stats.maxWarningLevel || 'NONE'}`);
        setText('warnedUsersCount', `已预警用户 ${summary.warnedUsers || 0} 人`);
        setText('warningCoverageText', `覆盖率 ${Number(summary.coverage || 0).toFixed(2)}%`);
        renderWarningTypeChart(typeStats);
        renderWarningTypeSummary(typeStats.distribution || []);
    } catch (error) {
        console.error('加载预警统计失败:', error);
    }
}

async function loadWarningDashboard() {
    try {
        const dashboard = await api.getWarningDashboard();
        renderWarningFunnel(dashboard.funnel || []);
        renderWarningLevelDistribution(dashboard.levelDistribution || []);
        renderWarningRuleRanking(dashboard.ruleRanking || []);
        renderLowHealthReviewSummary(dashboard.lowHealthReview || {});
    } catch (error) {
        console.error('加载预警仪表盘数据失败:', error);
    }
}

async function loadLowHealthUnwarned() {
    try {
        const users = await api.getLowHealthUnwarned({ size: 6 });
        renderLowHealthUnwarned(users || []);
    } catch (error) {
        console.error('加载低健康未预警用户失败:', error);
    }
}

function renderLowHealthReviewSummary(summary) {
    const container = document.getElementById('lowHealthReviewSummary');
    if (!container) {
        return;
    }

    const lowestUser = summary.lowestHealthUser || {};
    container.innerHTML = [
        buildSummaryCard('待复核人数', String(summary.count || 0), 'low-health-review', '当前健康度低于 60 且尚未命中开放预警'),
        buildSummaryCard('平均健康度', formatHealth(summary.avgHealthScore), 'low-health-review', '这批待复核对象的平均健康水平'),
        buildSummaryCard('平均风险分', formatRisk(summary.avgRiskScore), 'low-health-review', '用于辅助判断是否需要人工追加干预'),
        buildSummaryCard('最集中群体', escapeHtml(summary.topClusterLabel || '暂无'), 'low-health-review', lowestUser.name ? `最低健康用户：${escapeHtml(lowestUser.name)} ${formatHealth(lowestUser.healthScore)}` : '当前暂无最低健康样本')
    ].join('');
}

function renderLowHealthUnwarned(users) {
    const container = document.getElementById('lowHealthUnwarnedList');
    if (!container) {
        return;
    }
    if (!users.length) {
        container.innerHTML = '<div class="table-empty-cell">当前没有低健康且未命中开放预警的用户。</div>';
        return;
    }

    container.innerHTML = users.map((user) => `
        <div class="attention-card">
            <div class="attention-card-head">
                <div>
                    <div class="attention-card-title">${escapeHtml(user.name || '-')}</div>
                    <div class="table-cell-sub">${escapeHtml(user.studentId || '-')} / ${escapeHtml(user.college || '-')} / ${escapeHtml(user.major || '-')}</div>
                </div>
                <span class="badge badge-warning">待复核</span>
            </div>
            <div class="attention-card-metrics">
                <span class="health-score health-score-danger">${Number(user.healthScore || 0).toFixed(7)}</span>
                <span class="attention-card-risk">风险 ${Number(user.riskScore || 0).toFixed(2)}</span>
            </div>
            <div class="cluster-overview-meta">
                <span class="badge badge-info">${escapeHtml(user.clusterLabel || '未聚类')}</span>
                <span>${escapeHtml(user.statusLabel || '待分析')}</span>
                <span>${escapeHtml(user.primaryConcern || '综合关注')}</span>
            </div>
            <div class="attention-card-note">${escapeHtml(user.focusReasonText || user.suggestion || '建议优先人工复核。')}</div>
            <div class="table-cell-sub">${escapeHtml(user.clusterSummary || '暂无群体摘要')}</div>
            <div class="attention-card-actions">
                <a class="btn btn-primary btn-small" href="analysis.html#user=${user.userId}">分析详情</a>
                <button class="btn btn-info btn-small" onclick="viewWarningsByUser(${user.userId})">筛选预警</button>
                <a class="btn btn-success btn-small" href="users.html">用户列表</a>
            </div>
        </div>
    `).join('');
}

async function loadHighRiskUsers() {
    try {
        const response = await api.getHighRiskUsersPage({
            page: highRiskPageState.page,
            size: highRiskPageState.size
        });

        if (shouldResetPage(highRiskPageState, response)) {
            return loadHighRiskUsers();
        }

        syncPageState(highRiskPageState, response);
        renderHighRiskTable(response.records || []);
        renderPager('highRisk', highRiskPageState);
    } catch (error) {
        console.error('加载高风险用户失败:', error);
    }
}

async function loadUnhandledWarnings() {
    try {
        const currentUser = await auth.getCurrentUser();
        const isAdmin = auth.isAdmin(currentUser);
        const response = await api.getWarningPage({
            page: warningPageState.page,
            size: warningPageState.size,
            userId: currentWarningUserId || undefined
        });

        if (shouldResetPage(warningPageState, response)) {
            return loadUnhandledWarnings();
        }

        syncPageState(warningPageState, response);
        renderWarningTable(response.records || [], isAdmin);
        renderPager('warning', warningPageState);
    } catch (error) {
        console.error('加载未处理预警失败:', error);
    }
}

function renderWarningFunnel(funnel) {
    const chartDom = document.getElementById('warningFunnelChart');
    const summaryDom = document.getElementById('warningFunnelSummary');
    if (summaryDom) {
        summaryDom.innerHTML = funnel.map((item) => buildSummaryCard(
            item.label || '-',
            String(item.value || 0),
            'funnel-stage',
            item.description || ''
        )).join('');
    }
    if (!chartDom || !window.echarts) {
        return;
    }

    warningFunnelChart = warningFunnelChart || echarts.init(chartDom);
    warningFunnelChart.setOption({
        tooltip: {
            trigger: 'item',
            formatter(params) {
                return `${escapeHtml(params.data.label || '')}<br>数量：${params.data.value || 0}<br>${escapeHtml(params.data.description || '')}`;
            }
        },
        series: [{
            type: 'funnel',
            left: '10%',
            top: 20,
            bottom: 20,
            width: '80%',
            minSize: '35%',
            maxSize: '100%',
            sort: 'descending',
            gap: 8,
            label: {
                show: true,
                position: 'inside',
                formatter: '{b}\n{c}'
            },
            itemStyle: {
                borderColor: '#fcf8f1',
                borderWidth: 2
            },
            data: funnel.map((item) => ({
                name: item.label,
                label: item.label,
                value: Number(item.value || 0),
                description: item.description,
                itemStyle: {
                    color: pickFunnelColor(item.key)
                }
            }))
        }]
    });

    bindChartResize();
}

function renderWarningLevelDistribution(levels) {
    const chartDom = document.getElementById('warningLevelChart');
    const summaryDom = document.getElementById('warningLevelSummary');
    if (summaryDom) {
        summaryDom.innerHTML = levels.map((item) => buildSummaryCard(
            `${item.label || item.level} 等级`,
            `${item.count || 0}`,
            'level-summary',
            `占当前开放预警 ${Number(item.percentage || 0).toFixed(2)}%`
        )).join('');
    }
    if (!chartDom || !window.echarts) {
        return;
    }

    warningLevelChart = warningLevelChart || echarts.init(chartDom);
    warningLevelChart.setOption({
        tooltip: {
            trigger: 'item',
            formatter(params) {
                return `${escapeHtml(params.data.name || '')}<br>数量：${params.data.value || 0}<br>占比：${Number(params.percent || 0).toFixed(2)}%`;
            }
        },
        legend: {
            bottom: 0,
            left: 'center'
        },
        series: [{
            type: 'pie',
            radius: ['45%', '70%'],
            center: ['50%', '45%'],
            label: {
                formatter: '{b}\n{d}%'
            },
            data: levels.map((item) => ({
                name: `${item.label || item.level}`,
                value: Number(item.count || 0),
                itemStyle: { color: pickLevelColor(item.level) }
            }))
        }]
    });

    bindChartResize();
}

function renderWarningRuleRanking(items) {
    const container = document.getElementById('warningRuleRanking');
    if (!container) {
        return;
    }
    if (!items.length) {
        container.innerHTML = '<div class="table-empty-cell">当前暂无规则命中排行数据。</div>';
        return;
    }

    container.innerHTML = items.map((item, index) => `
        <div class="compare-list-item">
            <div class="compare-list-head">
                <div>
                    <div class="table-cell-main">TOP ${index + 1} · ${escapeHtml(item.rule || '-')}</div>
                    <div class="table-cell-sub">影响用户 ${item.userCount || 0} 人 · 平均风险 ${formatRisk(item.avgRiskScore)}</div>
                </div>
                <span class="badge ${getWarningLevelBadgeClass(item.topLevel)}">${escapeHtml(item.topLevel || 'LOW')}</span>
            </div>
            <div class="compare-list-meta">
                <span>命中次数 ${item.count || 0}</span>
                <span>最新命中 ${formatDateTime(item.latestTime)}</span>
            </div>
        </div>
    `).join('');
}

function renderWarningTypeChart(data) {
    const chartDom = document.getElementById('warningTypeChart');
    if (!chartDom || !window.echarts) {
        return;
    }

    const distribution = data.distribution || [];
    warningTypeChart = warningTypeChart || echarts.init(chartDom);
    warningTypeChart.setOption({
        tooltip: {
            trigger: 'axis',
            axisPointer: { type: 'shadow' },
            formatter(params) {
                const source = params[0]?.data || {};
                return [
                    escapeHtml(source.type || ''),
                    `影响用户：${source.userCount || 0} 人`,
                    `占总用户：${Number(source.value || 0).toFixed(2)}%`,
                    `开放预警：${source.warningCount || 0} 条`,
                    `占全部预警：${Number(source.warningPercentage || 0).toFixed(2)}%`
                ].join('<br>');
            }
        },
        grid: { left: '4%', right: '4%', bottom: '6%', top: '8%', containLabel: true },
        xAxis: {
            type: 'value',
            max: 100,
            axisLabel: { formatter: '{value}%' }
        },
        yAxis: {
            type: 'category',
            inverse: true,
            data: distribution.map((item) => item.type)
        },
        series: [{
            type: 'bar',
            barWidth: 18,
            label: {
                show: true,
                position: 'right',
                formatter(params) {
                    return `${Number(params.value || 0).toFixed(2)}%`;
                }
            },
            data: distribution.map((item) => ({
                value: Number(item.userPercentage || 0),
                type: item.type,
                userCount: item.userCount || item.count || 0,
                warningCount: item.warningCount || 0,
                warningPercentage: item.warningPercentage || 0,
                itemStyle: {
                    color: item.type === '未预警' ? WARNING_PALETTE.accent : WARNING_PALETTE.primary
                }
            }))
        }]
    });

    bindChartResize();
}

function renderWarningTypeSummary(distribution) {
    const container = document.getElementById('warningTypeSummary');
    if (!container) {
        return;
    }

    container.innerHTML = distribution.map((item) => `
        <div class="card">
            <div class="card-body">
                <div class="table-cell-main">${escapeHtml(item.type || '-')}</div>
                <div class="table-cell-sub">影响用户 ${item.userCount || item.count || 0} 人 / 占总用户 ${Number(item.userPercentage || 0).toFixed(2)}%</div>
                <div class="table-cell-sub">开放预警 ${item.warningCount || 0} 条 / 占全部预警 ${Number(item.warningPercentage || 0).toFixed(2)}%</div>
            </div>
        </div>
    `).join('');
}

function renderHighRiskTable(users) {
    const tbody = document.getElementById('highRiskTable');
    if (!tbody) {
        return;
    }

    if (!users.length) {
        tbody.innerHTML = '<tr><td colspan="10" class="table-empty-cell">暂无高风险用户</td></tr>';
        return;
    }

    tbody.innerHTML = users.map((user) => `
        <tr>
            <td>
                <div class="table-cell-main">${escapeHtml(user.studentId || '-')}</div>
                <div class="table-cell-sub">${escapeHtml(user.name || '-')}</div>
            </td>
            <td>
                <div class="table-cell-main">${escapeHtml(user.college || '-')}</div>
                <div class="table-cell-sub">${escapeHtml(user.major || '-')}</div>
            </td>
            <td>
                <span class="badge badge-info">${escapeHtml(user.clusterLabel || '未命名群体')}</span>
                <div class="table-cell-sub">${escapeHtml(user.clusterTag || '暂无群体摘要')}</div>
            </td>
            <td><span class="health-score ${resolveHealthClass(user.healthScore)}">${formatHealth(user.healthScore)}</span></td>
            <td>
                <div class="table-cell-main">${formatRisk(user.riskScore)}</div>
                <div class="table-cell-sub">晚归 ${Number(user.lateReturnCount || 0)} 次</div>
            </td>
            <td>${user.warningCount || 0}</td>
            <td><span class="badge ${getWarningLevelBadgeClass(user.maxRiskLevel)}">${escapeHtml(user.maxRiskLevel || 'HIGH')}</span></td>
            <td>${escapeHtml(user.topWarningType || '-')}</td>
            <td><span class="status-chip ${resolveStatusClass(user.analysisStatus)}">${escapeHtml(user.analysisStatus || '重点预警')}</span></td>
            <td>
                <div class="table-actions">
                    <button class="btn btn-primary btn-small" onclick="viewAnalysis(${user.userId})">分析详情</button>
                    <button class="btn btn-info btn-small" onclick="viewWarningsByUser(${user.userId})">查看预警</button>
                </div>
            </td>
        </tr>
    `).join('');
}

function renderWarningTable(warnings, isAdmin) {
    const tbody = document.getElementById('warningTable');
    if (!tbody) {
        return;
    }

    if (!warnings.length) {
        tbody.innerHTML = '<tr><td colspan="9" class="table-empty-cell">暂无未处理预警数据</td></tr>';
        return;
    }

    tbody.innerHTML = warnings.map((warning) => {
        const actionButton = isAdmin
            ? `<button class="btn btn-success btn-small" onclick="handleWarningClick(${warning.warningId})">处理</button>`
            : '<button class="btn btn-small" disabled title="仅管理员可处理">只读</button>';

        return `
            <tr>
                <td>
                    <div class="table-cell-main">${escapeHtml(warning.studentId || String(warning.userId || '-'))}</div>
                    <div class="table-cell-sub">${escapeHtml(warning.name || '-')} / ${escapeHtml(warning.college || '-')} / ${escapeHtml(warning.major || '-')}</div>
                </td>
                <td>
                    <span class="badge badge-info">${escapeHtml(warning.clusterLabel || '未命名群体')}</span>
                    <div class="table-cell-sub">${escapeHtml(warning.statusLabel || '待分析')}</div>
                </td>
                <td>${escapeHtml(warning.warningType || '综合风险')}</td>
                <td><span class="badge ${getWarningLevelBadgeClass(warning.warningLevel)}">${escapeHtml(warning.warningLevel || 'MEDIUM')}</span></td>
                <td>
                    <div class="table-cell-main">预警 ${formatRisk(warning.riskScore)}</div>
                    <div class="table-cell-sub">当前风险 ${formatRisk(warning.analysisRiskScore)} / 健康度 <span class="health-score ${resolveHealthClass(warning.healthScore)}">${formatHealth(warning.healthScore)}</span></div>
                </td>
                <td>
                    <div class="table-cell-main">${escapeHtml(warning.riskDescription || '-')}</div>
                    <div class="table-cell-sub">触发规则：${escapeHtml(warning.triggerRule || '系统规则')}</div>
                    <div class="table-cell-sub">画像标签：${escapeHtml(warning.clusterTag || '暂无')}</div>
                </td>
                <td>${escapeHtml(warning.recommendedIntervention || '建议结合班级管理、辅导员访谈进一步核查。')}</td>
                <td>${formatDateTime(warning.createTime)}</td>
                <td>
                    <div class="table-actions">
                        ${actionButton}
                        <button class="btn btn-primary btn-small" onclick="viewAnalysis(${warning.userId})">分析详情</button>
                    </div>
                </td>
            </tr>
        `;
    }).join('');
}

function renderPager(prefix, state) {
    const info = document.getElementById(`${prefix}PageInfo`);
    const prevButton = document.getElementById(`${prefix}PrevButton`);
    const nextButton = document.getElementById(`${prefix}NextButton`);

    if (info) {
        info.textContent = `第 ${state.page + 1} 页 / 共 ${state.totalPages} 页，合计 ${state.total} 条`;
    }
    if (prevButton) {
        prevButton.disabled = state.page <= 0;
    }
    if (nextButton) {
        nextButton.disabled = state.page >= state.totalPages - 1 || state.total === 0;
    }
}

function syncPageState(state, response) {
    state.page = Number(response.page || 0);
    state.size = Number(response.size || state.size);
    state.total = Number(response.total || 0);
    state.totalPages = Math.max(1, Number(response.totalPages || 1));
}

function shouldResetPage(state, response) {
    const records = response.records || [];
    const total = Number(response.total || 0);
    const totalPages = Math.max(1, Number(response.totalPages || 1));
    if (records.length === 0 && total > 0 && state.page >= totalPages) {
        state.page = totalPages - 1;
        return true;
    }
    return false;
}

async function handleWarningClick(id) {
    try {
        await auth.requireAdmin();
    } catch (error) {
        if (error.code === 401) {
            auth.redirectToLogin('warning.html');
            return;
        }
        alert(error.message || '当前无处理权限');
        return;
    }

    if (!confirm('确认已处理这条预警记录吗？')) {
        return;
    }

    const remark = window.prompt('请输入处理备注（可选）', '已人工核查并关闭预警');
    if (remark === null) {
        return;
    }

    try {
        await api.handleWarning(id, {
            handlerRemark: remark.trim() || '已人工核查并关闭预警'
        });
        alert(`预警 ${id} 已处理完成`);
        await refreshPageData();
    } catch (error) {
        console.error('处理预警失败:', error);
        if (error.code === 401) {
            auth.redirectToLogin('warning.html');
            return;
        }
        alert(error.message || '处理失败，请稍后重试');
    }
}

function viewAnalysis(userId) {
    window.location.href = `analysis.html#user=${userId}`;
}

function viewWarningsByUser(userId) {
    window.location.hash = `user=${userId}`;
}

function clearWarningFilter() {
    history.replaceState(null, '', 'warning.html');
    currentWarningUserId = null;
    warningPageState.page = 0;
    refreshPageData();
}

function prevHighRiskPage() {
    if (highRiskPageState.page > 0) {
        highRiskPageState.page -= 1;
        loadHighRiskUsers();
    }
}

function nextHighRiskPage() {
    if (highRiskPageState.page < highRiskPageState.totalPages - 1) {
        highRiskPageState.page += 1;
        loadHighRiskUsers();
    }
}

function prevWarningPage() {
    if (warningPageState.page > 0) {
        warningPageState.page -= 1;
        loadUnhandledWarnings();
    }
}

function nextWarningPage() {
    if (warningPageState.page < warningPageState.totalPages - 1) {
        warningPageState.page += 1;
        loadUnhandledWarnings();
    }
}

function buildSummaryCard(label, value, extraClass, subtext) {
    return `
        <div class="summary-card ${extraClass || ''}">
            <div class="summary-card-label">${escapeHtml(label || '-')}</div>
            <div class="summary-card-value">${escapeHtml(value || '--')}</div>
            <div class="summary-card-sub">${escapeHtml(subtext || '')}</div>
        </div>
    `;
}

function bindChartResize() {
    if (chartResizeBound) {
        return;
    }
    window.addEventListener('resize', () => {
        warningTypeChart && warningTypeChart.resize();
        warningLevelChart && warningLevelChart.resize();
        warningFunnelChart && warningFunnelChart.resize();
    });
    chartResizeBound = true;
}

function pickFunnelColor(key) {
    switch (key) {
        case 'totalWarnings':
            return WARNING_PALETTE.secondary;
        case 'openWarnings':
            return WARNING_PALETTE.warning;
        case 'handledWarnings':
            return WARNING_PALETTE.accent;
        case 'warnedUsers':
            return WARNING_PALETTE.sand;
        case 'highRiskUsers':
            return WARNING_PALETTE.danger;
        default:
            return WARNING_PALETTE.secondary;
    }
}

function pickLevelColor(level) {
    switch (String(level || '').toUpperCase()) {
        case 'CRITICAL':
            return WARNING_PALETTE.danger;
        case 'HIGH':
            return WARNING_PALETTE.warning;
        case 'MEDIUM':
            return WARNING_PALETTE.sand;
        default:
            return WARNING_PALETTE.accent;
    }
}

function getWarningLevelBadgeClass(level) {
    if (level === 'CRITICAL' || level === 'HIGH' || level === '3') {
        return 'badge-danger';
    }
    if (level === 'MEDIUM' || level === '2') {
        return 'badge-warning';
    }
    return 'badge-success';
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

function resolveHealthClass(value) {
    const score = Number(value);
    if (!Number.isFinite(score)) {
        return '';
    }
    if (score < 60) {
        return 'health-score-danger';
    }
    if (score < 75) {
        return 'health-score-warning';
    }
    return 'health-score-success';
}

function formatHealth(value) {
    if (value === undefined || value === null || value === '') {
        return '--';
    }
    return Number(value).toFixed(7);
}

function formatRisk(value) {
    if (value === undefined || value === null || value === '') {
        return '--';
    }
    return Number(value).toFixed(2);
}

function formatDateTime(value) {
    if (!value) {
        return '-';
    }
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) {
        return String(value);
    }
    return date.toLocaleString('zh-CN', { hour12: false });
}

function setText(id, value) {
    const element = document.getElementById(id);
    if (element) {
        element.textContent = value;
    }
}

function escapeHtml(value) {
    return String(value ?? '')
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
}
