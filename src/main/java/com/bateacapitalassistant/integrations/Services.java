package com.bateacapitalassistant.integrations;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.*;

import static com.bateacapitalassistant.integrations.IntegrationSupport.*;

public final class Services {
    private Services() {
    }

    public static String status() {
        return formatStatus(ContainerNames.containers());
    }

    static String formatStatus(JsonNode containers) {
        if (containers == null || !containers.isArray())
            return "Service status unavailable. Docker could not be queried; container availability is unknown.";
        var issues = new ArrayList<String>();
        var running = new ArrayList<String>();
        for (JsonNode row : containers) {
            String name = row.path("Names").path(0).asText("").replaceFirst("^/", "");
            if (!name.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}")) continue;
            String state = row.path("State").asText("unknown");
            String detail = limit(row.path("Status").asText("").replaceAll("[\\p{Cntrl}]", " "), 150);
            String label = "• " + name + " — " + state + (detail.isBlank() ? "" : " (" + detail + ")");
            if (!state.equals("running") || detail.contains("(unhealthy)")) issues.add(label);
            else running.add(label);
        }
        var lines = new ArrayList<String>();
        lines.add("Container services — live Docker snapshot");
        lines.add(issues.size() + " stopped, unhealthy, or unavailable; " + running.size() + " running");
        lines.addAll(issues);
        lines.addAll(running);
        lines.add("Stopped containers may be intentional. Running state alone does not verify an application's API.");
        return String.join("\n", lines);
    }
}
