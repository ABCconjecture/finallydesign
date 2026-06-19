const TASK_LOG_PAGE_SIZE = 5;
const TASK_AUDIT_PAGE_SIZE = 5;
const taskDetailState = new Map();

document.addEventListener('DOMContentLoaded', async () => {
    try {
        const currentUser = await auth.requireLogin();
        await auth.renderSessionBar();
        bindActions(currentUser);
        await loadTaskStatus(currentUser);
    } catch (error) {
        if (error.code === 401) {
            auth.redirectToLogin('tasks.html');
            return;
        }
        console.error('加载任务状态失败', error);
        window.alert(error.message || '任务状态加载失败');
    }
});

async function loadTaskStatus(currentUser = null) {
    const user = currentUser || await auth.getCurrentUser(true);
    const data = await api.getTaskStatus();
    renderSchedulerSummary(data.scheduler || {});
    renderClusterNodes((data.scheduler && data.scheduler.clusterNodes) || []);
    renderTaskTable(data.tasks || [], auth.isAdmin(user));
}

function bindActions(currentUser) {
    const refreshButton = document.getElementById('refreshTaskStatusButton');
    if (refreshButton) {
        refreshButton.addEventListener('click', async () => {
            refreshButton.disabled = true;
            try {
                await loadTaskStatus(currentUser);
                showTaskMessage('任务状态已刷新', 'info');
            } catch (error) {
                showTaskMessage(error.message || '刷新任务状态失败', 'danger');
            } finally {
                refreshButton.disabled = false;
            }
        });
    }

    const tbody = document.getElementById('taskStatusTable');
    if (!tbody) {
        return;
    }

    tbody.addEventListener('click', async (event) => {
        const detailToggle = event.target.closest('button[data-detail-toggle]');
        if (detailToggle) {
            await toggleTaskDetail(detailToggle.dataset.task);
            return;
        }

        const detailActionButton = event.target.closest('button[data-detail-action][data-task]');
        if (detailActionButton) {
            await handleDetailAction(
                detailActionButton.dataset.task,
                detailActionButton.dataset.detailAction,
                detailActionButton.dataset.auditId,
                detailActionButton.dataset.range
            );
            return;
        }

        const actionButton = event.target.closest('button[data-action][data-task]');
        if (!actionButton) {
            return;
        }

        const taskKey = actionButton.dataset.task;
        const action = actionButton.dataset.action;
        const actionLabel = actionButton.dataset.label || '执行操作';

        if (!auth.isAdmin(currentUser)) {
            showTaskMessage('当前账号为查看员，只能查看任务状态，不能执行控制操作', 'warning');
            return;
        }

        const confirmMessage = action === 'retry'
            ? buildRetryConfirmMessage(taskKey, actionLabel)
            : `确认要${actionLabel}吗？`;
        const confirmed = window.confirm(confirmMessage);
        if (!confirmed) {
            return;
        }

        const actionPayload = action === 'retry' ? collectRetryPayload(taskKey) : null;
        if (action === 'retry' && !actionPayload) {
            return;
        }

        setTaskButtonsDisabled(taskKey, true);
        try {
            await performTaskAction(taskKey, action, actionPayload);
            await loadTaskStatus(currentUser);
            await refreshOpenDetail(taskKey, true);
            showTaskMessage(`已${actionLabel}`, 'success');
        } catch (error) {
            const message = error.code === 403
                ? '当前账号没有管理员权限，无法控制任务'
                : (error.message || `${actionLabel}失败`);
            showTaskMessage(message, 'danger');
        } finally {
            setTaskButtonsDisabled(taskKey, false);
        }
    });
}

async function handleDetailAction(taskKey, action, auditId = null, rangeKey = null) {
    const state = ensureTaskState(taskKey);
    if (!state) {
        return;
    }

    switch (action) {
        case 'log-refresh':
            await loadTaskLogPage(taskKey, state.logs.page || 0, true);
            return;
        case 'log-prev':
            await loadTaskLogPage(taskKey, Math.max(0, (state.logs.page || 0) - 1), true);
            return;
        case 'log-next':
            await loadTaskLogPage(taskKey, (state.logs.page || 0) + 1, true);
            return;
        case 'audit-refresh':
            await loadTaskAuditPage(taskKey, state.audits.page || 0, true);
            return;
        case 'audit-prev':
            await loadTaskAuditPage(taskKey, Math.max(0, (state.audits.page || 0) - 1), true);
            return;
        case 'audit-next':
            await loadTaskAuditPage(taskKey, (state.audits.page || 0) + 1, true);
            return;
        case 'audit-apply':
            applyAuditFilters(taskKey);
            await loadTaskAuditPage(taskKey, 0, true);
            return;
        case 'audit-reset':
            resetAuditFilters(taskKey);
            await loadTaskAuditPage(taskKey, 0, true);
            return;
        case 'audit-range':
            applyAuditQuickRange(taskKey, rangeKey);
            await loadTaskAuditPage(taskKey, 0, true);
            return;
        case 'audit-range-hours':
            if (applyAuditRecentHoursRange(taskKey)) {
                await loadTaskAuditPage(taskKey, 0, true);
            }
            return;
        case 'audit-export':
            await exportTaskAudits(taskKey);
            await loadTaskAuditPage(taskKey, state.audits.page || 0, false);
            return;
        case 'export':
            await downloadTaskLogs(taskKey);
            await loadTaskAuditPage(taskKey, state.audits.page || 0, false);
            return;
        case 'copy-stack':
            await copyLatestFailure(taskKey);
            await loadTaskAuditPage(taskKey, state.audits.page || 0, false);
            return;
        case 'audit-copy-detail':
            await copyTaskAuditDetail(taskKey, auditId);
            await loadTaskAuditPage(taskKey, state.audits.page || 0, false);
            return;
        default:
            console.warn('未知详情操作', action);
    }
}

