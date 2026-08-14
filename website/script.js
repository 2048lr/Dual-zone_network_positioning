// ============================================================
// HamKit · 交互脚本（DeepSeek 风格）
// ============================================================

(function () {
    'use strict';

    // 滚动揭示
    const revealTargets = [
        '.hero__badge',
        '.hero__title',
        '.hero__subtitle',
        '.hero__desc',
        '.hero__cta',
        '.section__head',
        '.feature',
        '.note',
        '.stack__group',
        '.dev-card'
    ];

    revealTargets.forEach(selector => {
        document.querySelectorAll(selector).forEach(el => {
            el.setAttribute('data-reveal', '');
        });
    });

    const revealObserver = new IntersectionObserver((entries) => {
        entries.forEach(entry => {
            if (entry.isIntersecting) {
                entry.target.classList.add('is-visible');
                revealObserver.unobserve(entry.target);
            }
        });
    }, { threshold: 0.08, rootMargin: '0px 0px -40px 0px' });

    document.querySelectorAll('[data-reveal]').forEach(el => revealObserver.observe(el));

    // 导航栏激活态
    const navLinks = document.querySelectorAll('.nav__links a');
    const sections = document.querySelectorAll('main section[id]');

    const navObserver = new IntersectionObserver((entries) => {
        entries.forEach(entry => {
            if (entry.isIntersecting) {
                const id = entry.target.id;
                navLinks.forEach(link => {
                    link.style.color = link.getAttribute('href') === '#' + id
                        ? 'var(--text-primary)'
                        : '';
                });
            }
        });
    }, { rootMargin: '-40% 0px -55% 0px' });

    sections.forEach(sec => navObserver.observe(sec));

    // 动态获取最新版本并更新 badge
    // 优先实时请求 GitHub API 获取最新 release（含预发布，排除 draft）；
    // 断网 / 限流时依次回退到本地缓存、部署烘焙的 version.json。
    (async function () {
        const badge = document.getElementById('release-badge');
        if (!badge) return;

        const REPO = 'fuxue-linkong/HamKit';
        const CACHE_KEY = 'hamkit-latest-version';
        const CACHE_TTL = 5 * 60 * 1000; // 5 分钟内复用缓存，减少 GitHub API 限流风险

        const render = (tag, prerelease) => {
            if (!tag) return false;
            badge.textContent = tag + (prerelease ? ' · 预发布' : ' · 现已上线');
            return true;
        };

        const remember = (tag, prerelease) => {
            try {
                localStorage.setItem(CACHE_KEY, JSON.stringify({ tag, prerelease, ts: Date.now() }));
            } catch (e) { /* 隐私模式等场景忽略 */ }
        };

        const readCache = () => {
            try {
                const raw = localStorage.getItem(CACHE_KEY);
                return raw ? JSON.parse(raw) : null;
            } catch (e) {
                return null;
            }
        };

        // 1) 实时在线获取 GitHub 最新 release，避免依赖静态 version.json 的旧值
        try {
            const r = await fetch(`https://api.github.com/repos/${REPO}/releases?per_page=1`, {
                headers: { 'Accept': 'application/vnd.github+json' },
                cache: 'no-store'
            });
            if (!r.ok) throw new Error(`HTTP ${r.status}`);
            const list = await r.json();
            const rel = Array.isArray(list) ? list.find(x => !x.draft) : null;
            if (rel && render(rel.tag_name, rel.prerelease)) {
                remember(rel.tag_name, rel.prerelease);
                return;
            }
        } catch (e) { /* 限流 / 断网，走兜底 */ }

        // 2) 上次成功获取的版本兜底（断网 / 限流时仍显示已知版本）
        const cached = readCache();
        const fresh = cached && cached.ts && Date.now() - cached.ts < CACHE_TTL;
        if (cached && render(cached.tag, cached.prerelease) && fresh) return;

        // 3) 部署时烘焙的 version.json 兜底（静态文件，零 API 调用）
        try {
            const res = await fetch('./version.json', { cache: 'no-store' });
            if (res.ok) {
                const v = await res.json();
                if (render(v.tag, v.prerelease)) return;
            }
        } catch (e) { /* 本地直接打开文件等场景没有 version.json */ }

        // 4) 全部失败：保留加载态提示，不回退到写死的旧版本号
        if (!badge.textContent || badge.textContent === '正在加载…') {
            badge.textContent = '查看 Releases';
        }
    })();
})();
