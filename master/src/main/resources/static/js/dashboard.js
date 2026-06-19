let trendChart = null;
let warningTypeChart = null;
let clusterChart = null;
let refreshTimer = null;
let refreshing = false;
let trendMode = 'health';
let trendCache = [];
const DASHBOARD_PALETTE = {
    primary: '#c96a4a',
    accent: '#748c5f',
    secondary: '#8d7360',
    warning: '#cb9543',
    danger: '#b65d4c',
    sand: '#d8b999',
    line: '#bda88f',
    text: '#77675a',
    grid: '#deccb8'
};

const MANUAL_TASK_ACTIONS = {
    analysis_full_manual: {
        buttonId: 'analysisFullTrigger',
        label: '提交全量分析',
        action: () => api.triggerAnalysisUpdate()
    },
    profile_full_manual: {
        buttonId: 'profileFullTrigger',
        label: '提交画像重建',
        action: () => api.triggerProfileUpdate()
    },
    warning_full_manual: {
        buttonId: 'warningFullTrigger',
        label: '提交预警同步',
        action: () => api.triggerWarningCheck()
    }
};

document.addEventListener('DOMContentLoaded', async () => {
    try {
        await auth.requireLogin();
        bindTrendSwitchEvents();
        await auth.renderSessionBar();
        await refreshDashboard(true);
        startPolling();
        window.addEventListener('resize', resizeCharts);
    } catch (error) {
        if (error.code === 401 || error.message === '请先登录') {
            auth.redirectToLogin('dashboard.html');
            return;
        }
        console.error('加载首页看板失败:', error);
        showMessage(error.message || '首页数据加载失败，请刷新后重试', 'danger');
    }
});

window.addEventListener('beforeunload', () => {
    if (refreshTimer) {
        window.clearInterval(refreshTimer);
    }
});

function bindTrendSwitchEvents() {
    document.getElementById('trendHealthButton')?.addEventListener('click', () => switchTrendMode('health'));
    document.getElementById('trendHighRiskButton')?.addEventListener('click', () => switchTrendMode('highRisk'));
}

function switchTrendMode(mode) {
    if (trendMode === mode) {
        return;
    }
    trendMode = mode;
    renderTrendSwitchState();
    renderTrendChart(trendCache);
}

function renderTrendSwitchState() {
    const healthButton = document.getElementById('trendHealthButton');
    const highRiskButton = document.getElementById('trendHighRiskButton');
    healthButton?.classList.toggle('active', trendMode === 'health');
    highRiskButton?.classList.toggle('active', trendMode === 'highRisk');
}

function startPolling() {
    if (refreshTimer) {
        window.clearInterval(refreshTimer);
    }
    refreshTimer = window.setInterval(() => refreshDashboard(false), 5000);
}

async function refreshDashboard(showErrorMessage) {
    if (refreshing) {
        return;
    }

    refreshing = true;
    try {
        const [stats, trend, warningStats, clusterOverview, manualTaskStatus, currentUser, lowHealthFocus] = await Promise.all([
            api.getStats(),
            api.getTrend(),
            api.getWarningStats(),
            api.getClusterOverview(),
            api.getManualTaskStatus(),
            auth.getCurrentUser(true),
            api.getLowHealthFocus({ size: 6 })
        ]);

        trendCache = Array.isArray(trend) ? trend : [];
        renderStats(stats);
        renderManualTasks(manualTaskStatus, currentUser);
        renderTrendSwitchState();
        renderTrendChart(trendCache);
        renderLowHealthFocus(lowHealthFocus || []);
        renderDailySummary(stats);
        renderClusterHealthCompare(clusterOverview || []);
        renderWarningTypeChart(warningStats || {});
        renderClusterChart(clusterOverview || []);
    } catch (error) {
        console.error('刷新首页看板失败:', error);
        if (showErrorMessage) {
            showMessage(error.message || '首页数据刷新失败，请稍后再试', 'danger');
        }
    } finally {
        refreshing = false;
    }
}

