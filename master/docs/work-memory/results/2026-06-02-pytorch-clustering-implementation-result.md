# 2026-06-02 PyTorch Clustering Implementation Result

## Scope

Project 2 clustering was upgraded from Java-side Weka K-Means to a Python ML service architecture:

```text
Spring Boot feature aggregation
-> FastAPI Python ML service
-> Pandas / NumPy feature matrix
-> scikit-learn StandardScaler
-> optional PyTorch AutoEncoder representation learning
-> scikit-learn KMeans
-> Java profile / warning / dashboard integration
```

Java no longer keeps a local Weka clustering implementation. If the Python service is unavailable, Java reports that clustering cannot continue.

## Added Or Changed

- `ml/campus_cluster/feature_schema.py`
- `ml/campus_cluster/cluster_model.py`
- `ml/campus_cluster/service.py`
- `ml/campus_cluster/requirements.txt`
- `ml/campus_cluster/tests/test_cluster_model.py`
- `src/main/java/com/example/bysjdesign/service/ml/PythonClusterClient.java`
- `src/main/java/com/example/bysjdesign/service/KMeansService.java`
- `src/main/resources/application.properties`
- `docs/resume-project-plan/项目二-校园学生行为分析与风险预警系统.md`

## Verification

```text
mvn test
PASS: 14 tests, 0 failures, 0 errors, 1 skipped

PYTHONPATH=ml python -m unittest ml/campus_cluster/tests/test_cluster_model.py
PASS: 2 tests

FastAPI /health
PASS: status=ok

FastAPI /cluster
PASS: status=ok, algorithm=sklearn-kmeans, clusterCount=2, sampleCount=12

PythonClusterClient fallback probe
PASS: fallbackOptionalEmpty=true

mvn -DskipTests "-Dspring-boot.repackage.skip=true" package
PASS
```

## Follow-up Verification

After enabling Python clustering by default and adding `PythonClusterClientTest`:

```text
PYTHONPATH=ml python -m unittest ml/campus_cluster/tests/test_cluster_model.py
PASS: 2 tests

FastAPI /health and /cluster smoke
PASS: status=ok, algorithm=sklearn-kmeans, clusterCount=2, sampleCount=12

mvn test
PASS: 16 tests, 0 failures, 0 errors, 1 skipped

mvn -DskipTests package
FAIL: same Spring Boot repackage file-lock issue

mvn -DskipTests "-Dspring-boot.repackage.skip=true" package
PASS
```

Runtime default now prefers Python clustering:

```text
app.ml.cluster.enabled=${APP_ML_CLUSTER_ENABLED:true}
```

## Weka Removal Update

After the user requested the new plan without keeping Weka:

- Removed `weka-stable` from `pom.xml`.
- Removed Weka imports and `SimpleKMeans` fallback logic from `KMeansService`.
- Kept `KMeansService` as the Java orchestration name, but its clustering implementation now depends on the Python FastAPI service.
- Updated docs so resume wording no longer includes Weka fallback.

## Final Cleanup Verification

Code scan:

```text
rg -n "weka|Weka|SimpleKMeans" src pom.xml ml
PASS: no matches

rg -n "降级兜底|兜底|回退到|Weka|weka|SimpleKMeans" src/main/java src/main/resources pom.xml docs/resume-project-plan/项目二-校园学生行为分析与风险预警系统.md
PASS: no matches
```

Latest test run:

```text
PYTHONPATH=ml python -m unittest ml/campus_cluster/tests/test_cluster_model.py
PASS: 2 tests

FastAPI /health and /cluster smoke
PASS: status=ok, algorithm=sklearn-kmeans, clusterCount=2, sampleCount=12

mvn test
PASS: 16 tests, 0 failures, 0 errors, 1 skipped

mvn -DskipTests package
FAIL: same Spring Boot repackage jar rename/file-lock issue

mvn -DskipTests "-Dspring-boot.repackage.skip=true" package
PASS
```

Note: the verified FastAPI smoke request uses integer `userId`, matching the Java data model and Python parser.

## Known Limitation

`mvn -DskipTests package` without skipping Spring Boot repackage still fails:

```text
Unable to rename target/bysj-design-1.0.0.jar to target/bysj-design-1.0.0.jar.original
```

This is currently treated as a local `target` file-lock issue, not a compile failure, because `mvn test` and package without repackage both pass.

## Resume Wording

Use:

- Python + Pandas + NumPy data processing
- scikit-learn KMeans clustering
- PyTorch AutoEncoder representation learning
- FastAPI ML service
- Spring Boot Java integration
- multi-source behavior feature fusion

Avoid:

- deep learning directly predicting student risk
- medical or psychological diagnosis
- replacing the whole Java system with Python
