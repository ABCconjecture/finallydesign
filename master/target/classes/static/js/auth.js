const auth = {
    currentUser: null,

    async getCurrentUser(force = false) {
        if (!force && this.currentUser) {
            return this.currentUser;
        }

        try {
            this.currentUser = await api.getCurrentUser();
            return this.currentUser;
        } catch (error) {
            if (error.code === 401) {
                this.currentUser = null;
                return null;
            }
            throw error;
        }
    },

    isAdmin(user = this.currentUser) {
        return !!user && String(user.role || '').toUpperCase() === 'ADMIN';
    },

    getRoleLabel(role) {
        return String(role || '').toUpperCase() === 'ADMIN' ? '管理员' : '查看员';
    },

    async requireLogin() {
        const currentUser = await this.getCurrentUser(true);
        if (currentUser) {
            return currentUser;
        }
        this.redirectToLogin();
        const error = new Error('请先登录');
        error.code = 401;
        throw error;
    },

    async requireAdmin() {
        const currentUser = await this.requireLogin();
        if (this.isAdmin(currentUser)) {
            return currentUser;
        }
        const error = new Error('当前账号为只读查看员，请使用管理员账号执行该操作');
        error.code = 403;
        throw error;
    },

    redirectToLogin(target) {
        window.location.href = this.getLoginUrl(target);
    },

    getLoginUrl(target) {
        const redirect = target || this.getCurrentPage();
        return `login.html?redirect=${encodeURIComponent(redirect)}`;
    },

    getCurrentPage() {
        const fileName = window.location.pathname.split('/').pop() || 'dashboard.html';
        const search = window.location.search || '';
        const hash = window.location.hash || '';
        return `${fileName}${search}${hash}`;
    },

    async logout() {
        try {
            await api.logout();
        } catch (error) {
            console.warn('退出登录失败，继续清理本地状态', error);
        }
        this.currentUser = null;
        this.redirectToLogin('dashboard.html');
    },

    async renderSessionBar() {
        if (document.body.classList.contains('auth-page')) {
            return;
        }

        const nav = document.querySelector('.daohang');
        if (!nav) {
            return;
        }

        let bar = document.getElementById('sessionBar');
        if (!bar) {
            bar = document.createElement('div');
            bar.id = 'sessionBar';
            bar.className = 'session-bar';
            nav.insertAdjacentElement('afterend', bar);
        }

        const currentUser = await this.getCurrentUser();
        if (currentUser) {
            bar.innerHTML = `
                <div class="session-bar-inner">
                    <div class="session-user">
                        <span class="session-label">当前登录</span>
                        <strong>${currentUser.displayName || currentUser.username}</strong>
                        <span class="session-meta">${currentUser.username} / ${this.getRoleLabel(currentUser.role)}</span>
                    </div>
                    <button type="button" class="session-link" id="logoutButton">退出登录</button>
                </div>
            `;
            const logoutButton = document.getElementById('logoutButton');
            if (logoutButton) {
                logoutButton.addEventListener('click', () => this.logout());
            }
            return;
        }

        bar.innerHTML = `
            <div class="session-bar-inner">
                <div class="session-user">
                    <span class="session-label">当前状态</span>
                    <strong>未登录</strong>
                    <span class="session-meta">查看系统数据需要登录，触发与处理类操作需要管理员身份</span>
                </div>
                <a class="session-link" href="${this.getLoginUrl()}">去登录</a>
            </div>
        `;
    }
};

window.auth = auth;

document.addEventListener('DOMContentLoaded', () => {
    auth.renderSessionBar().catch((error) => {
        console.error('初始化登录状态失败', error);
    });
});