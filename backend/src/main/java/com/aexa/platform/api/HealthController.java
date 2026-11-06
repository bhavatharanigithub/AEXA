package com.aexa.platform.api;

import com.aexa.platform.api.dto.HealthResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/api/v1")
public class HealthController {

    private final JdbcTemplate jdbcTemplate;
    private final String activeProfile;

    public HealthController(
            JdbcTemplate jdbcTemplate, @Value("${spring.profiles.active:local}") String activeProfile) {
        this.jdbcTemplate = jdbcTemplate;
        this.activeProfile = activeProfile;
    }

    @GetMapping("/health")
    public ResponseEntity<HealthResponse> health() {
        boolean databaseUp = pingDatabase();
        HealthResponse body = new HealthResponse(
                databaseUp ? "UP" : "DOWN",
                "aexa-backend",
                "0.1.0",
                activeProfile,
                databaseUp ? "UP" : "DOWN",
                Instant.now());
        return ResponseEntity.status(databaseUp ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }

    private boolean pingDatabase() {
        try {
            Integer result = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            return result != null && result == 1;
        } catch (Exception ex) {
            return false;
        }
    }
}
