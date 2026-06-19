from __future__ import annotations

from typing import Any

from fastapi import FastAPI
from pydantic import BaseModel, Field

from .cluster_model import ClusterConfig, cluster_feature_rows


class ClusterRequest(BaseModel):
    rows: list[dict[str, Any]] = Field(default_factory=list)
    clusterCount: int = 5
    useAutoEncoder: bool = True
    autoencoderEpochs: int = 80
    latentDim: int = 4


app = FastAPI(title="Campus Cluster Service")


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}


@app.post("/cluster")
def cluster(request: ClusterRequest) -> dict[str, Any]:
    config = ClusterConfig(
        cluster_count=request.clusterCount,
        use_autoencoder=request.useAutoEncoder,
        autoencoder_epochs=request.autoencoderEpochs,
        latent_dim=request.latentDim,
    )
    return cluster_feature_rows(request.rows, config)

