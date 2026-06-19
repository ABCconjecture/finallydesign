let clusterDistributionChart = null;
let clusterRadarChart = null;
let clusterOverviewCache = [];
const PROFILE_PALETTE = {
    primary: '#c96a4a',
    accent: '#748c5f',
    secondary: '#8d7360',
    warning: '#cb9543',
    danger: '#b65d4c',
    text: '#77675a',
    grid: '#deccb8',
    radarFillA: 'rgba(201, 106, 74, 0.05)',
    radarFillB: 'rgba(201, 106, 74, 0.10)',
    radarArea: 'rgba(201, 106, 74, 0.20)'
};
const clusterPageState = {
    clusterId: null,
    page: 0,
    size: 10,
    total: 0,
    totalPages: 1
};

document.addEventListener('DOMContentLoaded', async () => {
    try {
        await auth.requireLogin();
        await auth.renderSessionBar();
        bindPagerEvents();
        await loadClusterDistribution();
        await restoreClusterFromHash();
        window.addEventListener('resize', () => {
            clusterDistributionChart && clusterDistributionChart.resize();
            clusterRadarChart && clusterRadarChart.resize();
        });
    } catch (error) {
        if (error.code === 401 || error.message === '请先登录') {
            auth.redirectToLogin('profile.html');
            return;
        }
        console.error('初始化用户画像页面失败:', error);
        alert(error.message || '用户画像页面加载失败');
    }
});

function bindPagerEvents() {
    const jumpInput = document.getElementById('clusterUsersJumpPageInput');
    if (!jumpInput) {
        return;
    }
    jumpInput.addEventListener('keydown', (event) => {
        if (event.key === 'Enter') {
            event.preventDefault();
            jumpClusterUsersPage();
        }
    });
}

