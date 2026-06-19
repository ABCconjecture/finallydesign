# 2026-06-02 Project 2 Feature Audit

## Purpose

Validate whether the current project 2 data can support multi-source feature processing, risk scoring, and clustering before migrating to Python + scikit-learn + PyTorch.

## Dataset

```text
users: 1000
network logs: 526352
access logs: 411184
borrow logs: 1809
total logs: 939345
feature count: 18
```

## Feature Summary

```text
avgRisk: 31.64
avgBehaviorScore: 68.36
avgOnlineHours: 2.24
avgStudyRatio: 0.234
avgLateReturn: 2.10
avgClassroomAccess: 31.03
avgLibraryAccess: 23.86
```

## Warning Candidate Summary

```text
high risk users, riskScore >= 65: 51
medium risk users, 45 <= riskScore < 65: 49
network warning candidates: 100
access/routine warning candidates: 141
academic or borrow warning candidates: 10
```

## Lightweight Cluster Summary

```text
cluster 0: 258 users, learning-resource-active, risk 17.13
cluster 1: 361 users, teaching-activity-active, risk 31.11
cluster 2: 209 users, routine-fluctuation-observation, risk 35.14
cluster 3: 100 users, network-high-low-activity-attention, risk 65.30
cluster 4: 72 users, borrow-learning-active, risk 29.46
```

## Conclusion

The current data supports the migration plan:

- multi-source log processing is feasible;
- 18 behavior features can be constructed;
- cluster labels are interpretable;
- Python ML service can start from these same features;
- PyTorch AutoEncoder should be introduced as an enhancement and compared against the scikit-learn baseline.

