package io.refactor.scheduledwf.service;

import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

@Component
public class ConductorClient {
    private final RestTemplate rest = new RestTemplate();
    private final String baseUrl;

    public ConductorClient(org.springframework.core.env.Environment env) {
        this.baseUrl = env.getProperty("conductor.url", "http://localhost:8080");
    }

    public String startWorkflow(String name, int version, Map<String, Object> input) {
        String url = baseUrl + "/workflow";
        Map<String, Object> body = Map.of(
            "name", name,
            "version", version,
            "input", input
        );
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> req = new HttpEntity<>(body, headers);
        ResponseEntity<String> resp = rest.postForEntity(url, req, String.class);
        return resp.getBody();
    }

    public int countActiveRuns(String namespace, String workflowName) {
        // TODO: call conductor search API to count running workflows for (namespace, workflowName)
        // For now return 0 to allow triggers.
        return 0;
    }
}
