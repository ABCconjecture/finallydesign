document.addEventListener('DOMContentLoaded', async () => {
    bindAccountEvents();
    try {
        await auth.requireAdmin();
        await loadAccountPage();
    } catch (error) {
        if (error.code === 401) {
            auth.redirectToLogin('account.html');
            return;
        }
        alert(error.message || '当前页面仅管理员可访问');
        window.location.href = 'dashboard.html';
    }
});

function bindAccountEvents() {
    document.getElementById('profileForm')?.addEventListener('submit', updateProfile);
    document.getElementById('passwordForm')?.addEventListener('submit', changePassword);
    document.getElementById('createUserForm')?.addEventListener('submit', createAccountUser);
}

async function loadAccountPage() {
    const currentUser = await api.getCurrentUser();
    fillCurrentUser(currentUser);
    await loadAccountUsers();
}

function fillCurrentUser(currentUser) {
    document.getElementById('currentAccountName').textContent = currentUser.displayName || currentUser.username;
    document.getElementById('currentAccountMeta').textContent = `${currentUser.username} / ${auth.getRoleLabel(currentUser.role)}`;
    document.getElementById('profileUsername').value = currentUser.username || '';
    document.getElementById('profileDisplayName').value = currentUser.displayName || '';
}

async function loadAccountUsers() {
    const users = await api.getAccountUsers();
    renderAccountTable(users || []);
}

function renderAccountTable(users) {
    const tbody = document.getElementById('accountUsersTable');
    if (!tbody) {
        return;
    }

    if (!users.length) {
        tbody.innerHTML = '<tr><td colspan="8" style="text-align: center; color: var(--text-light);">暂无账号数据</td></tr>';
        return;
    }

    tbody.innerHTML = users.map((user) => {
        const statusEnabled = Number(user.status) === 1;
        const nextStatus = statusEnabled ? 0 : 1;
        const statusText = statusEnabled ? '启用' : '停用';
        const statusClass = statusEnabled ? 'badge-success' : 'badge-warning';
        const nextRole = String(user.role).toUpperCase() === 'ADMIN' ? 'VIEWER' : 'ADMIN';
        const roleActionText = nextRole === 'ADMIN' ? '设为管理员' : '设为查看员';
        const selfTag = user.self ? '<span class="badge badge-info">当前登录</span>' : '';
        const statusButton = user.self
            ? '<span style="color: var(--text-light);">不能停用当前账号</span>'
            : `<button class="btn btn-small ${nextStatus === 1 ? 'btn-success' : 'btn-warning'}" onclick="toggleAccountStatus(${user.userId}, ${nextStatus}, '${escapeJs(user.username)}')">${nextStatus === 1 ? '启用' : '停用'}</button>`;
        const roleButton = user.self
            ? '<span style="color: var(--text-light);">当前角色由其他管理员调整</span>'
            : `<button class="btn btn-small btn-primary" onclick="toggleAccountRole(${user.userId}, '${nextRole}', '${escapeJs(user.username)}')">${roleActionText}</button>`;
        const resetButton = `<button class="btn btn-small btn-primary" onclick="resetAccountPassword(${user.userId}, '${escapeJs(user.username)}')">重置密码</button>`;

        return `
            <tr>
                <td>${escapeHtml(user.username)} ${selfTag}</td>
                <td>${escapeHtml(user.displayName || '-')}</td>
                <td><span class="badge badge-primary">${auth.getRoleLabel(user.role)}</span></td>
                <td><span class="badge ${statusClass}">${statusText}</span></td>
                <td>${formatDateTime(user.lastLoginTime)}</td>
                <td>${formatDateTime(user.createTime)}</td>
                <td>${formatDateTime(user.updateTime)}</td>
                <td class="account-actions">${roleButton} ${statusButton} ${resetButton}</td>
            </tr>
        `;
    }).join('');
}