async function performTaskAction(taskKey, action, payload = null) {
    switch (action) {
        case 'trigger':
            return api.triggerTask(taskKey);
        case 'retry':
            return api.retryTask(taskKey, payload || {});
        case 'pause':
            return api.pauseTask(taskKey);
        case 'resume':
            return api.resumeTask(taskKey);
        default:
            throw new Error(`未知任务操作: ${action}`);
    }
}

function buildRetryConfirmMessage(taskKey, actionLabel) {
    const state = ensureTaskState(taskKey);
    const task = state ? state.summary : null;
    const latestFailure = state && state.logs && state.logs.latestFailure
        ? state.logs.latestFailure
        : findLatestFailure((task && task.recentLogs) || []);
    const segments = [
        `确认要${actionLabel}吗？`
    ];

    if (task && task.taskName) {
        segments.push(`任务：${task.taskName}`);
    }
    if (latestFailure && latestFailure.message) {
        segments.push(`最近失败：${latestFailure.message}`);
    }
    if (task && task.lastCompletedTime) {
        segments.push(`上次完成：${formatDateTime(task.lastCompletedTime)}`);
    }
    if (task && task.consecutiveFailureCount !== undefined && task.retryMaxConsecutiveFailures !== undefined) {
        segments.push(`连续失败：${valueOrZero(task.consecutiveFailureCount)} / ${valueOrZero(task.retryMaxConsecutiveFailures)}`);
    }
    segments.push('请确认失败原因已经处理完成，再提交重试。');
    return segments.join('\n');
}

function collectRetryPayload(taskKey) {
    const state = ensureTaskState(taskKey);
    const task = state ? state.summary : null;
    const latestFailure = state && state.logs && state.logs.latestFailure
        ? state.logs.latestFailure
        : findLatestFailure((task && task.recentLogs) || []);

    const reasonSegments = ['请输入本次失败重试原因（必填）'];
    if (task && task.taskName) {
        reasonSegments.push(`任务：${task.taskName}`);
    }
    if (latestFailure && latestFailure.message) {
        reasonSegments.push(`最近失败：${latestFailure.message}`);
    }
    const retryReasonInput = window.prompt(reasonSegments.join('\n'), '');
    if (retryReasonInput === null) {
        showTaskMessage('已取消失败重试提交', 'info');
        return null;
    }

    const retryReason = String(retryReasonInput || '').trim();
    if (!retryReason) {
        showTaskMessage('请填写重试原因后再提交', 'warning');
        return null;
    }

    const approvalSegments = ['请输入审批备注（选填，直接确定可留空）'];
    if (task && task.taskName) {
        approvalSegments.push(`任务：${task.taskName}`);
    }
    approvalSegments.push('备注会和重试原因一起写入任务审计。');
    const approvalNoteInput = window.prompt(approvalSegments.join('\n'), '');
    if (approvalNoteInput === null) {
        showTaskMessage('已取消失败重试提交', 'info');
        return null;
    }

    return {
        retryReason,
        approvalNote: String(approvalNoteInput || '').trim()
    };
}

function applyAuditQuickRange(taskKey, rangeKey) {
    const state = ensureTaskState(taskKey);
    if (!state) {
        return;
    }

    const now = new Date();
    let start = null;
    let end = now;
    switch (String(rangeKey || '').toLowerCase()) {
        case 'today':
            start = new Date(now.getFullYear(), now.getMonth(), now.getDate(), 0, 0, 0, 0);
            break;
        case '7d':
            start = new Date(now.getTime() - (7 * 24 * 60 * 60 * 1000));
            break;
        case '30d':
            start = new Date(now.getTime() - (30 * 24 * 60 * 60 * 1000));
            break;
        default:
            return;
    }

    state.audits.startTime = formatDateTimeLocalValue(start);
    state.audits.endTime = formatDateTimeLocalValue(end);
    state.audits.recentHours = '';
}

function applyAuditRecentHoursRange(taskKey) {
    const state = ensureTaskState(taskKey);
    if (!state) {
        return false;
    }

    const hoursInput = document.getElementById(`audit-recent-hours-${taskKey}`);
    const rawValue = hoursInput ? String(hoursInput.value || '').trim() : '';
    const recentHours = Number(rawValue);
    if (!rawValue || !Number.isFinite(recentHours) || recentHours <= 0) {
        showTaskMessage('请输入大于 0 的最近小时数', 'warning');
        return false;
    }

    const normalizedHours = Math.min(Math.floor(recentHours), 24 * 90);
    const end = new Date();
    const start = new Date(end.getTime() - (normalizedHours * 60 * 60 * 1000));
    state.audits.recentHours = String(normalizedHours);
    state.audits.startTime = formatDateTimeLocalValue(start);
    state.audits.endTime = formatDateTimeLocalValue(end);
    return true;
}

function renderSchedulerSummary(scheduler) {
    const container = document.getElementById('schedulerSummary');
    if (!container) {
        return;
    }

    container.innerHTML = `
        <div class="metric-card info">
            <div class="metric-label">调度器名称</div>
            <div class="metric-value">${escapeHtml(scheduler.schedulerName || 'campusScheduler')}</div>
            <div class="metric-trend">实例 ID：${escapeHtml(scheduler.schedulerInstanceId || 'AUTO')}</div>
        </div>
        <div class="metric-card ${scheduler.persistent ? 'success' : 'warning'}">
            <div class="metric-label">JobStore</div>
            <div class="metric-value">${scheduler.persistent ? 'JDBC' : 'MEMORY'}</div>
            <div class="metric-trend">${escapeHtml(scheduler.jobStoreClass || '-')}</div>
        </div>
        <div class="metric-card ${scheduler.clustered ? 'success' : 'warning'}">
            <div class="metric-label">集群模式</div>
            <div class="metric-value">${scheduler.clustered ? '已开启' : '未开启'}</div>
            <div class="metric-trend">当前节点：${escapeHtml(scheduler.nodeId || '-')}</div>
        </div>
        <div class="metric-card ${scheduler.started ? 'success' : 'danger'}">
            <div class="metric-label">调度器状态</div>
            <div class="metric-value">${scheduler.started ? '运行中' : (scheduler.shutdown ? '已关闭' : '待机')}</div>
            <div class="metric-trend">累计执行：${valueOrZero(scheduler.numberOfJobsExecuted)} 次</div>
        </div>
    `;
}

