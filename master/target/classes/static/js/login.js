document.addEventListener('DOMContentLoaded', async () => {
    const form = document.getElementById('loginForm');
    const submitButton = document.getElementById('loginButton');
    const errorBox = document.getElementById('loginError');
    const usernameInput = document.getElementById('username');
    const passwordInput = document.getElementById('password');
    const redirectTarget = new URLSearchParams(window.location.search).get('redirect') || 'warning.html';

    try {
        const currentUser = await api.getCurrentUser();
        if (currentUser) {
            window.location.href = redirectTarget;
            return;
        }
    } catch (error) {
        if (error.code !== 401) {
            console.error('检查登录状态失败', error);
        }
    }

    form.addEventListener('submit', async (event) => {
        event.preventDefault();
        errorBox.textContent = '';
        errorBox.classList.add('hidden');
        submitButton.disabled = true;
        submitButton.textContent = '登录中...';

        try {
            await api.login({
                username: usernameInput.value.trim(),
                password: passwordInput.value
            });
            window.location.href = redirectTarget;
        } catch (error) {
            errorBox.textContent = error.message || '登录失败，请稍后重试';
            errorBox.classList.remove('hidden');
        } finally {
            submitButton.disabled = false;
            submitButton.textContent = '登录';
        }
    });
});