# 00-PROJ-campus-risk-ml

## Project

Campus student behavior analysis and risk warning system.

Workspace:

```text
D:\文件1\毕业论文\毕设\备份\--master
```

## Startup Checklist

When resuming this project:

1. Read this index first.
2. Read `memory.md` for stable decisions and guardrails.
3. Read the latest 3-5 entries under `logs/`.
4. Check `results/` for the latest baseline or experiment outputs.
5. Run `git status --short`.
6. Before code changes, run the baseline tests listed in `memory.md`.
7. After code changes, update today's log and add/refresh a result note.

## Current Direction

Project 2 clustering has been moved from Java Weka K-Means to a Python ML service:

```text
Pandas / NumPy feature matrix
-> scikit-learn scaling and KMeans
-> PyTorch AutoEncoder latent representation
-> scikit-learn KMeans on latent vectors
-> FastAPI service
-> Spring Boot client and dashboard integration
```

Java remains the business platform:

- Spring Boot backend
- MySQL/JPA data access
- Redis/session support
- Quartz task scheduling
- Warning and dashboard pages

## Active Documents

- [[项目二-校园学生行为分析与风险预警系统]]
  - `docs/resume-project-plan/项目二-校园学生行为分析与风险预警系统.md`
- [[项目一-园区用能数据预测与异常预警平台]]
  - `docs/resume-project-plan/项目一-园区用能数据预测与异常预警平台.md`
- [[memory]]
  - `docs/work-memory/memory.md`

## Recent Log

Time order is newest first.

- [[2026-06-02 PyTorch clustering implementation result]]
  - `docs/work-memory/results/2026-06-02-pytorch-clustering-implementation-result.md`
  - Implemented Python ML service, Java client integration, and verification notes; Java-side Weka fallback has now been removed.
- [[2026-06-02 PyTorch 聚类迁移启动]]
  - `docs/work-memory/logs/2026-06-02-pytorch-clustering-migration-start.md`
  - Decided to use scikit-learn as the main clustering tool and PyTorch AutoEncoder as representation-learning enhancement; first implementation slice is complete.
- [[2026-06-02 项目二基线数据审计]]
  - `docs/work-memory/results/2026-06-02-project2-feature-audit.md`
  - Verified 1000 users, 939345 total multi-source logs, 18 behavior features, and interpretable cluster distribution.

## Next Actions

1. Resolve the local Spring Boot repackage file-lock issue if a runnable fat jar is required.
2. Start the Python FastAPI service before using the default Python-first clustering path.
3. For production-like practice, add scheduled model refresh or cached model artifacts instead of per-request training.
4. Add a real integration test that starts FastAPI and triggers `/api/campus/cluster/trigger` through Spring Boot.
5. Keep resume wording focused on multi-source behavior analysis and representation-enhanced clustering.