function renderClusterNodes(nodes) {
    const tbody = document.getElementById('clusterNodeTable');
    if (!tbody) {
        return;
    }

    if (!nodes.length) {
        tbody.innerHTML = '<tr><td colspan="4" style="text-align: center; color: var(--text-light);">当前仅检测到本节点，或调度器状态表尚未产生新的心跳记录。</td></tr>';
        return;
    }

    tbody.innerHTML = nodes.map((node) => `
        <tr>
            <td><span class="mono">${escapeHtml(node.instanceName || '-')}</span></td>
            <td>${formatDateTime(node.lastCheckinTime)}</td>
            <td>${formatDurationMs(node.checkinInterval)}</td>
            <td><span class="badge ${node.alive ? 'badge-success' : 'badge-danger'}">${node.alive ? '在线' : '异常'}</span></td>
        </tr>
    `).join('');
}

function renderTaskTable(tasks, isAdmin) {
    const tbody = document.getElementById('taskStatusTable');
    const toolbarNote = document.getElementById('taskToolbarNote');
    if (!tbody) {
        return;
    }

    if (toolbarNote) {
        toolbarNote.textContent = isAdmin
            ? '当前为管理员视角，可手动执行、失败重试、暂停或恢复任务，并按页查看日志与操作审计。'
            : '当前为查看员视角，可查看任务状态、失败原因、分页日志与操作审计，控制类按钮仅管理员可用。';
    }

    if (!tasks.length) {
        tbody.innerHTML = '<tr><td colspan="9" style="text-align: center; color: var(--text-light);">暂无任务状态数据</td></tr>';
        return;
    }

    tbody.innerHTML = tasks.map((task) => {
        syncTaskState(task);
        const lastStatus = task.lastStatus || 'PENDING';
        const latestFailure = findLatestFailure(task.recentLogs || []);
        const summaryText = task.lastMessage || '暂无执行摘要';
        const retryGuardNote = task.retryGuardReason && !task.canRetry
            ? `<div class="task-retry-note">重试限制：${escapeHtml(task.retryGuardReason)}</div>`
            : '';
        return `
            <tr>
                <td>
                    <div><strong>${escapeHtml(task.taskName || '-')}</strong></div>
                    <div class="task-subtitle">${escapeHtml(task.taskKey || '-')}</div>
                    ${task.executing ? '<div class="badge badge-info task-running-badge">执行中</div>' : ''}
                </td>
                <td><code>${escapeHtml(task.cronExpression || '-')}</code></td>
                <td><span class="badge ${getTriggerBadgeClass(task.triggerState)}">${escapeHtml(task.triggerState || 'UNKNOWN')}</span></td>
                <td><span class="badge ${getStatusBadgeClass(lastStatus)}">${escapeHtml(lastStatus)}</span></td>
                <td>${valueOrZero(task.lastProcessedCount)}</td>
                <td>${formatDateTime(task.lastCompletedTime)}</td>
                <td>${formatDateTime(task.nextFireTime)}</td>
                <td>
                    <div>${escapeHtml(summaryText)}</div>
                    ${latestFailure ? `<div class="task-error-summary">最近失败：${escapeHtml(latestFailure.message || '未知原因')}</div>` : ''}
                    ${retryGuardNote}
                </td>
                <td>${renderTaskActions(task, isAdmin)}</td>
            </tr>
            <tr class="task-detail-row" id="task-detail-${escapeHtml(task.taskKey)}" hidden>
                <td colspan="9">
                    <div class="task-detail" id="task-detail-content-${escapeHtml(task.taskKey)}">
                        <div class="task-empty-state">点击“展开详情”后加载分页日志与操作审计。</div>
                    </div>
                </td>
            </tr>
        `;
    }).join('');
}

function syncTaskState(task) {
    const previousState = taskDetailState.get(task.taskKey) || {};
    taskDetailState.set(task.taskKey, {
        taskKey: task.taskKey,
        summary: task,
        logs: {
            page: previousState.logs ? previousState.logs.page : 0,
            size: previousState.logs ? previousState.logs.size : TASK_LOG_PAGE_SIZE,
            totalPages: previousState.logs ? previousState.logs.totalPages : 0,
            totalElements: previousState.logs ? previousState.logs.totalElements : 0,
            items: previousState.logs ? previousState.logs.items : [],
            latestFailure: previousState.logs ? previousState.logs.latestFailure : findLatestFailure(task.recentLogs || []),
            loaded: previousState.logs ? previousState.logs.loaded : false,
            loading: false
        },
        audits: {
            page: previousState.audits ? previousState.audits.page : 0,
            size: previousState.audits ? previousState.audits.size : TASK_AUDIT_PAGE_SIZE,
            totalPages: previousState.audits ? previousState.audits.totalPages : 0,
            totalElements: previousState.audits ? previousState.audits.totalElements : 0,
            items: previousState.audits ? previousState.audits.items : [],
            action: previousState.audits ? (previousState.audits.action || '') : '',
            operator: previousState.audits ? (previousState.audits.operator || '') : '',
            result: previousState.audits ? (previousState.audits.result || '') : '',
            startTime: previousState.audits ? (previousState.audits.startTime || '') : '',
            endTime: previousState.audits ? (previousState.audits.endTime || '') : '',
            recentHours: previousState.audits ? (previousState.audits.recentHours || '') : '',
            loaded: previousState.audits ? previousState.audits.loaded : false,
            loading: false
        }
    });
}