function renderStats(stats) {
    setText('totalUsers', stats.totalUsers || 0);
    setText('unhandledWarnings', stats.unhandledWarnings || 0);
    setText('highRiskCount', stats.highRiskUsers || 0);
    setText('avgHealth', Number(stats.avgHealthScore || 0).toFixed(7));
    setText('unwarnedUsers', stats.unwarnedUsers || 0);
    setText('warningCoverage', `${Number(stats.warningCoverage || 0).toFixed(2)}%`);
    setText('totalUsersTrend', `当前活跃用户 ${stats.activeUsers || 0} 人`);
    setText('warningTrend', `当前最高风险等级：${stats.maxWarningLevel || 'NONE'}`);
    setComparisonTrend(
        'highRiskTrend',
        stats.highRiskComparisonLabel || '较上一分析日',
        Number(stats.highRiskDeltaComparedToPrevious || 0),
        0,
        'lower'
    );
    setComparisonTrend(
        'avgHealthTrend',
        stats.healthComparisonLabel || '较上一分析日',
        Number(stats.healthDeltaComparedToPrevious || 0),
        7,
        'higher'
    );
    setText('unwarnedTrend', `未预警用户 ${stats.unwarnedUsers || 0} 人`);
    setText('coverageTrend', `已预警用户覆盖 ${Number(stats.warningCoverage || 0).toFixed(2)}%`);
    setText(
        'trendChartHint',
        stats.latestAnalysisDate
            ? `当前仅展示最近真实分析快照趋势，最新分析日为 ${stats.latestAnalysisDate}。`
            : '当前仅展示最近真实分析快照趋势。'
    );
}

function setComparisonTrend(id, label, delta, digits, improvedWhen) {
    const element = document.getElementById(id);
    if (!element) {
        return;
    }
    const trendState = resolveTrendState(delta, improvedWhen);
    element.innerHTML = `${escapeHtml(label)} <span class="metric-delta ${trendState}">${formatDelta(delta, digits)}</span>`;
}

function resolveTrendState(delta, improvedWhen) {
    const number = Number(delta || 0);
    if (number === 0) {
        return 'flat';
    }
    if (improvedWhen === 'lower') {
        return number < 0 ? 'up' : 'down';
    }
    return number > 0 ? 'up' : 'down';
}

function renderManualTasks(status, currentUser) {
    const tasks = Array.isArray(status?.tasks) ? status.tasks : [];
    const taskGrid = document.getElementById('manualTaskGrid');
    const summary = document.getElementById('manualTaskSummary');
    const admin = auth.isAdmin(currentUser);

    if (!taskGrid || !summary) {
        return;
    }

    const activeTask = tasks.find((item) => item.running);
    if (activeTask) {
        summary.textContent = `当前正在执行：${activeTask.taskName}，已完成 ${activeTask.processedCount || 0} / ${activeTask.totalCount || 0}，请耐心等待后台处理。`;
    } else if (tasks.length) {
        summary.textContent = `当前没有正在执行的全量任务。${admin ? '管理员可以手动提交全量分析、画像重建和预警同步任务。' : '当前账号为查看员，可查看进度但不能提交任务。'}`;
    } else {
        summary.textContent = '暂未读取到后台全量任务状态。';
    }

    taskGrid.innerHTML = tasks.map((task) => buildManualTaskCard(task, admin)).join('');
    attachManualTaskEvents(admin);
}

function buildManualTaskCard(task, admin) {
    const action = MANUAL_TASK_ACTIONS[task.taskKey];
    const progressPercent = Number(task.progressPercent || 0);
    const canTrigger = admin && !task.running && !!task.canTrigger;
    const statusClass = task.running
        ? 'info'
        : task.lastStatus === 'SUCCESS'
            ? 'success'
            : task.lastStatus === 'FAILED'
                ? 'danger'
                : 'warning';
    const statusText = task.running
        ? '执行中'
        : task.lastStatus === 'SUCCESS'
            ? '最近成功'
            : task.lastStatus === 'FAILED'
                ? '最近失败'
                : '待执行';
    const blockedText = task.blockedByTask
        ? `<div class="task-card-meta">当前被“${escapeHtml(task.blockedByTask)}”占用，待其完成后可再次提交。</div>`
        : '';
    const operatorText = task.operatorLabel
        ? `<div class="task-card-meta">最近提交人：${escapeHtml(task.operatorLabel)}</div>`
        : '';

    return `
        <div class="task-progress-card">
            <div class="task-progress-head">
                <div>
                    <div class="table-cell-main">${escapeHtml(task.taskName || '-')}</div>
                    <div class="table-cell-sub">${escapeHtml(task.description || '')}</div>
                </div>
                <span class="status-chip ${statusClass}">${statusText}</span>
            </div>
            <div class="progress-track">
                <span class="progress-fill" style="width:${Math.max(0, Math.min(progressPercent, 100))}%;"></span>
            </div>
            <div class="task-progress-meta">
                <span>进度：${progressPercent.toFixed(1)}%</span>
                <span>已处理：${task.processedCount || 0} / ${task.totalCount || 0}</span>
            </div>
            <div class="task-card-message">${escapeHtml(task.message || '暂无状态信息')}</div>
            ${operatorText}
            ${task.lastCompletedTime ? `<div class="task-card-meta">最近完成：${formatDateTime(task.lastCompletedTime)}</div>` : ''}
            ${blockedText}
            <div class="task-card-actions">
                <button class="btn btn-primary" id="${action?.buttonId || ''}" ${canTrigger ? '' : 'disabled'}>
                    ${task.running ? '任务执行中' : (action?.label || '提交任务')}
                </button>
            </div>
        </div>
    `;
}

