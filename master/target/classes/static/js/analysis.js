let radarChart = null;

const analysisElements = {
    emptyState: null,
    content: null,
    searchInput: null,
    searchButton: null,
    resetButton: null,
    userBasicInfo: null,
    userMetrics: null,
    historyTable: null,
    warningsTable: null,
    healthAlert: null,
    comparisonCards: null,
    dimensions: null,
    timeline: null,
    clusterBenchmark: null
};
const ANALYSIS_PALETTE = {
    primary: '#c96a4a',
    secondary: '#8d7360',
    grid: '#deccb8',
    radarFillA: 'rgba(201, 106, 74, 0.05)',
    radarFillB: 'rgba(201, 106, 74, 0.10)',
    radarArea: 'rgba(201, 106, 74, 0.24)'
};

document.addEventListener('DOMContentLoaded', async () => {
    try {
        await auth.requireLogin();
        await auth.renderSessionBar();
        cacheElements();
        bindEvents();
        initChart();
        await restoreSearchFromHash();
    } catch (error) {
        if (error.code === 401 || error.message === '请先登录') {
            auth.redirectToLogin('analysis.html');
            return;
        }
        console.error('初始化多维分析页面失败:', error);
        alert(error.message || '多维分析页面加载失败');
    }
});

function cacheElements() {
    analysisElements.emptyState = document.getElementById('analysisEmptyState');
    analysisElements.content = document.getElementById('analysisContent');
    analysisElements.searchInput = document.getElementById('searchInput');
    analysisElements.searchButton = document.getElementById('searchButton');
    analysisElements.resetButton = document.getElementById('resetButton');
    analysisElements.userBasicInfo = document.getElementById('userBasicInfo');
    analysisElements.userMetrics = document.getElementById('userMetrics');
    analysisElements.historyTable = document.getElementById('analysisHistoryTable');
    analysisElements.warningsTable = document.getElementById('userWarningsTable');
    analysisElements.healthAlert = document.getElementById('analysisHealthAlert');
    analysisElements.comparisonCards = document.getElementById('analysisComparisonCards');
    analysisElements.dimensions = document.getElementById('analysisDimensions');
    analysisElements.timeline = document.getElementById('analysisTimeline');
    analysisElements.clusterBenchmark = document.getElementById('analysisClusterBenchmark');
}

function bindEvents() {
    analysisElements.searchButton?.addEventListener('click', () => searchUser());
    analysisElements.resetButton?.addEventListener('click', resetAnalysisView);
    analysisElements.searchInput?.addEventListener('keydown', (event) => {
        if (event.key === 'Enter') {
            event.preventDefault();
            searchUser();
        }
    });
    window.addEventListener('resize', () => radarChart && radarChart.resize());
}

function initChart() {
    const dom = document.getElementById('userRadarChart');
    if (dom) {
        radarChart = echarts.init(dom);
    }
}

async function restoreSearchFromHash() {
    const hash = window.location.hash.substring(1);
    const params = new URLSearchParams(hash);
    const userId = params.get('user');
    if (!userId) {
        return;
    }
    if (analysisElements.searchInput) {
        analysisElements.searchInput.value = userId;
    }
    await searchUser();
}

async function searchUser() {
    const keyword = analysisElements.searchInput?.value.trim();
    if (!keyword) {
        alert('请输入学号、姓名或用户 ID');
        return;
    }

    try {
        const profileData = await api.getProfile(keyword);
        const userId = profileData?.user?.userId;
        if (!userId) {
            throw new Error('未找到对应用户');
        }

        const [analysis, warnings, history, insight] = await Promise.all([
            api.getAnalysis(userId),
            api.getUserWarnings(userId),
            api.getAnalysisHistory(userId),
            api.getAnalysisInsight(userId)
        ]);

        renderAnalysisResult(profileData.user, profileData.profile, analysis, warnings, history, insight);
        window.location.hash = `user=${userId}`;
    } catch (error) {
        console.error('查询用户分析失败:', error);
        alert(error.message || '未找到对应用户或分析数据');
    }
}