function renderTaskActions(task, isAdmin) {
    const detailButton = `<button type="button" class="btn btn-info btn-small" data-detail-toggle="true" data-task="${escapeHtml(task.taskKey)}">展开详情</button>`;

    if (!isAdmin) {
        return `<div class="task-actions">${detailButton}<span class="task-readonly">只读</span></div>`;
    }

    const triggerDisabled = !task.canTrigger || task.executing;
    const retryDisabled = !task.canRetry || task.executing;
    const pauseDisabled = !task.canPause || task.executing;
    const resumeDisabled = !task.canResume || task.executing;
    const retryTitle = task.retryGuardReason ? ` title="${escapeHtml(task.retryGuardReason)}"` : '';

    return `
        <div class="task-actions">
            <button type="button" class="btn btn-primary btn-small" data-action="trigger" data-label="手动执行 ${escapeHtml(task.taskName)}" data-task="${escapeHtml(task.taskKey)}" ${triggerDisabled ? 'disabled' : ''}>手动执行</button>
            <button type="button" class="btn btn-danger btn-small" data-action="retry" data-label="失败重试 ${escapeHtml(task.taskName)}" data-task="${escapeHtml(task.taskKey)}" ${retryDisabled ? 'disabled' : ''}${retryTitle}>失败重试</button>
            <button type="button" class="btn btn-warning btn-small" data-action="pause" data-label="暂停 ${escapeHtml(task.taskName)}" data-task="${escapeHtml(task.taskKey)}" ${pauseDisabled ? 'disabled' : ''}>暂停</button>
            <button type="button" class="btn btn-success btn-small" data-action="resume" data-label="恢复 ${escapeHtml(task.taskName)}" data-task="${escapeHtml(task.taskKey)}" ${resumeDisabled ? 'disabled' : ''}>恢复</button>
            ${detailButton}
        </div>
    `;
}

async function toggleTaskDetail(taskKey) {
    const row = document.getElementById(`task-detail-${taskKey}`);
    if (!row) {
        return;
    }

    row.hidden = !row.hidden;
    updateDetailToggleLabel(taskKey, row.hidden);

    if (row.hidden) {
        return;
    }

    const state = ensureTaskState(taskKey);
    if (!state) {
        return;
    }

    if (!state.logs.loaded || !state.audits.loaded) {
        await Promise.all([
            loadTaskLogPage(taskKey, state.logs.page || 0, false),
            loadTaskAuditPage(taskKey, state.audits.page || 0, false)
        ]);
    } else {
        renderTaskDetailContent(taskKey);
    }
}

async function refreshOpenDetail(taskKey, showStatusMessage) {
    const row = document.getElementById(`task-detail-${taskKey}`);
    if (!row || row.hidden) {
        return;
    }

    const state = ensureTaskState(taskKey);
    if (!state) {
        return;
    }

    await Promise.all([
        loadTaskLogPage(taskKey, state.logs.page || 0, false),
        loadTaskAuditPage(taskKey, state.audits.page || 0, false)
    ]);

    if (showStatusMessage) {
        showTaskMessage(`${state.summary.taskName} 的详情已刷新`, 'info');
    }
}

async function loadTaskLogPage(taskKey, page, showStatusMessage) {
    const state = ensureTaskState(taskKey);
    if (!state) {
        return;
    }

    state.logs.loading = true;
    renderTaskDetailContent(taskKey);

    try {
        const data = await api.getTaskLogs(taskKey, { page, size: state.logs.size || TASK_LOG_PAGE_SIZE });
        state.logs.page = data.page || 0;
        state.logs.size = data.size || TASK_LOG_PAGE_SIZE;
        state.logs.totalPages = data.totalPages || 0;
        state.logs.totalElements = data.totalElements || 0;
        state.logs.items = data.content || [];
        state.logs.latestFailure = data.latestFailure || null;
        state.logs.loaded = true;
        if (showStatusMessage) {
            showTaskMessage(`已加载 ${state.summary.taskName} 的第 ${state.logs.page + 1} 页执行日志`, 'info');
        }
    } catch (error) {
        showTaskMessage(error.message || '任务日志加载失败', 'danger');
    } finally {
        state.logs.loading = false;
        renderTaskDetailContent(taskKey);
    }
}

async function loadTaskAuditPage(taskKey, page, showStatusMessage) {
    const state = ensureTaskState(taskKey);
    if (!state) {
        return;
    }

    state.audits.loading = true;
    renderTaskDetailContent(taskKey);

    try {
        const data = await api.getTaskAudits(taskKey, {
            page,
            size: state.audits.size || TASK_AUDIT_PAGE_SIZE,
            action: state.audits.action,
            operator: state.audits.operator,
            result: state.audits.result,
            startTime: state.audits.startTime,
            endTime: state.audits.endTime
        });
        state.audits.page = data.page || 0;
        state.audits.size = data.size || TASK_AUDIT_PAGE_SIZE;
        state.audits.totalPages = data.totalPages || 0;
        state.audits.totalElements = data.totalElements || 0;
        state.audits.items = data.content || [];
        state.audits.action = data.action || '';
        state.audits.operator = data.operatorKeyword || '';
        state.audits.result = data.resultStatus || '';
        state.audits.startTime = data.startTime || '';
        state.audits.endTime = data.endTime || '';
        state.audits.loaded = true;
        if (showStatusMessage) {
            showTaskMessage(`已加载 ${state.summary.taskName} 的第 ${state.audits.page + 1} 页操作审计`, 'info');
        }
    } catch (error) {
        showTaskMessage(error.message || '操作审计加载失败', 'danger');
    } finally {
        state.audits.loading = false;
        renderTaskDetailContent(taskKey);
    }
}