function attachManualTaskEvents(admin) {
    Object.entries(MANUAL_TASK_ACTIONS).forEach(([, config]) => {
        const button = document.getElementById(config.buttonId);
        if (!button) {
            return;
        }
        button.addEventListener('click', async () => {
            if (!admin) {
                showMessage('当前账号为查看员，请使用管理员账号执行全量任务。', 'warning');
                return;
            }
            await triggerManualTask(config, button);
        });
    });
}

async function triggerManualTask(config, button) {
    const originalText = button.textContent;
    try {
        await auth.requireAdmin();
        button.disabled = true;
        button.textContent = '任务提交中...';
        const result = await config.action();
        showMessage(result.message || `${config.label}已提交，系统正在后台执行。`, 'success');
        await refreshDashboard(false);
    } catch (error) {
        if (error.code === 401) {
            auth.redirectToLogin('dashboard.html');
            return;
        }
        showMessage(error.message || `${config.label}失败，请稍后重试`, 'danger');
    } finally {
        button.textContent = originalText;
    }
}

function renderTrendChart(trendData) {
    if (!window.echarts) {
        return;
    }
    const dom = document.getElementById('trendChart');
    if (!dom) {
        return;
    }
    trendChart = trendChart || echarts.init(dom);

    const metricConfig = trendMode === 'health'
        ? {
            name: '平均健康度',
            color: DASHBOARD_PALETTE.primary,
            data: trendData.map((item) => Number(item.avgHealthScore || 0)),
            deltaKey: 'healthDelta',
            digits: 7,
            max: 100
        }
        : {
            name: '高风险用户数',
            color: DASHBOARD_PALETTE.warning,
            data: trendData.map((item) => Number(item.highRiskUsers || 0)),
            deltaKey: 'highRiskDelta',
            digits: 0,
            max: Math.max(5, ...trendData.map((item) => Number(item.highRiskUsers || 0))) + 2
        };

    trendChart.setOption({
        tooltip: {
            trigger: 'axis',
            formatter(params) {
                const index = params[0]?.dataIndex ?? 0;
                const current = trendData[index] || {};
                const value = metricConfig.data[index] ?? 0;
                return [
                    current.date || '',
                    `${metricConfig.name}：${metricConfig.digits === 7 ? Number(value).toFixed(7) : Number(value).toFixed(0)}`,
                    `${current.comparisonLabel || '较上一分析日'}：${formatDelta(current[metricConfig.deltaKey] || 0, metricConfig.digits)}`
                ].join('<br>');
            }
        },
        grid: { left: '4%', right: '4%', bottom: '8%', top: '14%', containLabel: true },
        xAxis: {
            type: 'category',
            data: trendData.map((item) => item.date || ''),
            axisLine: { lineStyle: { color: DASHBOARD_PALETTE.line } },
            axisLabel: { color: DASHBOARD_PALETTE.text }
        },
        yAxis: {
            type: 'value',
            min: 0,
            max: metricConfig.max,
            axisLabel: {
                color: DASHBOARD_PALETTE.text,
                formatter: (value) => metricConfig.digits === 7 ? Number(value).toFixed(0) : Number(value).toFixed(0)
            },
            splitLine: { lineStyle: { color: DASHBOARD_PALETTE.grid } }
        },
        series: [{
            name: metricConfig.name,
            type: 'line',
            smooth: true,
            symbolSize: 8,
            data: metricConfig.data,
            lineStyle: { color: metricConfig.color, width: 3 },
            itemStyle: { color: metricConfig.color },
            areaStyle: {
                color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
                    { offset: 0, color: `${metricConfig.color}55` },
                    { offset: 1, color: `${metricConfig.color}10` }
                ])
            }
        }]
    }, true);
}

