from __future__ import annotations

from dataclasses import dataclass
from typing import Any

import numpy as np
import pandas as pd
import torch
from sklearn.cluster import KMeans
from sklearn.metrics import davies_bouldin_score, silhouette_score
from sklearn.preprocessing import StandardScaler
from torch import nn

from .feature_schema import FEATURE_ORDER


@dataclass(frozen=True)
class ClusterConfig:
    cluster_count: int = 5
    seed: int = 42
    autoencoder_epochs: int = 80
    latent_dim: int = 4
    use_autoencoder: bool = True


class BehaviorAutoEncoder(nn.Module):
    def __init__(self, input_dim: int, latent_dim: int):
        super().__init__()
        hidden_dim = max(8, input_dim // 2)
        self.encoder = nn.Sequential(
            nn.Linear(input_dim, hidden_dim),
            nn.ReLU(),
            nn.Linear(hidden_dim, latent_dim),
        )
        self.decoder = nn.Sequential(
            nn.Linear(latent_dim, hidden_dim),
            nn.ReLU(),
            nn.Linear(hidden_dim, input_dim),
        )

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        return self.decoder(self.encoder(x))

    def encode(self, x: torch.Tensor) -> torch.Tensor:
        return self.encoder(x)


def cluster_feature_rows(rows: list[dict[str, Any]], config: ClusterConfig | None = None) -> dict[str, Any]:
    config = config or ClusterConfig()
    if not rows:
        return _empty_result()

    frame = _to_feature_frame(rows)
    actual_cluster_count = _resolve_cluster_count(frame, config.cluster_count)
    if actual_cluster_count <= 1:
        return _single_cluster_result(rows, frame)

    scaler = StandardScaler()
    scaled = scaler.fit_transform(frame[FEATURE_ORDER].to_numpy(dtype=np.float32))
    embedding, reconstruction_loss, algorithm = _build_embedding(scaled, config)

    kmeans = KMeans(n_clusters=actual_cluster_count, random_state=config.seed, n_init=20)
    labels = kmeans.fit_predict(embedding)

    clusters = _build_clusters(frame, labels, actual_cluster_count)
    cluster_lookup = {item["clusterId"]: item for item in clusters}
    assignments = []
    for idx, row in frame.iterrows():
        cluster_id = int(labels[idx])
        cluster = cluster_lookup[cluster_id]
        assignments.append(
            {
                "userId": int(row["userId"]),
                "clusterId": cluster_id,
                "label": cluster["label"],
                "summary": cluster["summary"],
                "focus": cluster["focus"],
                "features": _row_features(row),
            }
        )

    metrics = _build_metrics(embedding, labels, reconstruction_loss)
    return {
        "status": "ok",
        "algorithm": algorithm,
        "featureOrder": FEATURE_ORDER,
        "metrics": metrics,
        "clusters": clusters,
        "assignments": assignments,
    }


def _to_feature_frame(rows: list[dict[str, Any]]) -> pd.DataFrame:
    normalized_rows: list[dict[str, float]] = []
    for item in rows:
        features = item.get("features") or {}
        normalized = {"userId": int(item.get("userId"))}
        for name in FEATURE_ORDER:
            normalized[name] = _to_float(features.get(name))
        normalized_rows.append(normalized)
    return pd.DataFrame(normalized_rows)


def _resolve_cluster_count(frame: pd.DataFrame, requested_count: int) -> int:
    if len(frame) <= 1:
        return 1
    unique_rows = frame[FEATURE_ORDER].drop_duplicates()
    return max(1, min(int(requested_count), len(frame), len(unique_rows)))


def _build_embedding(scaled: np.ndarray, config: ClusterConfig) -> tuple[np.ndarray, float | None, str]:
    if not config.use_autoencoder or len(scaled) < 10:
        return scaled, None, "sklearn-kmeans"

    torch.manual_seed(config.seed)
    input_dim = scaled.shape[1]
    latent_dim = max(2, min(config.latent_dim, input_dim, len(scaled) - 1))
    model = BehaviorAutoEncoder(input_dim=input_dim, latent_dim=latent_dim)
    optimizer = torch.optim.Adam(model.parameters(), lr=0.01, weight_decay=1e-4)
    loss_fn = nn.MSELoss()
    data = torch.tensor(scaled, dtype=torch.float32)

    model.train()
    last_loss = 0.0
    for _ in range(config.autoencoder_epochs):
        optimizer.zero_grad()
        reconstructed = model(data)
        loss = loss_fn(reconstructed, data)
        loss.backward()
        optimizer.step()
        last_loss = float(loss.detach().cpu().item())

    model.eval()
    with torch.no_grad():
        embedding = model.encode(data).cpu().numpy()
    return embedding, round(last_loss, 6), "pytorch-autoencoder-kmeans"


def _build_clusters(frame: pd.DataFrame, labels: np.ndarray, cluster_count: int) -> list[dict[str, Any]]:
    clusters = []
    for cluster_id in range(cluster_count):
        members = frame[labels == cluster_id]
        centroid = {
            name: _round(float(members[name].mean())) if len(members) else 0.0
            for name in FEATURE_ORDER
        }
        label, focus = _label_cluster(centroid)
        summary = _summary(label, focus, len(members), centroid)
        clusters.append(
            {
                "clusterId": cluster_id,
                "label": label,
                "summary": summary,
                "focus": focus,
                "count": int(len(members)),
                "centroid": centroid,
            }
        )
    return clusters


def _build_metrics(embedding: np.ndarray, labels: np.ndarray, reconstruction_loss: float | None) -> dict[str, Any]:
    unique_labels = set(int(label) for label in labels)
    metrics: dict[str, Any] = {
        "clusterCount": len(unique_labels),
        "sampleCount": int(len(labels)),
    }
    if reconstruction_loss is not None:
        metrics["reconstructionLoss"] = reconstruction_loss
    if len(unique_labels) > 1 and len(labels) > len(unique_labels):
        metrics["silhouetteScore"] = _round(float(silhouette_score(embedding, labels)))
        metrics["daviesBouldinScore"] = _round(float(davies_bouldin_score(embedding, labels)))
    return metrics


def _label_cluster(centroid: dict[str, float]) -> tuple[str, str]:
    if centroid.get("riskScore", 0.0) >= 55.0 or centroid.get("healthScore", 100.0) < 45.0:
        return "网络偏高低活跃关注型", "在线时长和综合风险偏高，学习空间访问相对偏低"
    if centroid.get("lateReturnCount", 0.0) >= 4.0:
        return "作息波动观察型", "晚归次数偏高，学习生活节律存在波动"
    if centroid.get("studyTrafficRatio", 0.0) >= 0.25 or centroid.get("libraryAccessCount", 0.0) >= 25.0:
        return "学习资源活跃型", "学习类流量或图书馆访问较高，自主学习行为更活跃"
    if centroid.get("classroomAccessCount", 0.0) >= 25.0:
        return "教学活动活跃型", "教学楼访问较高，课堂与校园活动参与较稳定"
    if centroid.get("borrowCount", 0.0) >= 1.0:
        return "借阅学习活跃型", "借阅行为相对活跃，学习资源使用稳定"
    return "均衡稳定型", "多维行为较均衡，暂未出现明显集中风险"


def _summary(label: str, focus: str, count: int, centroid: dict[str, float]) -> str:
    return (
        f"{label}：{focus}；样本 {count} 人；"
        f"平均风险 {centroid.get('riskScore', 0.0):.2f}；"
        f"综合行为评分 {centroid.get('healthScore', 0.0):.2f}；"
        f"日均在线 {centroid.get('avgOnlineHours', 0.0):.2f} 小时；"
        f"学习流量占比 {centroid.get('studyTrafficRatio', 0.0):.2f}"
    )


def _row_features(row: pd.Series) -> dict[str, float]:
    return {name: _round(float(row[name])) for name in FEATURE_ORDER}


def _single_cluster_result(rows: list[dict[str, Any]], frame: pd.DataFrame) -> dict[str, Any]:
    centroid = {
        name: _round(float(frame[name].mean())) if len(frame) else 0.0
        for name in FEATURE_ORDER
    }
    label, focus = _label_cluster(centroid)
    cluster = {
        "clusterId": 0,
        "label": label,
        "summary": _summary(label, focus, len(frame), centroid),
        "focus": focus,
        "count": len(frame),
        "centroid": centroid,
    }
    assignments = [
        {
            "userId": int(item.get("userId")),
            "clusterId": 0,
            "label": label,
            "summary": cluster["summary"],
            "focus": focus,
            "features": item.get("features") or {},
        }
        for item in rows
    ]
    return {
        "status": "ok",
        "algorithm": "single-cluster",
        "featureOrder": FEATURE_ORDER,
        "metrics": {"clusterCount": 1, "sampleCount": len(rows)},
        "clusters": [cluster],
        "assignments": assignments,
    }


def _empty_result() -> dict[str, Any]:
    return {
        "status": "empty",
        "algorithm": "none",
        "featureOrder": FEATURE_ORDER,
        "metrics": {"clusterCount": 0, "sampleCount": 0},
        "clusters": [],
        "assignments": [],
    }


def _to_float(value: Any) -> float:
    if value is None:
        return 0.0
    try:
        if isinstance(value, bool):
            return 1.0 if value else 0.0
        return float(value)
    except (TypeError, ValueError):
        return 0.0


def _round(value: float) -> float:
    return round(value, 6)