function renderTaskDetailContent(taskKey) {
    const container = document.getElementById(`task-detail-content-${taskKey}`);
    const state = ensureTaskState(taskKey);
    if (!container || !state) {
        return;
    }

    if (state.logs.loading && !state.logs.loaded && state.audits.loading && !state.audits.loaded) {
        container.innerHTML = `
            <div class="task-loading-block">
                <div class="spinner"></div>
                <div class="task-empty-state">正在加载分页日志与操作审计...</div>
            </div>
        `;
        return;
    }

    const task = state.summary;
    const latestFailure = state.logs.latestFailure;
    const logPageText = state.logs.totalPages > 0
        ? `第 ${state.logs.page + 1} / ${state.logs.totalPages} 页，共 ${state.logs.totalElements} 条日志`
        : '暂无历史执行日志';
    const auditPageText = state.audits.totalPages > 0
        ? `第 ${state.audits.page + 1} / ${state.audits.totalPages} 页，共 ${state.audits.totalElements} 条审计`
        : '暂无操作审计记录';
    const retryStateText = task.retryCooldownRemainingSeconds > 0
        ? `${task.retryCooldownRemainingSeconds} 秒后可重试`
        : (task.canRetry ? '当前可重试' : (task.retryGuardReason || '当前不可重试'));

    container.innerHTML = `
        <div class="task-detail-grid">
            <div>
                <div class="task-detail-label">任务说明</div>
                <div>${escapeHtml(task.description || '-')}</div>
            </div>
            <div>
                <div class="task-detail-label">最近开始</div>
                <div>${formatDateTime(task.lastStartedTime)}</div>
            </div>
            <div>
                <div class="task-detail-label">最近完成</div>
                <div>${formatDateTime(task.lastCompletedTime)}</div>
            </div>
            <div>
                <div class="task-detail-label">最近失败原因</div>
                <div>${latestFailure ? escapeHtml(latestFailure.message || '未知原因') : '最近没有失败记录'}</div>
            </div>
            <div>
                <div class="task-detail-label">连续失败次数 / 上限</div>
                <div>${valueOrZero(task.consecutiveFailureCount)} / ${valueOrZero(task.retryMaxConsecutiveFailures)}</div>
            </div>
            <div>
                <div class="task-detail-label">重试冷却</div>
                <div>${escapeHtml(retryStateText)}</div>
            </div>
        </div>
        ${task.retryGuardReason ? `<div class="task-empty-state task-retry-note-block">当前重试状态：${escapeHtml(task.retryGuardReason)}</div>` : ''}
        <div class="task-detail-section">
            <div class="task-detail-toolbar">
                <div class="task-pager">
                    <span>${logPageText}</span>
                    <button type="button" class="btn btn-secondary btn-small" data-detail-action="log-prev" data-task="${escapeHtml(taskKey)}" ${(state.logs.page <= 0 || state.logs.totalPages <= 1) ? 'disabled' : ''}>上一页</button>
                    <button type="button" class="btn btn-secondary btn-small" data-detail-action="log-next" data-task="${escapeHtml(taskKey)}" ${(!state.logs.totalPages || state.logs.page >= state.logs.totalPages - 1) ? 'disabled' : ''}>下一页</button>
                    <button type="button" class="btn btn-secondary btn-small" data-detail-action="log-refresh" data-task="${escapeHtml(taskKey)}">刷新日志</button>
                </div>
                <div class="task-log-actions">
                    <button type="button" class="btn btn-primary btn-small" data-detail-action="export" data-task="${escapeHtml(taskKey)}">导出日志</button>
                    <button type="button" class="btn btn-warning btn-small" data-detail-action="copy-stack" data-task="${escapeHtml(taskKey)}" ${(!latestFailure || !latestFailure.detail) ? 'disabled' : ''}>复制失败堆栈</button>
                </div>
            </div>
            ${latestFailure && latestFailure.detail ? `
                <div class="task-detail-section task-inline-stack">
                    <div class="task-detail-label">最近失败堆栈</div>
                    <pre class="task-detail-pre">${escapeHtml(latestFailure.detail)}</pre>
                </div>
            ` : ''}
            <div class="task-detail-label">分页执行日志</div>
            ${renderTaskLogTable(state.logs.items)}
        </div>
        <div class="task-detail-section">
            <div class="task-detail-toolbar">
                <div class="task-pager">
                    <span>${auditPageText}</span>
                    <button type="button" class="btn btn-secondary btn-small" data-detail-action="audit-prev" data-task="${escapeHtml(taskKey)}" ${(state.audits.page <= 0 || state.audits.totalPages <= 1) ? 'disabled' : ''}>上一页</button>
                    <button type="button" class="btn btn-secondary btn-small" data-detail-action="audit-next" data-task="${escapeHtml(taskKey)}" ${(!state.audits.totalPages || state.audits.page >= state.audits.totalPages - 1) ? 'disabled' : ''}>下一页</button>
                    <button type="button" class="btn btn-secondary btn-small" data-detail-action="audit-refresh" data-task="${escapeHtml(taskKey)}">刷新审计</button>
                </div>
                <div class="task-log-actions">
                    <button type="button" class="btn btn-primary btn-small" data-detail-action="audit-export" data-task="${escapeHtml(taskKey)}">导出审计</button>
                </div>
            </div>
            <div class="task-filter-grid">
                <div class="form-group">
                    <label class="task-detail-label" for="audit-action-${escapeHtml(taskKey)}">操作类型</label>
                    <select class="form-control" id="audit-action-${escapeHtml(taskKey)}">
                        <option value="">全部</option>
                        <option value="TRIGGER" ${state.audits.action === 'TRIGGER' ? 'selected' : ''}>手动执行</option>
                        <option value="RETRY" ${state.audits.action === 'RETRY' ? 'selected' : ''}>失败重试</option>
                        <option value="PAUSE" ${state.audits.action === 'PAUSE' ? 'selected' : ''}>暂停</option>
                        <option value="RESUME" ${state.audits.action === 'RESUME' ? 'selected' : ''}>恢复</option>
                        <option value="EXPORT_LOG" ${state.audits.action === 'EXPORT_LOG' ? 'selected' : ''}>导出日志</option>
                        <option value="EXPORT_AUDIT" ${state.audits.action === 'EXPORT_AUDIT' ? 'selected' : ''}>导出审计</option>
                        <option value="COPY_FAILURE_STACK" ${state.audits.action === 'COPY_FAILURE_STACK' ? 'selected' : ''}>复制堆栈</option>
                        <option value="COPY_AUDIT_DETAIL" ${state.audits.action === 'COPY_AUDIT_DETAIL' ? 'selected' : ''}>复制审计</option>
                    </select>
                </div>
                <div class="form-group">
                    <label class="task-detail-label" for="audit-result-${escapeHtml(taskKey)}">结果状态</label>
                    <select class="form-control" id="audit-result-${escapeHtml(taskKey)}">
                        <option value="">全部</option>
                        <option value="SUCCESS" ${state.audits.result === 'SUCCESS' ? 'selected' : ''}>成功</option>
                        <option value="FAILED" ${state.audits.result === 'FAILED' ? 'selected' : ''}>失败</option>
                    </select>
                </div>
                <div class="form-group">
                    <label class="task-detail-label" for="audit-operator-${escapeHtml(taskKey)}">操作人</label>
                    <input class="form-control" id="audit-operator-${escapeHtml(taskKey)}" type="text" placeholder="输入用户名或显示名" value="${escapeHtml(state.audits.operator || '')}">
                </div>
                <div class="form-group">
                    <label class="task-detail-label" for="audit-start-time-${escapeHtml(taskKey)}">开始时间</label>
                    <input class="form-control" id="audit-start-time-${escapeHtml(taskKey)}" type="datetime-local" value="${escapeHtml(state.audits.startTime || '')}">
                </div>
                <div class="form-group">
                    <label class="task-detail-label" for="audit-end-time-${escapeHtml(taskKey)}">结束时间</label>
                    <input class="form-control" id="audit-end-time-${escapeHtml(taskKey)}" type="datetime-local" value="${escapeHtml(state.audits.endTime || '')}">
                </div>
                <div class="form-group">
                    <label class="task-detail-label">快捷时间</label>
                    <div class="task-quick-range-actions">
                        <button type="button" class="btn btn-secondary btn-small" data-detail-action="audit-range" data-range="today" data-task="${escapeHtml(taskKey)}">今天</button>
                        <button type="button" class="btn btn-secondary btn-small" data-detail-action="audit-range" data-range="7d" data-task="${escapeHtml(taskKey)}">近7天</button>
                        <button type="button" class="btn btn-secondary btn-small" data-detail-action="audit-range" data-range="30d" data-task="${escapeHtml(taskKey)}">近30天</button>
                    </div>
                </div>
                <div class="form-group">
                    <label class="task-detail-label" for="audit-recent-hours-${escapeHtml(taskKey)}">自定义最近 N 小时</label>
                    <div class="task-quick-range-actions">
                        <input class="form-control task-hours-input" id="audit-recent-hours-${escapeHtml(taskKey)}" type="number" min="1" max="2160" step="1" placeholder="例如 6 / 12 / 48" value="${escapeHtml(state.audits.recentHours || '')}">
                        <button type="button" class="btn btn-secondary btn-small" data-detail-action="audit-range-hours" data-task="${escapeHtml(taskKey)}">应用</button>
                    </div>
                </div>
                <div class="task-filter-actions">
                    <button type="button" class="btn btn-primary btn-small" data-detail-action="audit-apply" data-task="${escapeHtml(taskKey)}">筛选</button>
                    <button type="button" class="btn btn-secondary btn-small" data-detail-action="audit-reset" data-task="${escapeHtml(taskKey)}">重置</button>
                </div>
            </div>
            <div class="task-detail-label">操作审计记录</div>
            ${renderTaskAuditTable(taskKey, state.audits.items)}
        </div>
    `;
}

