package com.example.bysjdesign.service.ml;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class PythonClusterClient {

    private static final Logger logger = LoggerFactory.getLogger(PythonClusterClient.class);

    private final Gson gson = new Gson();
    private final HttpClient httpClient;

    @Value("${app.ml.cluster.enabled:true}")
    private boolean enabled;

    @Value("${app.ml.cluster.url:http://127.0.0.1:8001/cluster}")
    private String clusterUrl;

    @Value("${app.ml.cluster.timeout-ms:5000}")
    private int timeoutMs;

    @Value("${app.ml.cluster.autoencoder:true}")
    private boolean useAutoEncoder;

    @Value("${app.ml.cluster.autoencoder-epochs:80}")
    private int autoencoderEpochs;

    @Value("${app.ml.cluster.latent-dim:4}")
    private int latentDim;

    public PythonClusterClient() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Optional<ClusterResponse> cluster(List<Map<String, Object>> rows, int clusterCount) {
        if (!enabled) {
            return Optional.empty();
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("rows", rows);
        payload.put("clusterCount", clusterCount);
        payload.put("useAutoEncoder", useAutoEncoder);
        payload.put("autoencoderEpochs", autoencoderEpochs);
        payload.put("latentDim", latentDim);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(clusterUrl))
                .timeout(Duration.ofMillis(Math.max(timeoutMs, 1000)))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(payload)))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                logger.warn("Python 聚类服务返回非 2xx 状态码: {}", response.statusCode());
                return Optional.empty();
            }
            ClusterResponse clusterResponse = gson.fromJson(response.body(), ClusterResponse.class);
            if (clusterResponse == null || clusterResponse.assignments == null || clusterResponse.clusters == null) {
                logger.warn("Python 聚类服务响应为空或字段缺失");
                return Optional.empty();
            }
            return Optional.of(clusterResponse);
        } catch (IOException | InterruptedException | JsonSyntaxException | IllegalArgumentException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            logger.warn("调用 Python 聚类服务失败，当前聚类任务无法继续: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    public static class ClusterResponse {
        private String status;
        private String algorithm;
        private Map<String, Object> metrics;
        private List<ClusterAssignment> assignments;
        private List<ClusterDescriptorPayload> clusters;

        public String getStatus() {
            return status;
        }

        public String getAlgorithm() {
            return algorithm;
        }

        public Map<String, Object> getMetrics() {
            return metrics;
        }

        public List<ClusterAssignment> getAssignments() {
            return assignments;
        }

        public List<ClusterDescriptorPayload> getClusters() {
            return clusters;
        }
    }

    public static class ClusterAssignment {
        private Integer userId;
        private Integer clusterId;
        private String label;
        private String summary;
        private String focus;
        private Map<String, Double> features;

        public Integer getUserId() {
            return userId;
        }

        public Integer getClusterId() {
            return clusterId;
        }

        public String getLabel() {
            return label;
        }

        public String getSummary() {
            return summary;
        }

        public String getFocus() {
            return focus;
        }

        public Map<String, Double> getFeatures() {
            return features;
        }
    }

    public static class ClusterDescriptorPayload {
        private Integer clusterId;
        private String label;
        private String summary;
        private String focus;
        private Integer count;
        private Map<String, Double> centroid;

        public Integer getClusterId() {
            return clusterId;
        }

        public String getLabel() {
            return label;
        }

        public String getSummary() {
            return summary;
        }

        public String getFocus() {
            return focus;
        }

        public Integer getCount() {
            return count;
        }

        public Map<String, Double> getCentroid() {
            return centroid;
        }
    }
}
