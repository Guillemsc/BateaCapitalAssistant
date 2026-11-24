package com.bateacapitalassistant.integrations;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.Map;

import static com.bateacapitalassistant.integrations.IntegrationSupport.*;

public final class Tdarr {
    private Tdarr() {
    }

    public static ObjectNode status() throws IOException, InterruptedException {
        String key = IntegrationSupport.secret("tdarr-api-key");
        JsonNode status = IntegrationSupport.request("http://192.168.1.152:8265/api/v2/status", null, Map.of("x-api-key", key), 15);
        JsonNode nodes = IntegrationSupport.request("http://192.168.1.152:8265/api/v2/get-nodes", null, Map.of("x-api-key", key), 15);
        ArrayNode summaries = IntegrationSupport.JSON.createArrayNode();
        for (JsonNode node : rows(nodes).stream().limit(20).toList()) {
            JsonNode workers = node.path("workers");
            ArrayNode active = IntegrationSupport.JSON.createArrayNode();
            for (JsonNode worker : rows(workers).stream().limit(10).toList()) {
                if (!worker.isObject()) continue;
                ObjectNode safe = IntegrationSupport.JSON.createObjectNode();
                for (String field : new String[]{"id", "workerType", "status", "percentage", "progress", "fps", "ETA", "eta", "file", "fileName"})
                    if (worker.path(field).isValueNode() && !worker.path(field).isNull())
                        safe.set(field, worker.path(field));
                active.add(safe);
            }
            ObjectNode summary = summaries.addObject();
            summary.set("name", node.path("nodeName"));
            summary.set("paused", node.path("nodePaused"));
            summary.set("node_queue_lengths", node.path("queueLengths"));
            summary.put("active_worker_count", workers.size());
            summary.set("workers", active);
        }
        ObjectNode result = IntegrationSupport.JSON.createObjectNode();
        result.set("version", status.path("version"));
        result.set("nodes", summaries);
        return result;
    }
}