function renderAnalysisResult(user, profile, analysis, warnings, history, insight) {
    toggleAnalysisContent(true);

    const tagPayload = parseProfileTags(profile?.tags);
    const clusterLabel = tagPayload.label || '未聚类';
    const clusterSummary = tagPayload.summary || '暂无画像摘要';

    analysisElements.userBasicInfo.innerHTML = `
        <div class="detail-row"><span class="detail-label">姓名</span><span class="detail-value">${escapeHtml(user?.name || '-')}</span></div>
        <div class="detail-row"><span class="detail-label">学号</span><span class="detail-value">${escapeHtml(user?.studentId || '-')}</span></div>
        <div class="detail-row"><span class="detail-label">学院</span><span class="detail-value">${escapeHtml(user?.college || '-')}</span></div>
        <div class="detail-row"><span class="detail-label">专业</span><span class="detail-value">${escapeHtml(user?.major || '-')}</span></div>
        <div class="detail-row"><span class="detail-label">所在群体</span><span class="detail-value">${escapeHtml(clusterLabel)}</span></div>
        <div class="detail-row"><span class="detail-label">群体摘要</span><span class="detail-value">${escapeHtml(clusterSummary)}</span></div>
        <div class="detail-row"><span class="detail-label">快照日期</span><span class="detail-value">${escapeHtml(analysis?.analysisDate || '-')}</span></div>
        <div class="detail-row"><span class="detail-label">写入时间</span><span class="detail-value">${formatDateTime(analysis?.updateTime)}</span></div>
    `;

    renderHealthAlert(user, analysis, warnings);
    renderMetrics(analysis);
    renderComparison(insight?.comparison || {});
    renderDimensions(insight?.dimensions || []);
    renderTimeline(insight?.timeline || []);
    renderClusterBenchmark(insight?.clusterBenchmark || {});
    renderRadar(analysis);
    renderHistory(history || []);
    renderWarnings(warnings || []);
}

function renderHealthAlert(user, analysis, warnings) {
    if (!analysisElements.healthAlert) {
        return;
    }

    const healthScore = Number(analysis?.healthScore || 0);
    if (!Number.isFinite(healthScore) || healthScore >= 60) {
        analysisElements.healthAlert.style.display = 'none';
        analysisElements.healthAlert.innerHTML = '';
        return;
    }

    const reasons = [];
    if (Number(analysis?.lateReturnCount || 0) > 0) {
        reasons.push(`晚归次数 ${analysis.lateReturnCount} 次`);
    }
    if (Number(analysis?.networkRisk || 0) >= 25) {
        reasons.push(`网络健康风险较高（${Number(analysis.networkRisk).toFixed(2)}）`);
    }
    if ((Number(analysis?.studyTrafficRatio || 0) * 100) < 20) {
        reasons.push(`学习流量占比偏低（${(Number(analysis.studyTrafficRatio || 0) * 100).toFixed(2)}%）`);
    }
    if (analysis?.absenteeFlag) {
        reasons.push('存在行为考勤异常标记');
    }
    if (Number(analysis?.unreturnedCount || 0) > 0) {
        reasons.push(`仍有 ${analysis.unreturnedCount} 本未归还图书`);
    }

    const suggestion = warnings.length
        ? '当前已存在开放预警，建议先查看预警记录并结合辅导员访谈复核。'
        : '当前尚未命中开放预警，建议优先人工复核最近一次分析结果。';

    analysisElements.healthAlert.style.display = 'block';
    analysisElements.healthAlert.innerHTML = `
        <div class="table-cell-main">${escapeHtml(user?.name || '该用户')} 当前健康度偏低（${healthScore.toFixed(7)}）</div>
        <div class="analysis-alert-reasons">${escapeHtml(reasons.length ? reasons.join('；') : '综合健康分已低于关注阈值，建议重点跟进。')}</div>
        <div class="analysis-alert-actions">
            <a class="btn btn-primary btn-small" href="warning.html#user=${user?.userId}">查看预警记录</a>
            <a class="btn btn-info btn-small" href="users.html">返回用户列表</a>
            <span class="table-cell-sub">${escapeHtml(suggestion)}</span>
        </div>
    `;
}

