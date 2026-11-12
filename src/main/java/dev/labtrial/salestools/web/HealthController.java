package dev.labtrial.salestools.web;

import dev.labtrial.salestools.core.SchemaState;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class HealthController {
    private final SchemaState schema;
    public HealthController(SchemaState schema) { this.schema = schema; }
    @GetMapping("/health")
    ResponseEntity<Map<String, Object>> health() {
        try { return ResponseEntity.ok(schema.verify()); }
        catch (RuntimeException unavailable) {
            return ResponseEntity.status(503).body(Map.of("status", "DOWN", "application", "lab-sales-systems"));
        }
    }
}
