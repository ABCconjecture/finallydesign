document.addEventListener("DOMContentLoaded", () => {
    const revealTargets = document.querySelectorAll(
        ".card, .metric-card, .attention-card, .summary-card, .compare-list-item, .task-progress-card, .auth-card"
    );

    if (!revealTargets.length) {
        return;
    }

    const prefersReducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    if (prefersReducedMotion || !("IntersectionObserver" in window)) {
        revealTargets.forEach((element) => {
            element.classList.add("reveal-visible");
        });
        return;
    }

    revealTargets.forEach((element) => {
        element.classList.add("reveal-ready");
    });

    const observer = new IntersectionObserver((entries) => {
        entries.forEach((entry) => {
            if (!entry.isIntersecting) {
                return;
            }
            entry.target.classList.add("reveal-visible");
            observer.unobserve(entry.target);
        });
    }, {
        threshold: 0.12,
        rootMargin: "0px 0px -40px 0px"
    });

    revealTargets.forEach((element) => observer.observe(element));
});