function renderMetrics(analysis) {
    analysisElements.userMetrics.innerHTML = `
        <div class="metric-card info">
            <div class="metric-label">健康度</div>
            <div class="metric-value">${Number(analysis?.healthScore || 0).toFixed(7)}</div>
            <div class="metric-trend">综合健康分，数值越高越稳定</div>
        </div>
        <div class="metric-card danger">
            <div class="metric-label">风险分</div>
            <div class="metric-value">${Number(analysis?.riskScore || 0).toFixed(2)}</div>
            <div class="metric-trend">风险分 = 100 - 健康度</div>
        </div>
        <div class="metric-card success">
            <div class="metric-label">日均在线</div>
            <div class="metric-value">${Number(analysis?.avgOnlineHours || 0).toFixed(2)}h</div>
            <div class="metric-trend">按最新分析窗口估算</div>
        </div>
        <div class="metric-card warning">
            <div class="metric-label">学习流量占比</div>
            <div class="metric-value">${(Number(analysis?.studyTrafficRatio || 0) * 100).toFixed(2)}%</div>
            <div class="metric-trend">学习相关网络流量占总流量比例</div>
        </div>
    `;
}

function renderComparison(comparison) {
    if (!analysisElements.comparisonCards) {
        return;
    }
    const label = comparison.label || '较上一分析周期';
    analysisElements.comparisonCards.innerHTML = [
        buildComparisonCard('健康度变化', formatDelta(comparison.healthDelta || 0, 7), resolveTrendState(comparison.healthDelta || 0, 'higher'), label),
        buildComparisonCard('风险分变化', formatDelta(comparison.riskDelta || 0, 2), resolveTrendState(comparison.riskDelta || 0, 'lower'), label),
        buildComparisonCard('日均在线变化', formatDelta(comparison.onlineDelta || 0, 2), resolveTrendState(comparison.onlineDelta || 0, 'lower'), label),
        buildComparisonCard('晚归次数变化', formatDelta(comparison.lateReturnDelta || 0, 0), resolveTrendState(comparison.lateReturnDelta || 0, 'lower'), label)
    ].join('');
}

function buildComparisonCard(title, value, state, label) {
    return `
        <div class="summary-card">
            <div class="summary-card-label">${escapeHtml(title)}</div>
            <div class="summary-card-value ${state}">${escapeHtml(value)}</div>
            <div class="summary-card-sub">${escapeHtml(label)}</div>
        </div>
    `;
}

function renderDimensions(dimensions) {
    if (!analysisElements.dimensions) {
        return;
    }
    if (!dimensions.length) {
        analysisElements.dimensions.innerHTML = '<div class="table-empty-cell">当前暂无维度拆解数据。</div>';
        return;
    }

    analysisElements.dimensions.innerHTML = dimensions.map((item) => `
        <div class="compare-list-item detail-card">
            <div class="compare-list-head">
                <div class="table-cell-main">${escapeHtml(item.label || '-')}</div>
                <span class="badge ${resolveDimensionBadge(item.score)}">${Number(item.score || 0).toFixed(2)}</span>
            </div>
            <div class="dimension-progress">
                <span class="dimension-progress-fill" style="width:${Math.max(0, Math.min(Number(item.score || 0), 100))}%;"></span>
            </div>
            <div class="table-cell-sub">${escapeHtml(item.detail || '')}</div>
            <div class="cluster-overview-meta">
                <span>${escapeHtml(item.suggestion || '')}</span>
            </div>
        </div>
    `).join('');
}