function renderTaskLogTable(logs) {
    if (!logs.length) {
        return '<div class="task-empty-state">当前页暂无执行日志。</div>';
    }

    return `
        <div style="overflow-x: auto;">
            <table class="table task-log-table">
                <thead>
                <tr>
                    <th>结果</th>
                    <th>处理数量</th>
                    <th>开始时间</th>
                    <th>完成时间</th>
                    <th>耗时</th>
                    <th>节点</th>
                    <th>调度实例</th>
                    <th>摘要</th>
                </tr>
                </thead>
                <tbody>
                ${logs.map((log) => `
                    <tr>
                        <td><span class="badge ${getStatusBadgeClass(log.status)}">${escapeHtml(log.status || 'UNKNOWN')}</span></td>
                        <td>${valueOrZero(log.processedCount)}</td>
                        <td>${formatDateTime(log.startedTime)}</td>
                        <td>${formatDateTime(log.completedTime)}</td>
                        <td>${formatDurationMs(log.durationMs)}</td>
                        <td>${escapeHtml(log.nodeId || '-')}</td>
                        <td><span class="mono">${escapeHtml(log.schedulerInstanceId || '-')}</span></td>
                        <td>${escapeHtml(log.message || '-')}</td>
                    </tr>
                `).join('')}
                </tbody>
            </table>
        </div>
    `;
}

