/**
 * 前端工具函数
 */

/**
 * 格式化日期
 */
function formatDate(date, format = 'YYYY-MM-DD') {
    if (!date) return '';
    const d = new Date(date);
    const year = d.getFullYear();
    const month = String(d.getMonth() + 1).padStart(2, '0');
    const day = String(d.getDate()).padStart(2, '0');
    const hours = String(d.getHours()).padStart(2, '0');
    const minutes = String(d.getMinutes()).padStart(2, '0');

    return format
        .replace('YYYY', year)
        .replace('MM', month)
        .replace('DD', day)
        .replace('HH', hours)
        .replace('mm', minutes);
}

/**
 * 显示加载提示
 */
function showLoading(message = '加载中...') {
    const div = document.createElement('div');
    div.className = 'alert alert-info';
    div.id = 'loading-alert';
    div.innerHTML = message;
    document.body.appendChild(div);
}

/**
 * 隐藏加载提示
 */
function hideLoading() {
    const div = document.getElementById('loading-alert');
    if (div) div.remove();
}

/**
 * 显示成功提示
 */
function showSuccess(message) {
    const div = document.createElement('div');
    div.className = 'alert alert-success';
    div.textContent = message;
    document.body.appendChild(div);
    setTimeout(() => div.remove(), 3000);
}

/**
 * 显示错误提示
 */
function showError(message) {
    const div = document.createElement('div');
    div.className = 'alert alert-danger';
    div.textContent = message;
    document.body.appendChild(div);
    setTimeout(() => div.remove(), 5000);
}

/**
 * 深拷贝对象
 */
function deepClone(obj) {
    return JSON.parse(JSON.stringify(obj));
}

/**
 * 数组分组
 */
function groupBy(array, key) {
    return array.reduce((result, item) => {
        (result[item[key]] = result[item[key]] || []).push(item);
        return result;
    }, {});
}

/**
 * 数组去重
 */
function unique(array, key) {
    if (!key) {
        return [...new Set(array)];
    }

    const seen = new Set();
    return array.filter(item => {
        const value = item[key];
        if (seen.has(value)) return false;
        seen.add(value);
        return true;
    });
}

/**
 * 数据格式化
 */
function formatNumber(num, decimals = 0) {
    return Number(num).toFixed(decimals);
}

/**
 * 生成随机颜色
 */
function randomColor() {
    return '#' + Math.floor(Math.random() * 16777215).toString(16);
}

/**
 * 防抖函数
 */
function debounce(fn, delay = 300) {
    let timeout;
    return function(...args) {
        clearTimeout(timeout);
        timeout = setTimeout(() => fn.apply(this, args), delay);
    };
}

/**
 * 节流函数
 */
function throttle(fn, delay = 300) {
    let last = 0;
    return function(...args) {
        const now = Date.now();
        if (now - last >= delay) {
            last = now;
            fn.apply(this, args);
        }
    };
}

/**
 * 查询参数解析
 */
function getQueryParam(name) {
    const url = new URL(window.location);
    return url.searchParams.get(name);
}

/**
 * 本地存储
 */
const storage = {
    set: (key, value) => localStorage.setItem(key, JSON.stringify(value)),
    get: (key) => {
        const item = localStorage.getItem(key);
        return item ? JSON.parse(item) : null;
    },
    remove: (key) => localStorage.removeItem(key),
    clear: () => localStorage.clear(),
};