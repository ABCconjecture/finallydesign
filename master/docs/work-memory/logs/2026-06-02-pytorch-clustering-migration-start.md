# 2026-06-02 PyTorch 聚类迁移启动

## Context

The user wants project 2 to migrate from Java Weka K-Means to a more current ML stack:

```text
Python + Pandas + NumPy + scikit-learn + PyTorch
```

The user also wants the work to follow a skill-based working-memory system:

- skill stores startup instructions and workflow rules;
- changing project context lives in project index/log files;
- logs are organized by time in reverse chronological order;
- each resume should read the project index, recent logs, result files, and git status.

## Decisions

1. Use scikit-learn KMeans as the main clustering implementation.
2. Use PyTorch AutoEncoder only as an enhancement to learn latent user-behavior representations.
3. Keep Spring Boot as the platform and expose Python clustering through FastAPI.
4. Keep fallback behavior while migrating, so project can still run if Python service is unavailable.
5. Establish project-local memory first, then optionally install the Codex skill into the user skill directory.

Note: the fallback decision above was superseded by the user's later request to remove old Weka code. Current state: Python ML service is the only clustering implementation.

## Planned Architecture

```text
analysis_data / CSV features
-> Python feature loader
-> StandardScaler
-> KMeans baseline
-> PyTorch AutoEncoder
-> KMeans on latent vectors
-> cluster labels and metrics
-> FastAPI response
-> Java client
-> dashboard / warnings / profiles
```

## Next Tests

Before code modification:

1. Run `mvn test`.
2. Run `mvn -DskipTests package`.
3. Run feature audit baseline and save output to `results/`.

After modification:

1. Python unit tests.
2. Python service smoke test.
3. Java client tests.
4. Full Java build.
5. Python-service-down fallback test.

## Open Risks

1. Python dependencies may be missing locally.
2. Network may be restricted, so dependency installation may require user approval.
3. AutoEncoder may be overkill for 1000 users and 18 features, so it should not replace the scikit-learn baseline.
4. Existing code uses `healthScore`; documentation should migrate wording to `behaviorScore` while keeping code compatibility.

## Implementation Update

Completed the first migration slice:

1. Added `ml/campus_cluster/` Python module.
2. Added Pandas / NumPy feature matrix construction.
3. Added scikit-learn `StandardScaler` and `KMeans`.
4. Added PyTorch AutoEncoder representation learning as an optional enhancement.
5. Added FastAPI `/health` and `/cluster` service.
6. Added Java `PythonClusterClient`.
7. Updated `KMeansService` so Python clustering is used when enabled; the later cleanup removed Java-side Weka fallback.
8. Added `app.ml.cluster.*` configuration in `application.properties`.
9. Updated project 2 resume-plan wording to reflect the optimized stack.

## Test Results

```text
mvn test
-> PASS, 14 tests, 0 failures, 0 errors, 1 skipped

PYTHONPATH=ml python -m unittest ml/campus_cluster/tests/test_cluster_model.py
-> PASS, 2 tests

FastAPI smoke test
-> /health returned ok
-> /cluster returned ok, sklearn-kmeans, 2 clusters, 12 samples

Python-service-down Java fallback probe
-> fallbackOptionalEmpty=true

mvn -DskipTests "-Dspring-boot.repackage.skip=true" package
-> PASS
```

## Remaining Issues

1. `mvn -DskipTests package` still fails in Spring Boot `repackage` because `target/bysj-design-1.0.0.jar` cannot be renamed to `.jar.original`.
2. The failure appears to be a local file-lock issue, with IDE / Maven Server / Java LSP processes still running.
3. The current ML service is synchronous and trains per request; for production-like use, add cached model artifacts or scheduled retraining.
4. AutoEncoder is useful for resume and practice, but should be presented as representation enhancement, not as direct risk prediction.

## Follow-up Implementation Update

User requested to start code changes, change the clustering algorithm, and delete unrelated files.

Completed:

1. Deleted `.codex_tmp/` temporary files after validating the resolved path stayed inside the workspace.
2. Changed Python clustering to be enabled by default:
   - `app.ml.cluster.enabled=${APP_ML_CLUSTER_ENABLED:true}`
   - `@Value("${app.ml.cluster.enabled:true}")`
3. Later removed Java Weka K-Means fallback at the user's request.
4. Added persistent Java test `PythonClusterClientTest` for disabled and service-unavailable behavior.

Latest verification:

```text
PYTHONPATH=ml python -m unittest ml/campus_cluster/tests/test_cluster_model.py
-> PASS, 2 tests

FastAPI smoke test
-> PASS, /health ok, /cluster ok, algorithm=sklearn-kmeans, clusterCount=2, sampleCount=12

mvn test
-> PASS, 16 tests, 0 failures, 0 errors, 1 skipped

mvn -DskipTests package
-> FAIL, same local Spring Boot repackage file-lock issue

mvn -DskipTests "-Dspring-boot.repackage.skip=true" package
-> PASS
```

## Weka Removal Update

The user requested the new plan without keeping the old Weka implementation.

Completed:

1. Removed `weka-stable` from `pom.xml`.
2. Removed Weka imports, `SimpleKMeans`, `Instances`, normalization, and local cluster-label fallback methods from `KMeansService`.
3. Changed failed Python clustering behavior from Java fallback to explicit failure:
   - `Python 聚类服务不可用或响应无效，请先启动 FastAPI 聚类服务后重试`
4. Removed Weka fallback wording from the project 2 resume-plan document.

## Final Framework Cleanup Verification

After removing the old Weka path, the current framework is:

```text
Spring Boot feature aggregation and dashboard APIs
-> PythonClusterClient
-> FastAPI /cluster
-> Pandas / NumPy feature matrix
-> scikit-learn StandardScaler
-> optional PyTorch AutoEncoder representation learning
-> scikit-learn KMeans
-> Java profile / overview / warning integration
```

Cleanup checks:

```text
rg -n "weka|Weka|SimpleKMeans" src pom.xml ml
-> PASS: no matches

rg -n "降级兜底|兜底|回退到|Weka|weka|SimpleKMeans" src/main/java src/main/resources pom.xml docs/resume-project-plan/项目二-校园学生行为分析与风险预警系统.md
-> PASS: no matches
```

Verification:

```text
PYTHONPATH=ml python -m unittest ml/campus_cluster/tests/test_cluster_model.py
-> PASS, 2 tests

FastAPI smoke test with integer userId and useAutoEncoder=false
-> PASS, /health ok, /cluster ok, algorithm=sklearn-kmeans, clusterCount=2, sampleCount=12

mvn test
-> PASS, 16 tests, 0 failures, 0 errors, 1 skipped

mvn -DskipTests package
-> FAIL, same local Spring Boot repackage file-lock issue

mvn -DskipTests "-Dspring-boot.repackage.skip=true" package
-> PASS
```

Implementation note:

- Java tests now mock `PythonClusterClient` where the Spring Boot integration flow needs clustering output.
- `PythonClusterClientTest` keeps service-disabled and service-unavailable behavior covered.
- The first failed FastAPI smoke probe used string user IDs; the service expects numeric IDs produced by the Java data model, so the verified smoke test uses integer `userId`.