async function updateProfile(event) {
    event.preventDefault();
    const username = document.getElementById('profileUsername').value.trim();
    const displayName = document.getElementById('profileDisplayName').value.trim();

    try {
        const updatedUser = await api.updateCurrentProfile({ username, displayName });
        auth.currentUser = updatedUser;
        await auth.renderSessionBar();
        fillCurrentUser(updatedUser);
        alert('账号资料已更新');
        await loadAccountUsers();
    } catch (error) {
        handleAccountError(error);
    }
}

async function changePassword(event) {
    event.preventDefault();
    const currentPassword = document.getElementById('currentPassword').value;
    const newPassword = document.getElementById('newPassword').value;
    const confirmPassword = document.getElementById('confirmPassword').value;

    if (newPassword !== confirmPassword) {
        alert('两次输入的新密码不一致');
        return;
    }

    try {
        await api.changeCurrentPassword({ currentPassword, newPassword });
        document.getElementById('passwordForm').reset();
        alert('密码已修改，下次登录请使用新密码');
    } catch (error) {
        handleAccountError(error);
    }
}

async function createAccountUser(event) {
    event.preventDefault();
    const username = document.getElementById('newAccountUsername').value.trim();
    const displayName = document.getElementById('newAccountDisplayName').value.trim();
    const role = document.getElementById('newAccountRole').value;
    const password = document.getElementById('newAccountPassword').value;

    try {
        await api.createAccountUser({ username, displayName, role, password });
        document.getElementById('createUserForm').reset();
        document.getElementById('newAccountRole').value = 'VIEWER';
        alert('新账号已创建');
        await loadAccountUsers();
    } catch (error) {
        handleAccountError(error);
    }
}

async function toggleAccountStatus(userId, nextStatus, username) {
    const actionText = nextStatus === 1 ? '启用' : '停用';
    if (!confirm(`确认${actionText}账号 ${username} 吗？`)) {
        return;
    }

    try {
        await api.updateAccountStatus(userId, { status: nextStatus });
        alert(`账号 ${username} 已${actionText}`);
        await loadAccountUsers();
    } catch (error) {
        handleAccountError(error);
    }
}

async function toggleAccountRole(userId, role, username) {
    const roleLabel = auth.getRoleLabel(role);
    if (!confirm(`确认将账号 ${username} 调整为${roleLabel}吗？`)) {
        return;
    }

    try {
        await api.updateAccountRole(userId, { role });
        const currentUser = await auth.getCurrentUser(true);
        await auth.renderSessionBar();
        alert(`账号 ${username} 的角色已调整为${roleLabel}`);
        if (!auth.isAdmin(currentUser)) {
            alert('当前账号已切换为查看员，即将返回首页。');
            window.location.href = 'dashboard.html';
            return;
        }
        await loadAccountUsers();
    } catch (error) {
        handleAccountError(error);
    }
}

async function resetAccountPassword(userId, username) {
    const newPassword = window.prompt(`请输入账号 ${username} 的新密码`, 'Admin@2026Reset');
    if (newPassword === null) {
        return;
    }

    try {
        await api.resetAccountPassword(userId, { newPassword: newPassword.trim() });
        alert(`账号 ${username} 的密码已重置`);
    } catch (error) {
        handleAccountError(error);
    }
}

function handleAccountError(error) {
    console.error('账号管理操作失败:', error);
    if (error.code === 401) {
        auth.redirectToLogin('account.html');
        return;
    }
    if (error.code === 403) {
        alert(error.message || '当前无管理员权限');
        window.location.href = 'dashboard.html';
        return;
    }
    alert(error.message || '账号管理操作失败');
}

function formatDateTime(value) {
    if (!value) {
        return '-';
    }
    return new Date(value).toLocaleString();
}

function escapeHtml(value) {
    return String(value ?? '')
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
}

function escapeJs(value) {
    return String(value ?? '').replace(/\\/g, '\\\\').replace(/'/g, "\\'");
}

window.toggleAccountStatus = toggleAccountStatus;
window.toggleAccountRole = toggleAccountRole;
window.resetAccountPassword = resetAccountPassword;