function renderTaskAuditTable(taskKey, audits) {
    if (!audits.length) {
        return '<div class="task-empty-state">当前筛选条件下暂无操作审计记录。</div>';
    }

    return `
        <div style="overflow-x: auto;">
            <table class="table task-audit-table">
                <thead>
                <tr>
                    <th>操作</th>
                    <th>结果</th>
                    <th>操作人</th>
                    <th>时间</th>
                    <th>审批备注</th>
                    <th>节点</th>
                    <th>摘要</th>
                </tr>
                </thead>
                <tbody>
                ${audits.map((audit) => `
                    <tr>
                        <td><span class="badge badge-primary">${escapeHtml(getAuditActionLabel(audit.action))}</span></td>
                        <td><span class="badge ${getStatusBadgeClass(audit.result)}">${escapeHtml(audit.result || 'UNKNOWN')}</span></td>
                        <td>
                            <div>${escapeHtml(audit.operatorLabel || 'SYSTEM')}</div>
                            <div class="task-subtitle">${escapeHtml(audit.operatorRole || '-')}</div>
                        </td>
                        <td>${formatDateTime(audit.createTime)}</td>
                        <td>${renderApprovalNoteCell(audit.approvalNote)}</td>
                        <td>
                            <div>${escapeHtml(audit.nodeId || '-')}</div>
                            <div class="task-subtitle mono">${escapeHtml(audit.schedulerInstanceId || '-')}</div>
                        </td>
                        <td>
                            <div>${escapeHtml(audit.message || '-')}</div>
                            ${(audit.detail || audit.message) ? `
                                <button type="button" class="btn btn-secondary btn-small" data-detail-action="audit-copy-detail" data-task="${escapeHtml(taskKey)}" data-audit-id="${escapeHtml(audit.id)}">复制详情</button>
                            ` : ''}
                            ${audit.detail ? `
                                <details class="task-audit-detail">
                                    <summary>查看详情</summary>
                                    <pre class="task-detail-pre">${escapeHtml(audit.detail)}</pre>
                                </details>
                            ` : ''}
                        </td>
                    </tr>
                `).join('')}
                </tbody>
            </table>
        </div>
    `;
}

function updateDetailToggleLabel(taskKey, hidden) {
    document.querySelectorAll(`button[data-detail-toggle][data-task="${taskKey}"]`).forEach((button) => {
        button.textContent = hidden ? '展开详情' : '收起详情';
    });
}

function ensureTaskState(taskKey) {
    return taskDetailState.get(taskKey) || null;
}

function applyAuditFilters(taskKey) {
    const state = ensureTaskState(taskKey);
    if (!state) {
        return;
    }
    const actionInput = document.getElementById(`audit-action-${taskKey}`);
    const resultInput = document.getElementById(`audit-result-${taskKey}`);
    const operatorInput = document.getElementById(`audit-operator-${taskKey}`);
    const startTimeInput = document.getElementById(`audit-start-time-${taskKey}`);
    const endTimeInput = document.getElementById(`audit-end-time-${taskKey}`);
    state.audits.action = actionInput ? String(actionInput.value || '').trim() : '';
    state.audits.result = resultInput ? String(resultInput.value || '').trim() : '';
    state.audits.operator = operatorInput ? String(operatorInput.value || '').trim() : '';
    state.audits.startTime = startTimeInput ? String(startTimeInput.value || '').trim() : '';
    state.audits.endTime = endTimeInput ? String(endTimeInput.value || '').trim() : '';
    state.audits.recentHours = '';
}

function resetAuditFilters(taskKey) {
    const state = ensureTaskState(taskKey);
    if (!state) {
        return;
    }
    state.audits.action = '';
    state.audits.result = '';
    state.audits.operator = '';
    state.audits.startTime = '';
    state.audits.endTime = '';
    state.audits.recentHours = '';
}

function findLatestFailure(logs) {
    return logs.find((log) => log.status === 'FAILED') || null;
}

function setTaskButtonsDisabled(taskKey, disabled) {
    document.querySelectorAll('button[data-task]').forEach((button) => {
        if (button.dataset.task === taskKey && !button.dataset.detailAction && button.dataset.detailToggle !== 'true') {
            button.disabled = disabled;
        }
    });
}

async function downloadTaskLogs(taskKey) {
    await downloadFile(`${API_BASE}/tasks/${encodeURIComponent(taskKey)}/logs/export`, `task-${taskKey}-logs.csv`);
    showTaskMessage('任务日志已开始导出', 'success');
}

async function exportTaskAudits(taskKey) {
    const state = ensureTaskState(taskKey);
    const params = new URLSearchParams();
    if (state && state.audits.action) {
        params.set('action', state.audits.action);
    }
    if (state && state.audits.result) {
        params.set('result', state.audits.result);
    }
    if (state && state.audits.operator) {
        params.set('operator', state.audits.operator);
    }
    if (state && state.audits.startTime) {
        params.set('startTime', state.audits.startTime);
    }
    if (state && state.audits.endTime) {
        params.set('endTime', state.audits.endTime);
    }
    const query = params.toString();
    await downloadFile(
        `${API_BASE}/tasks/${encodeURIComponent(taskKey)}/audits/export${query ? `?${query}` : ''}`,
        `task-${taskKey}-audits.csv`
    );
    showTaskMessage('操作审计已开始导出', 'success');
}

