package com.bateacapitalassistant.integrations;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MonitoringTest {
    @Test
    void anOfflineEntityDoesNotMarkTheWholeDeviceUnavailable() throws Exception {
        var json = IntegrationSupport.JSON;
        var devices = json.readTree("[{\"id\":\"light\",\"name_by_user\":\"Hall\"}]");
        var entities = json.readTree("""
                [{"device_id":"light","entity_id":"light.hall"},
                 {"device_id":"light","entity_id":"sensor.hall"},
                 {"device_id":"light","entity_id":"sensor.disabled","disabled_by":"user"}]
                """);
        var states = json.readTree("""
                [{"entity_id":"light.hall","state":"off"},
                 {"entity_id":"sensor.hall","state":"unavailable"},
                 {"entity_id":"sensor.disabled","state":"ok"}]
                """);
        assertFalse(HomeAssistant.deviceAvailability(devices, entities, states).get("light").unavailable());
        ((com.fasterxml.jackson.databind.node.ObjectNode) states.get(0)).put("state", "unavailable");
        assertEquals(new HomeAssistant.DeviceAvailability("Hall", true),
                HomeAssistant.deviceAvailability(devices, entities, states).get("light"));
    }

    @Test
    void distinguishesBackupFailureUnknownAndStaleData() throws Exception {
        Instant now = Instant.parse("2026-11-27T12:00:00Z");
        var failure = Backups.parse(IntegrationSupport.JSON.readTree("""
                {"collected_at":"2026-11-27T11:55:00Z","tasks":[
                 {"task_id":1,"name":"NAS","last_bkp_result":"failed"},
                 {"task_id":2,"name":"Other","last_bkp_result":42}]}
                """), now);
        assertEquals(2, failure.problems().size());
        assertTrue(failure.text().contains("unrecognized"));
        var stale = Backups.parse(IntegrationSupport.JSON.readTree("""
                {"collected_at":"2026-11-26T11:55:00Z","tasks":[{"name":"NAS","last_bkp_result":"success"}]}
                """), now);
        assertTrue(stale.problems().containsKey("monitoring"));
        assertFalse(stale.text().contains("last result successful"));
    }

    @Test
    void servicesDifferentiateStoppedUnhealthyAndUnknown() throws Exception {
        var data = IntegrationSupport.JSON.readTree("""
                [{"Names":["/radarr"],"State":"running","Status":"Up 1 hour"},
                 {"Names":["/sonarr"],"State":"exited","Status":"Exited (1)"},
                 {"Names":["/seerr"],"State":"running","Status":"Up 1 hour (unhealthy)"}]
                """);
        String report = Services.formatStatus(data);
        assertTrue(report.contains("2 stopped, unhealthy, or unavailable; 1 running"));
        assertTrue(report.contains("sonarr — exited"));
        assertTrue(Services.formatStatus(null).contains("unknown"));
    }
}
