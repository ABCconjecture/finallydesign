package com.example.bysjdesign;

import com.example.bysjdesign.service.ml.PythonClusterClient;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PythonClusterClientTest {

    @Test
    void shouldReturnEmptyWhenDisabled() {
        PythonClusterClient client = new PythonClusterClient();

        Optional<PythonClusterClient.ClusterResponse> result = client.cluster(Collections.emptyList(), 2);

        assertTrue(result.isEmpty());
    }

    @Test
    void shouldReturnEmptyWhenPythonServiceUnavailable() throws Exception {
        PythonClusterClient client = new PythonClusterClient();
        setField(client, "enabled", true);
        setField(client, "clusterUrl", "http://127.0.0.1:65534/cluster");
        setField(client, "timeoutMs", 1000);
        setField(client, "useAutoEncoder", true);
        setField(client, "autoencoderEpochs", 5);
        setField(client, "latentDim", 2);

        Optional<PythonClusterClient.ClusterResponse> result = client.cluster(Collections.emptyList(), 2);

        assertTrue(result.isEmpty());
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