function renderLowHealthFocus(users) {
    const container = document.getElementById('lowHealthFocusList');
    if (!container) {
        return;
    }
    if (!users.length) {
        container.innerHTML = '<div class="table-empty-cell">当前没有低健康度重点关注用户。</div>';
        return;
    }

    container.innerHTML = users.map((user) => `
        <div class="attention-card">
            <div class="attention-card-head">
                <div>
                    <div class="attention-card-title">${escapeHtml(user.name || '-')}</div>
                    <div class="table-cell-sub">${escapeHtml(user.studentId || '-')} / ${escapeHtml(user.college || '-')} / ${escapeHtml(user.major || '-')}</div>
                </div>
                <span class="badge badge-danger">${escapeHtml(user.attentionTag || '低健康提醒')}</span>
            </div>
            <div class="attention-card-metrics">
                <span class="health-score health-score-danger">${Number(user.healthScore || 0).toFixed(7)}</span>
                <span class="attention-card-risk">风险 ${Number(user.riskScore || 0).toFixed(2)}</span>
            </div>
            <div class="table-cell-sub">${escapeHtml(user.clusterLabel || '未聚类')} · ${escapeHtml(user.statusLabel || '待分析')}</div>
            <div class="attention-card-note">${escapeHtml(user.suggestion || '建议优先查看该用户分析详情。')}</div>
            <div class="attention-card-actions">
                <a class="btn btn-primary btn-small" href="analysis.html#user=${user.userId}">分析详情</a>
                <a class="btn btn-warning btn-small" href="warning.html#user=${user.userId}">预警记录</a>
            </div>
        </div>
    `).join('');
}

function renderDailySummary(stats) {
    const container = document.getElementById('dailySummaryCards');
    if (!container) {
        return;
    }

    const cards = [
        buildSummaryCard(
            '平均健康度变化',
            formatDelta(stats.healthDeltaComparedToPrevious || 0, 7),
            stats.healthComparisonLabel || '较上一分析日',
            resolveTrendState(stats.healthDeltaComparedToPrevious || 0, 'higher'),
            `${stats.latestAnalysisDate || '-'} 与 ${stats.previousAnalysisDate || '-'} 的真实分析快照对比`
        ),
        buildSummaryCard(
            '高风险人数变化',
            formatDelta(stats.highRiskDeltaComparedToPrevious || 0, 0),
            stats.highRiskComparisonLabel || '较上一分析日',
            resolveTrendState(stats.highRiskDeltaComparedToPrevious || 0, 'lower'),
            `当前高风险人数 ${stats.highRiskUsers || 0} 人`
        ),
        buildSummaryCard(
            '低健康人数变化',
            formatDelta(stats.lowHealthDeltaComparedToPrevious || 0, 0),
            stats.lowHealthComparisonLabel || '较上一分析日',
            resolveTrendState(stats.lowHealthDeltaComparedToPrevious || 0, 'lower'),
            `当前低健康用户 ${stats.lowHealthUsers || 0} 人`
        )
    ];

    container.innerHTML = cards.join('');
}

function buildSummaryCard(title, delta, label, state, note) {
    return `
        <div class="summary-card">
            <div class="summary-card-label">${escapeHtml(title)}</div>
            <div class="summary-card-value ${state}">${escapeHtml(delta)}</div>
            <div class="summary-card-sub">${escapeHtml(label)}</div>
            <div class="table-cell-sub">${escapeHtml(note)}</div>
        </div>
    `;
}

function renderClusterHealthCompare(clusterOverview) {
    const container = document.getElementById('clusterHealthCompareList');
    if (!container) {
        return;
    }
    if (!clusterOverview.length) {
        container.innerHTML = '<div class="table-empty-cell">当前暂无群体健康对比数据。</div>';
        return;
    }

    container.innerHTML = [...clusterOverview]
        .sort((left, right) => Number(right.avgHealthScore || 0) - Number(left.avgHealthScore || 0))
        .map((item) => `
            <div class="compare-list-item">
                <div class="compare-list-head">
                    <div class="table-cell-main">${escapeHtml(item.label || '未命名群体')}</div>
                    <span class="health-score ${resolveCompareHealthClass(item.avgHealthScore)}">${Number(item.avgHealthScore || 0).toFixed(7)}</span>
                </div>
                <div class="compare-list-meta">
                    <span>用户数 ${item.count || 0}</span>
                    <span>预警率 ${Number(item.warningRate || 0).toFixed(2)}%</span>
                    <span>低健康 ${item.lowHealthUserCount || 0} 人</span>
                </div>
                <div class="table-cell-sub">${escapeHtml(item.focus || '')}</div>
            </div>
        `).join('');
}