async function loadClusterDistribution() {
    const dom = document.getElementById('clusterDistributionChart');
    if (!dom) {
        return;
    }

    clusterOverviewCache = await api.getClusterOverview();
    renderClusterCards(clusterOverviewCache);

    clusterDistributionChart = clusterDistributionChart || echarts.init(dom);
    clusterDistributionChart.setOption({
        tooltip: {
            trigger: 'axis',
            formatter(params) {
                const item = clusterOverviewCache[params[0]?.dataIndex || 0] || {};
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
            data: clusterOverviewCache.map((item) => item.label || '未命名群体'),
            axisLabel: { interval: 0, rotate: 15, color: PROFILE_PALETTE.text }
        },
        yAxis: {
            type: 'value',
            axisLabel: { color: PROFILE_PALETTE.text },
            splitLine: { lineStyle: { color: PROFILE_PALETTE.grid } }
        },
        series: [{
            type: 'bar',
            barWidth: '55%',
            data: clusterOverviewCache.map((item) => item.count || 0),
            itemStyle: {
                color(params) {
                    const colors = [PROFILE_PALETTE.primary, PROFILE_PALETTE.accent, PROFILE_PALETTE.warning, PROFILE_PALETTE.secondary, PROFILE_PALETTE.danger];
                    return colors[params.dataIndex % colors.length];
                },
                borderRadius: [6, 6, 0, 0]
            }
        }]
    });
}

function renderClusterCards(overview) {
    const cardContainer = document.getElementById('clusterOverviewCards');
    const buttonContainer = document.getElementById('clusterActionButtons');
    if (!cardContainer || !buttonContainer) {
        return;
    }

    cardContainer.innerHTML = overview.map((item) => `
        <div class="card cluster-overview-card">
            <div class="card-body">
                <div class="table-cell-main">${escapeHtml(item.label || '未命名群体')}</div>
                <div class="table-cell-sub">${escapeHtml(item.focus || '')}</div>
                <div class="cluster-overview-meta">
                    <span>用户数 ${item.count || 0}</span>
                    <span>预警率 ${Number(item.warningRate || 0).toFixed(2)}%</span>
                </div>
                <div class="cluster-overview-meta">
                    <span>平均健康度 ${Number(item.avgHealthScore || 0).toFixed(7)}</span>
                    <span>低健康 ${item.lowHealthUserCount || 0} 人</span>
                </div>
            </div>
        </div>
    `).join('');

    buttonContainer.innerHTML = overview.map((item) => `
        <button class="btn btn-primary cluster-switch-btn" data-cluster-id="${item.clusterId}">${escapeHtml(item.label || `群体 ${item.clusterId}`)}</button>
    `).join('');

    buttonContainer.querySelectorAll('.cluster-switch-btn').forEach((button) => {
        button.addEventListener('click', () => loadClusterProfile(Number(button.dataset.clusterId), true));
    });
}

async function restoreClusterFromHash() {
    const hash = window.location.hash.substring(1);
    const params = new URLSearchParams(hash);
    const clusterId = params.get('cluster');
    if (clusterId !== null) {
        await loadClusterProfile(Number(clusterId), true);
    } else if (clusterOverviewCache.length) {
        await loadClusterProfile(clusterOverviewCache[0].clusterId, true);
    }
}

async function loadClusterProfile(clusterId, resetPage = false) {
    if (resetPage || Number(clusterPageState.clusterId) !== Number(clusterId)) {
        clusterPageState.clusterId = clusterId;
        clusterPageState.page = 0;
    }

    try {
        const [response, insight] = await Promise.all([
            api.getClusterUsersPage(clusterId, {
                page: clusterPageState.page,
                size: clusterPageState.size
            }),
            api.getClusterInsight(clusterId)
        ]);
        const cluster = clusterOverviewCache.find((item) => Number(item.clusterId) === Number(clusterId));
        renderClusterSummary(cluster);
        renderClusterInsight(insight || {}, cluster);
        renderClusterUsers(response.records || []);
        syncClusterPager(response);
        highlightActiveCluster(clusterId);
        window.location.hash = `cluster=${clusterId}`;
    } catch (error) {
        console.error('加载聚类用户失败:', error);
        alert(error.message || '加载聚类用户失败，请稍后重试');
    }
}

function renderClusterSummary(cluster) {
    if (!cluster) {
        return;
    }
    setText('selectedClusterLabel', cluster.label || '未命名群体');
    setText('selectedClusterFocus', cluster.focus || '-');
    setText('selectedClusterCount', cluster.count || 0);
    setText('selectedClusterWarningRate', `预警覆盖率 ${Number(cluster.warningRate || 0).toFixed(2)}%`);
    setText('selectedClusterHealth', Number(cluster.avgHealthScore || 0).toFixed(7));
    setText('selectedClusterRisk', `平均风险 ${Number(cluster.avgRiskScore || 0).toFixed(2)}`);
    setText('selectedClusterWarnings', cluster.warningUserCount || 0);
    setText('selectedClusterStudy', `低健康用户 ${cluster.lowHealthUserCount || 0} 人 · 学习流量占比 ${(Number(cluster.studyTrafficRatio || 0) * 100).toFixed(2)}%`);
}

function renderClusterInsight(insight, cluster) {
    renderClusterRadar(insight.radar || null, cluster);
    renderClusterRiskSources(insight.riskSources || []);
    renderClusterTypicalSamples(insight.samples || [], cluster);
}

function renderClusterRadar(radar, cluster) {
    const dom = document.getElementById('clusterRadarChart');
    if (!dom || !window.echarts) {
        return;
    }
    clusterRadarChart = clusterRadarChart || echarts.init(dom);
    const labels = Array.isArray(radar?.labels) ? radar.labels : ['学习投入', '网络健康', '校园参与', '作息规律', '综合健康'];
    const values = Array.isArray(radar?.values) ? radar.values.map((item) => Number(item || 0)) : [0, 0, 0, 0, 0];
    const clusterLabel = cluster?.label || insightClusterLabelFallback(clusterPageState.clusterId);

    clusterRadarChart.setOption({
        tooltip: {
            trigger: 'item',
            formatter(params) {
                const lines = labels.map((label, index) => `${label}：${Number(values[index] || 0).toFixed(2)}`);
                return [clusterLabel, ...lines].join('<br>');
            }
        },
        radar: {
            indicator: labels.map((label) => ({ name: label, max: 100 })),
            splitArea: { areaStyle: { color: [PROFILE_PALETTE.radarFillA, PROFILE_PALETTE.radarFillB] } },
            splitLine: { lineStyle: { color: PROFILE_PALETTE.grid } },
            axisName: { color: PROFILE_PALETTE.secondary }
        },
        series: [{
            type: 'radar',
            data: [{
                value: values,
                name: clusterLabel,
                areaStyle: { color: PROFILE_PALETTE.radarArea },
                lineStyle: { color: PROFILE_PALETTE.primary, width: 3 },
                itemStyle: { color: PROFILE_PALETTE.primary }
            }]
        }]
    }, true);
}

function renderClusterRiskSources(riskSources) {
    const container = document.getElementById('clusterRiskSources');
    if (!container) {
        return;
    }
    if (!riskSources.length) {
        container.innerHTML = '<div class="table-empty-cell">当前群体暂无明显风险来源。</div>';
        return;
    }

    container.innerHTML = riskSources.map((item) => `
        <div class="compare-list-item detail-card">
            <div class="compare-list-head">
                <div class="table-cell-main">${escapeHtml(item.type || '-')}</div>
                <span class="badge badge-warning">${Number(item.percentage || 0).toFixed(2)}%</span>
            </div>
            <div class="compare-list-meta">
                <span>涉及人数 ${item.count || 0}</span>
                <span>占当前群体比例 ${Number(item.percentage || 0).toFixed(2)}%</span>
            </div>
            <div class="table-cell-sub">${escapeHtml(item.description || '')}</div>
        </div>
    `).join('');
}

function renderClusterTypicalSamples(samples, cluster) {
    const container = document.getElementById('clusterTypicalSamples');
    if (!container) {
        return;
    }
    if (!samples.length) {
        container.innerHTML = '<div class="table-empty-cell">当前群体暂无典型样本。</div>';
        return;
    }

    container.innerHTML = samples.map((sample) => {
        const healthClass = resolveHealthClass(sample.healthScore);
        return `
            <div class="attention-card sample-card">
                <div class="attention-card-head">
                    <div>
                        <div class="attention-card-title">${escapeHtml(sample.type || '典型样本')}</div>
                        <div class="table-cell-sub">${escapeHtml(sample.studentId || '-')} / ${escapeHtml(sample.name || '-')}</div>
                    </div>
                    <span class="badge badge-info">${escapeHtml(cluster?.label || '群体样本')}</span>
                </div>
                <div class="attention-card-metrics">
                    <span class="health-score ${healthClass}">${sample.healthScore != null ? Number(sample.healthScore).toFixed(7) : '--'}</span>
                    <span class="attention-card-risk">风险 ${sample.riskScore != null ? Number(sample.riskScore).toFixed(2) : '--'}</span>
                </div>
                <div class="table-cell-sub">${escapeHtml(sample.college || '-')} / ${escapeHtml(sample.major || '-')}</div>
                <div class="cluster-overview-meta">
                    <span>预警数 ${sample.warningCount || 0}</span>
                    <span>${escapeHtml(sample.topWarningType || '当前无开放预警')}</span>
                </div>
                <div class="attention-card-note">${escapeHtml(sample.reason || '适合作为当前群体的代表用户。')}</div>
                <div class="attention-card-actions">
                    <a class="btn btn-primary btn-small" href="analysis.html#user=${sample.userId}">分析详情</a>
                    <a class="btn btn-warning btn-small" href="warning.html#user=${sample.userId}">预警记录</a>
                </div>
            </div>
        `;
    }).join('');
}

function renderClusterUsers(users) {
    const tbody = document.getElementById('clusterUsersTable');
    if (!tbody) {
        return;
    }
    if (!users.length) {
        tbody.innerHTML = '<tr><td colspan="8" class="table-empty-cell">当前群体暂无用户数据。</td></tr>';
        return;
    }

    tbody.innerHTML = users.map((user) => {
        const healthClass = resolveHealthClass(user.healthScore);
        const reminder = Number(user.healthScore || 0) < 60 ? '<div class="table-cell-sub">低健康提醒，建议优先复核。</div>' : '';
        return `
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
                    <div class="table-cell-main">${escapeHtml(user.clusterLabel || '-')}</div>
                    <div class="table-cell-sub">${escapeHtml(user.topWarningType || '当前无开放预警')}</div>
                </td>
                <td>
                    <div class="table-cell-main"><span class="health-score ${healthClass}">${user.healthScore != null ? Number(user.healthScore).toFixed(7) : '--'}</span></div>
                    ${reminder}
                </td>
                <td>${user.riskScore != null ? Number(user.riskScore).toFixed(2) : '--'}</td>
                <td>${user.warningCount || 0}</td>
                <td><span class="status-chip ${mapStatusClass(user.statusLabel)}">${escapeHtml(user.statusLabel || '-')}</span></td>
                <td title="${escapeHtml(user.clusterSummary || '')}">${escapeHtml(user.clusterSummary || '-')}</td>
            </tr>
        `;
    }).join('');
}

function syncClusterPager(response) {
    clusterPageState.page = Number(response.page || 0);
    clusterPageState.size = Number(response.size || clusterPageState.size);
    clusterPageState.total = Number(response.total || 0);
    clusterPageState.totalPages = Math.max(1, Number(response.totalPages || 1));

    const pageInfo = document.getElementById('clusterUsersPageInfo');
    const prevButton = document.getElementById('clusterUsersPrevButton');
    const nextButton = document.getElementById('clusterUsersNextButton');

    if (pageInfo) {
        pageInfo.textContent = `第 ${clusterPageState.page + 1} 页 / 共 ${clusterPageState.totalPages} 页，合计 ${clusterPageState.total} 条`;
    }
    if (prevButton) {
        prevButton.disabled = clusterPageState.page <= 0;
    }
    if (nextButton) {
        nextButton.disabled = clusterPageState.page >= clusterPageState.totalPages - 1 || clusterPageState.total === 0;
    }
}

function prevClusterUsersPage() {
    if (clusterPageState.clusterId == null || clusterPageState.page <= 0) {
        return;
    }
    clusterPageState.page -= 1;
    loadClusterProfile(clusterPageState.clusterId);
}

function nextClusterUsersPage() {
    if (clusterPageState.clusterId == null || clusterPageState.page >= clusterPageState.totalPages - 1) {
        return;
    }
    clusterPageState.page += 1;
    loadClusterProfile(clusterPageState.clusterId);
}

function jumpClusterUsersPage() {
    const input = document.getElementById('clusterUsersJumpPageInput');
    if (!input || clusterPageState.clusterId == null) {
        return;
    }

    const pageNumber = Number(input.value);
    if (!Number.isInteger(pageNumber) || pageNumber < 1 || pageNumber > clusterPageState.totalPages) {
        return;
    }

    clusterPageState.page = pageNumber - 1;
    loadClusterProfile(clusterPageState.clusterId);
}

function highlightActiveCluster(clusterId) {
    document.querySelectorAll('.cluster-switch-btn').forEach((button) => {
        const active = Number(button.dataset.clusterId) === Number(clusterId);
        button.classList.toggle('btn-success', active);
        button.classList.toggle('btn-primary', !active);
    });
}

function insightClusterLabelFallback(clusterId) {
    const cluster = clusterOverviewCache.find((item) => Number(item.clusterId) === Number(clusterId));
    return cluster?.label || '群体画像';
}

function mapStatusClass(statusLabel) {
    if (statusLabel === '重点预警') {
        return 'danger';
    }
    if (statusLabel === '持续关注') {
        return 'warning';
    }
    if (statusLabel === '轻度波动') {
        return 'info';
    }
    return 'success';
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