function renderTimeline(timeline) {
    if (!analysisElements.timeline) {
        return;
    }
    if (!timeline.length) {
        analysisElements.timeline.innerHTML = '<div class="table-empty-cell">当前暂无异常行为时间线数据。</div>';
        return;
    }

    analysisElements.timeline.innerHTML = timeline.map((item) => `
        <div class="timeline-item severity-${String(item.severity || 'INFO').toLowerCase()}">
            <div class="timeline-time">${escapeHtml(formatTimelineTime(item.time))}</div>
            <div class="timeline-body">
                <div class="timeline-title-row">
                    <div class="table-cell-main">${escapeHtml(item.title || '-')}</div>
                    <span class="status-chip ${mapTimelineSeverity(item.severity)}">${escapeHtml(item.category || 'EVENT')}</span>
                </div>
                <div class="table-cell-sub">${escapeHtml(item.description || '')}</div>
            </div>
        </div>
    `).join('');
}

function renderClusterBenchmark(benchmark) {
    if (!analysisElements.clusterBenchmark) {
        return;
    }
    const metrics = Array.isArray(benchmark.metrics) ? benchmark.metrics : [];
    if (!metrics.length) {
        analysisElements.clusterBenchmark.innerHTML = '<div class="table-empty-cell">当前暂无群体均值差异数据。</div>';
        return;
    }

    analysisElements.clusterBenchmark.innerHTML = metrics.map((metric) => `
        <div class="compare-list-item">
            <div class="compare-list-head">
                <div>
                    <div class="table-cell-main">${escapeHtml(metric.label || '-')}</div>
                    <div class="table-cell-sub">${escapeHtml(benchmark.clusterLabel || '未聚类')} · ${escapeHtml(benchmark.clusterFocus || '')}</div>
                </div>
                <span class="benchmark-delta ${metric.state || 'flat'}">${formatDelta(metric.delta || 0, 2)}</span>
            </div>
            <div class="compare-list-meta">
                <span>用户值 ${Number(metric.userValue || 0).toFixed(2)}</span>
                <span>群体均值 ${Number(metric.clusterValue || 0).toFixed(2)}</span>
            </div>
        </div>
    `).join('');
}

function renderWarnings(warnings) {
    if (!analysisElements.warningsTable) {
        return;
    }

    if (!warnings.length) {
        analysisElements.warningsTable.innerHTML = '<tr><td colspan="6" class="table-empty-cell">当前暂无开放预警</td></tr>';
        return;
    }

    analysisElements.warningsTable.innerHTML = warnings.map((warning) => `
        <tr>
            <td>${escapeHtml(warning.warningType || '-')}</td>
            <td><span class="status-chip ${mapWarningLevel(warning.warningLevel)}">${escapeHtml(warning.warningLevel || '-')}</span></td>
            <td>${Number(warning.riskScore || 0).toFixed(2)}</td>
            <td>${escapeHtml(warning.triggerRule || warning.riskDescription || '-')}</td>
            <td>${escapeHtml(warning.recommendedIntervention || '-')}</td>
            <td>${formatDateTime(warning.createTime)}</td>
        </tr>
    `).join('');
}

function renderHistory(history) {
    if (!analysisElements.historyTable) {
        return;
    }

    if (!history.length) {
        analysisElements.historyTable.innerHTML = '<tr><td colspan="6" class="table-empty-cell">暂无历史分析记录</td></tr>';
        return;
    }

    analysisElements.historyTable.innerHTML = history.map((item) => `
        <tr>
            <td>${escapeHtml(item.analysisDate || '-')}</td>
            <td>${formatDateTime(item.updateTime)}</td>
            <td>${Number(item.avgOnlineHours || 0).toFixed(2)}h</td>
            <td>${(Number(item.studyTrafficRatio || 0) * 100).toFixed(2)}%</td>
            <td>${item.lateReturnCount || 0}</td>
            <td>${Number(item.healthScore || 0).toFixed(7)}</td>
        </tr>
    `).join('');
}