function resolveCompareHealthClass(value) {
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

function renderWarningTypeChart(data) {
    if (!window.echarts) {
        return;
    }
    const dom = document.getElementById('warningTypeChart');
    if (!dom) {
        return;
    }
    const distribution = Array.isArray(data.distribution) ? data.distribution : [];
    warningTypeChart = warningTypeChart || echarts.init(dom);
    warningTypeChart.setOption({
        tooltip: {
            trigger: 'axis',
            axisPointer: { type: 'shadow' },
            formatter(params) {
                const item = distribution[params[0]?.dataIndex || 0] || {};
                return `${item.type || '-'}<br>影响用户：${item.count || 0}<br>占总用户：${Number(item.userPercentage || 0).toFixed(2)}%`;
            }
        },
        grid: { left: '3%', right: '3%', bottom: '3%', top: '6%', containLabel: true },
        xAxis: {
            type: 'value',
            axisLabel: { formatter: '{value}%', color: DASHBOARD_PALETTE.text },
            splitLine: { lineStyle: { color: DASHBOARD_PALETTE.grid } }
        },
        yAxis: {
            type: 'category',
            data: distribution.map((item) => item.type || '-'),
            axisLabel: { color: DASHBOARD_PALETTE.text }
        },
        series: [{
            type: 'bar',
            data: distribution.map((item) => Number(item.userPercentage || 0)),
            itemStyle: {
                color(params) {
                    const palette = [DASHBOARD_PALETTE.primary, DASHBOARD_PALETTE.accent, DASHBOARD_PALETTE.warning, DASHBOARD_PALETTE.secondary, DASHBOARD_PALETTE.danger, DASHBOARD_PALETTE.sand];
                    return palette[params.dataIndex % palette.length];
                },
                borderRadius: [0, 6, 6, 0]
            },
            label: {
                show: true,
                position: 'right',
                formatter: (params) => `${Number(params.value || 0).toFixed(2)}%`
            }
        }]
    });
}

function renderClusterChart(clusterOverview) {
    if (!window.echarts) {
        return;
    }
    const dom = document.getElementById('clusterChart');
    if (!dom) {
        return;
    }
    clusterChart = clusterChart || echarts.init(dom);
    clusterChart.setOption({
        tooltip: {
            trigger: 'axis',
            formatter(params) {
                const item = clusterOverview[params[0]?.dataIndex || 0] || {};
                return [
                    item.label || '',
                    `用户数：${item.count || 0}`,
                    `平均健康度：${Number(item.avgHealthScore || 0).toFixed(7)}`,
                    `预警人数：${item.warningUserCount || 0}`,
                    `低健康人数：${item.lowHealthUserCount || 0}`,
                    item.focus || ''
                ].join('<br>');
            }
        },
        grid: { left: '3%', right: '3%', bottom: '12%', top: '5%', containLabel: true },
        xAxis: {
            type: 'category',
            data: clusterOverview.map((item) => item.label || '未命名群体'),
            axisLabel: {
                color: DASHBOARD_PALETTE.text,
                interval: 0,
                rotate: 15
            }
        },
        yAxis: {
            type: 'value',
            axisLabel: { color: DASHBOARD_PALETTE.text },
            splitLine: { lineStyle: { color: DASHBOARD_PALETTE.grid } }
        },
        series: [{
            type: 'bar',
            data: clusterOverview.map((item) => item.count || 0),
            barWidth: '55%',
            itemStyle: {
                borderRadius: [6, 6, 0, 0],
                color(params) {
                    const colors = [DASHBOARD_PALETTE.primary, DASHBOARD_PALETTE.accent, DASHBOARD_PALETTE.warning, DASHBOARD_PALETTE.secondary, DASHBOARD_PALETTE.danger];
                    return colors[params.dataIndex % colors.length];
                }
            }
        }]
    });
}

function formatDelta(value, digits) {
    const number = Number(value || 0);
    const formatted = digits === 0 ? Math.round(number).toString() : number.toFixed(digits);
    return number > 0 ? `+${formatted}` : formatted;
}

function formatDateTime(value) {
    if (!value) {
        return '-';
    }
    const date = new Date(value);
    return Number.isNaN(date.getTime()) ? '-' : date.toLocaleString();
}

function resizeCharts() {
    trendChart && trendChart.resize();
    warningTypeChart && warningTypeChart.resize();
    clusterChart && clusterChart.resize();
}

function setText(id, value) {
    const element = document.getElementById(id);
    if (element) {
        element.textContent = value;
    }
}

function showMessage(message, type = 'info') {
    const container = document.getElementById('dashboardMessage');
    if (!container) {
        return;
    }
    container.innerHTML = `<div class="alert alert-${type}">${escapeHtml(message)}</div>`;
    window.clearTimeout(showMessage.timer);
    showMessage.timer = window.setTimeout(() => {
        container.innerHTML = '';
    }, 5000);
}

function escapeHtml(value) {
    return String(value ?? '')
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
}
