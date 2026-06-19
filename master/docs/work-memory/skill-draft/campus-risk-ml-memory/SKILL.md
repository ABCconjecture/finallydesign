---
name: campus-risk-ml-memory
description: Use when working on this campus behavior analysis project, especially project memory, PyTorch/scikit-learn clustering migration, Java Spring Boot integration, test workflow, resume wording, or status restoration. Reads project index and recent logs before acting.
---

# Campus Risk ML Memory

## Startup

When this skill is used, first inspect:

1. `docs/work-memory/00-PROJ-campus-risk-ml.md`
2. `docs/work-memory/memory.md`
3. the latest 3-5 files in `docs/work-memory/logs/`
4. latest relevant files in `docs/work-memory/results/`
5. `git status --short`

Then provide a short status brief before making code changes.

## Project Rule

This project is a campus student behavior analysis and risk warning system.

Java is the business platform:

- Spring Boot
- Spring Data JPA
- MySQL
- Redis
- Quartz
- warning/profile/dashboard APIs
- Python service invocation and unavailable-service error handling

Python is the ML service:

- Pandas / NumPy feature processing
- scikit-learn scaler and KMeans
- PyTorch AutoEncoder representation learning
- FastAPI service
- clustering metrics and labels

## Work Rules

Before code changes:

1. Run baseline tests from `memory.md`.
2. Save or reference important results in `docs/work-memory/results/`.
3. Preserve existing Java behavior unless the user explicitly asks to remove it.

After code changes:

1. Run Python tests if Python code changed.
2. Run Java tests if Java code changed.
3. Run a build check.
4. Test Python-service-down failure behavior if integration code changed.
5. Update today's log in `docs/work-memory/logs/`.

## Resume Guardrails

Use:

- behavior analysis
- multi-source data processing
- behavior stability score
- dimensional risk score
- K-Means clustering
- PyTorch AutoEncoder representation learning
- FastAPI ML service

Avoid:

- medical diagnosis
- psychological diagnosis
- health conclusion
- deep learning directly predicting student risk

## Output Style

For status restoration, output:

```text
Status:
- Branch / git state:
- Recent work:
- Latest result:
- Current risk:
- Recommended next step:
```

For implementation work, keep a concise change summary and list exact tests run.