function renderRadar(analysis) {
    if (!radarChart) {
        return;
    }

    const values = [
        clamp(Number(analysis?.studyTrafficRatio || 0) * 240),
        clamp(100 - Number(analysis?.networkRisk || 0)),
        clamp((Number(analysis?.libraryAccessCount || 0) + Number(analysis?.classroomAccessCount || 0)) * 2),
        clamp(100 - Number(analysis?.lateReturnCount || 0) * 12 - (analysis?.absenteeFlag ? 20 : 0)),
        clamp(Number(analysis?.healthScore || 0))
    ];

    radarChart.setOption({
        tooltip: { trigger: 'item' },
        radar: {
            shape: 'polygon',
            radius: '70%',
            indicator: [
                { name: '学习投入', max: 100 },
                { name: '网络健康', max: 100 },
                { name: '校园参与', max: 100 },
                { name: '作息规律', max: 100 },
                { name: '综合健康', max: 100 }
            ],
            splitArea: { areaStyle: { color: [ANALYSIS_PALETTE.radarFillA, ANALYSIS_PALETTE.radarFillB] } },
            axisName: { color: ANALYSIS_PALETTE.secondary },
            splitLine: { lineStyle: { color: ANALYSIS_PALETTE.grid } }
        },
        series: [{
            type: 'radar',
            data: [{
                name: '用户行为画像',
                value: values,
                lineStyle: { color: ANALYSIS_PALETTE.primary, width: 2 },
                areaStyle: { color: ANALYSIS_PALETTE.radarArea },
                itemStyle: { color: ANALYSIS_PALETTE.primary }
            }]
        }]
    }, true);
    radarChart.resize();
}

function resetAnalysisView() {
    if (analysisElements.searchInput) {
        analysisElements.searchInput.value = '';
    }
    window.location.hash = '';
    toggleAnalysisContent(false);
}

function toggleAnalysisContent(show) {
    if (analysisElements.emptyState) {
        analysisElements.emptyState.style.display = show ? 'none' : 'block';
    }
    if (analysisElements.content) {
        analysisElements.content.style.display = show ? 'block' : 'none';
    }
    if (analysisElements.healthAlert && !show) {
        analysisElements.healthAlert.style.display = 'none';
        analysisElements.healthAlert.innerHTML = '';
    }
}

function parseProfileTags(rawTags) {
    if (!rawTags) {
        return {};
    }
    try {
        return JSON.parse(rawTags);
    } catch (error) {
        return {};
    }
}

function resolveTrendState(delta, improvedWhen) {
    const number = Number(delta || 0);
    if (Math.abs(number) < 0.0000001) {
        return 'flat';
    }
    if (improvedWhen === 'lower') {
        return number < 0 ? 'up' : 'down';
    }
    return number > 0 ? 'up' : 'down';
}

function resolveDimensionBadge(score) {
    const value = Number(score || 0);
    if (value >= 75) {
        return 'badge-success';
    }
    if (value >= 60) {
        return 'badge-warning';
    }
    return 'badge-danger';
}

function mapTimelineSeverity(severity) {
    switch (String(severity || '').toUpperCase()) {
        case 'DANGER':
            return 'danger';
        case 'WARNING':
            return 'warning';
        case 'SUCCESS':
            return 'success';
        default:
            return 'info';
    }
}

function mapWarningLevel(level) {
    switch (String(level || '').toUpperCase()) {
        case 'CRITICAL':
            return 'danger';
        case 'HIGH':
            return 'warning';
        case 'MEDIUM':
            return 'info';
        default:
            return 'success';
    }
}

function formatDelta(value, digits) {
    const number = Number(value || 0);
    const fixed = digits === 0 ? Math.round(number).toString() : number.toFixed(digits);
    return number > 0 ? `+${fixed}` : fixed;
}

function clamp(value) {
    return Math.max(0, Math.min(100, Number(value || 0)));
}

function formatTimelineTime(value) {
    if (!value) {
        return '-';
    }
    const date = new Date(value);
    return Number.isNaN(date.getTime()) ? String(value) : date.toLocaleString();
}

function formatDateTime(value) {
    if (!value) {
        return '-';
    }
    const date = new Date(value);
    return Number.isNaN(date.getTime()) ? '-' : date.toLocaleString();
}

function escapeHtml(value) {
    return String(value ?? '')
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
}
