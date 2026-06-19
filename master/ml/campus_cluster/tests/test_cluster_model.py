import unittest

from campus_cluster.cluster_model import ClusterConfig, cluster_feature_rows
from campus_cluster.feature_schema import FEATURE_ORDER


def sample_rows(count=30):
    rows = []
    for idx in range(count):
        high_risk = idx >= count // 2
        features = {name: 0.0 for name in FEATURE_ORDER}
        features.update(
            {
                "avgOnlineHours": 6.5 if high_risk else 1.8,
                "studyTrafficRatio": 0.08 if high_risk else 0.55,
                "libraryAccessCount": 5 if high_risk else 42,
                "borrowCount": 0 if high_risk else 2,
                "networkActivityCount": 120 if high_risk else 55,
                "classroomAccessCount": 8 if high_risk else 35,
                "lateReturnCount": 5 if high_risk else 1,
                "activeDays": 26,
                "avgAccessFrequency": 6.0,
                "avgBorrowDays": 0 if high_risk else 15,
                "networkRisk": 70 if high_risk else 10,
                "accessRisk": 45 if high_risk else 8,
                "borrowRisk": 0,
                "abnormalTrafficFlag": 1.0 if high_risk else 0.0,
                "absenteeFlag": 0.0,
                "riskScore": 68 if high_risk else 18,
                "healthScore": 32 if high_risk else 82,
            }
        )
        rows.append({"userId": idx + 1, "features": features})
    return rows


class ClusterModelTest(unittest.TestCase):
    def test_cluster_feature_rows_with_autoencoder(self):
        result = cluster_feature_rows(
            sample_rows(),
            ClusterConfig(cluster_count=2, autoencoder_epochs=5, latent_dim=2),
        )
        self.assertEqual("ok", result["status"])
        self.assertEqual(30, len(result["assignments"]))
        self.assertEqual(2, len(result["clusters"]))
        self.assertIn("silhouetteScore", result["metrics"])
        self.assertEqual("pytorch-autoencoder-kmeans", result["algorithm"])

    def test_cluster_feature_rows_without_autoencoder(self):
        result = cluster_feature_rows(
            sample_rows(),
            ClusterConfig(cluster_count=2, use_autoencoder=False),
        )
        self.assertEqual("ok", result["status"])
        self.assertEqual("sklearn-kmeans", result["algorithm"])
        self.assertEqual(30, result["metrics"]["sampleCount"])


if __name__ == "__main__":
    unittest.main()

