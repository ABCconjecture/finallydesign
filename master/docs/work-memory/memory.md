# Project Memory

## Stable Decisions

1. Project 2 should be positioned as a multi-source data analysis and Java backend engineering project, not as a pure deep-learning project.
2. The clustering migration should use scikit-learn KMeans as the main algorithm.
3. PyTorch should be used as an AutoEncoder representation-learning enhancement, not described as "PyTorch clustering" by itself.
4. Java should remain responsible for backend, permissions, tasks, warning handling, dashboard APIs, Python service invocation, and unavailable-service error handling.
5. Python should be responsible for feature matrix processing, model training/evaluation, cluster assignment, labels, and metrics.
6. Do not use TensorFlow in this project unless the user explicitly changes direction. Current choice is PyTorch.
7. Do not describe the project as diagnosing health, psychology, or medical risk. Use behavior analysis, attention risk, behavior stability score, and anomaly warning.

## Data Baseline

Current CSV data under `src/main/resources/data`:

- `campus_user.csv`: 1000 users.
- `network_log.csv`: 526352 rows.
- `access_log.csv`: 411184 rows.
- `borrow_log.csv`: 1809 rows.
- Total logs: 939345 rows.

The current feature set has 18 dimensions:

```text
avgOnlineHours
studyTrafficRatio
libraryAccessCount
borrowCount
networkActivityCount
classroomAccessCount
lateReturnCount
activeDays
avgAccessFrequency
avgBorrowDays
unreturnedCount
networkRisk
accessRisk
borrowRisk
abnormalTrafficFlag
absenteeFlag
riskScore
behaviorScore
```

Note: existing Java code still uses `healthScore`; resume and new docs should prefer `behaviorScore` or "综合行为评分".

## Test Rules

Before code changes:

```powershell
mvn test
mvn -DskipTests package
```

Also run or reproduce a data baseline check:

- user count
- total log count
- feature count
- average risk score
- cluster distribution

After code changes:

1. Run Python unit tests.
2. Run Python API smoke test if FastAPI service exists.
3. Run Java unit/integration tests.
4. Run Java package build.
5. Run a service-unavailable test where Python clustering returns no result and Java reports that clustering cannot continue.
6. Update `docs/work-memory/logs/` and `docs/work-memory/results/`.

## Resume Guardrails

Allowed wording:

- 多源数据处理
- 数据清洗与类别映射
- 用户维度特征融合
- 学生行为画像
- 综合行为评分
- 分维度风险评分
- K-Means 聚类
- PyTorch AutoEncoder 表征学习
- FastAPI 模型服务化
- Spring Boot 调用 Python 模型服务

Avoid wording:

- 心理诊断
- 健康结论
- 医学评估
- 精准预测学生风险
- 深度学习直接判断学生状态

## Implementation Guardrails

1. Keep existing Java pages and APIs usable.
2. Do not reintroduce Java-side Weka clustering; Python/scikit-learn is the only clustering implementation.
3. Use deterministic seeds for KMeans and PyTorch experiments.
4. Persist or return cluster metrics: silhouette score, Davies-Bouldin score, cluster counts, and cluster centroids.
5. Do not add network-dependent installs without approval.
6. If Python dependencies are missing, request permission before installing.