function renderApprovalNoteCell(approvalNote) {
    if (!approvalNote) {
        return '-';
    }

    const safeValue = escapeHtml(approvalNote);
    return `
        <span class="task-approval-note" tabindex="0">
            <span class="task-approval-note-preview">${safeValue}</span>
            <span class="task-approval-note-popover">${safeValue}</span>
        </span>
    `;
}

function formatDateTimeLocalValue(date) {
    if (!(date instanceof Date) || Number.isNaN(date.getTime())) {
        return '';
    }
    const year = date.getFullYear();
    const month = String(date.getMonth() + 1).padStart(2, '0');
    const day = String(date.getDate()).padStart(2, '0');
    const hours = String(date.getHours()).padStart(2, '0');
    const minutes = String(date.getMinutes()).padStart(2, '0');
    return `${year}-${month}-${day}T${hours}:${minutes}`;
}

async function downloadFile(url, defaultFileName) {
    const response = await fetch(url, {
        method: 'GET',
        credentials: 'same-origin'
    });

    if (!response.ok) {
        let message = '文件导出失败';
        try {
            const json = await response.json();
            message = json.message || message;
        } catch (error) {
            // keep fallback message
        }
        const exportError = new Error(message);
        exportError.code = response.status;
        throw exportError;
    }

    const blob = await response.blob();
    const link = document.createElement('a');
    const downloadUrl = window.URL.createObjectURL(blob);
    const disposition = response.headers.get('Content-Disposition') || '';
    const matched = disposition.match(/filename="([^"]+)"/i);
    link.href = downloadUrl;
    link.download = matched ? matched[1] : defaultFileName;
    link.style.display = 'none';
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
    window.URL.revokeObjectURL(downloadUrl);
}

async function copyLatestFailure(taskKey) {
    const data = await api.copyTaskLatestFailure(taskKey);
    const detail = data && data.detail;
    if (!detail) {
        showTaskMessage('当前没有可复制的失败堆栈', 'warning');
        return;
    }

    try {
        if (navigator.clipboard && navigator.clipboard.writeText) {
            await navigator.clipboard.writeText(detail);
        } else {
            fallbackCopy(detail);
        }
        showTaskMessage('失败堆栈已复制到剪贴板', 'success');
    } catch (error) {
        fallbackCopy(detail);
        showTaskMessage('失败堆栈已复制到剪贴板', 'success');
    }
}

async function copyTaskAuditDetail(taskKey, auditId) {
    const data = await api.copyTaskAuditDetail(taskKey, auditId);
    const content = data && data.content;
    if (!content) {
        showTaskMessage('当前没有可复制的审计内容', 'warning');
        return;
    }

    try {
        if (navigator.clipboard && navigator.clipboard.writeText) {
            await navigator.clipboard.writeText(content);
        } else {
            fallbackCopy(content);
        }
        showTaskMessage('审计详情已复制到剪贴板', 'success');
    } catch (error) {
        fallbackCopy(content);
        showTaskMessage('审计详情已复制到剪贴板', 'success');
    }
}

function fallbackCopy(text) {
    const textarea = document.createElement('textarea');
    textarea.value = text;
    textarea.style.position = 'fixed';
    textarea.style.opacity = '0';
    document.body.appendChild(textarea);
    textarea.focus();
    textarea.select();
    document.execCommand('copy');
    document.body.removeChild(textarea);
}

function showTaskMessage(message, type = 'info') {
    const box = document.getElementById('taskActionMessage');
    if (!box) {
        return;
    }
    box.className = `alert alert-${type}`;
    box.textContent = message;
}

function getAuditActionLabel(action) {
    switch (action) {
        case 'TRIGGER':
            return '手动执行';
        case 'RETRY':
            return '失败重试';
        case 'PAUSE':
            return '暂停';
        case 'RESUME':
            return '恢复';
        case 'EXPORT_LOG':
            return '导出日志';
        case 'EXPORT_AUDIT':
            return '导出审计';
        case 'COPY_FAILURE_STACK':
            return '复制堆栈';
        case 'COPY_AUDIT_DETAIL':
            return '复制审计';
        default:
            return action || 'UNKNOWN';
    }
}

function getStatusBadgeClass(status) {
    switch (status) {
        case 'SUCCESS':
            return 'badge-success';
        case 'RUNNING':
            return 'badge-info';
        case 'SKIPPED':
            return 'badge-warning';
        case 'FAILED':
            return 'badge-danger';
        default:
            return 'badge-primary';
    }
}

function getTriggerBadgeClass(state) {
    switch (state) {
        case 'NORMAL':
            return 'badge-success';
        case 'PAUSED':
            return 'badge-warning';
        case 'BLOCKED':
        case 'ERROR':
            return 'badge-danger';
        case 'COMPLETE':
            return 'badge-info';
        default:
            return 'badge-primary';
    }
}

function formatDateTime(value) {
    if (!value) {
        return '-';
    }
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) {
        return '-';
    }
    return date.toLocaleString('zh-CN', { hour12: false });
}

function formatDurationMs(value) {
    if (value === undefined || value === null || Number.isNaN(Number(value))) {
        return '-';
    }
    const milliseconds = Number(value);
    if (milliseconds < 1000) {
        return `${milliseconds} ms`;
    }
    const seconds = milliseconds / 1000;
    if (seconds < 60) {
        return `${seconds.toFixed(seconds >= 10 ? 0 : 1)} s`;
    }
    const minutes = Math.floor(seconds / 60);
    const remainSeconds = Math.round(seconds % 60);
    return `${minutes} min ${remainSeconds} s`;
}

function valueOrZero(value) {
    return value === undefined || value === null ? 0 : value;
}

function escapeHtml(value) {
    return String(value == null ? '' : value)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
}
