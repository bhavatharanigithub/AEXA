package com.aexa.platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.LinkedHashMap;
import java.util.Map;

@ConfigurationProperties(prefix = "aexa")
public class AexaProperties {

    private final Jwt jwt = new Jwt();
    private final Bootstrap bootstrap = new Bootstrap();
    private final Telemetry telemetry = new Telemetry();
    private final TraceStore traces = new TraceStore();
    private final LogStore logs = new LogStore();
    private final Ai ai = new Ai();
    private final Adaptive adaptive = new Adaptive();

    public Jwt getJwt() {
        return jwt;
    }

    public Bootstrap getBootstrap() {
        return bootstrap;
    }

    public Telemetry getTelemetry() {
        return telemetry;
    }

    public TraceStore getTraces() {
        return traces;
    }

    public LogStore getLogs() {
        return logs;
    }

    public Ai getAi() {
        return ai;
    }

    public Adaptive getAdaptive() {
        return adaptive;
    }

    public static class Adaptive {
        private int evaluationIntervalSeconds = 15;
        private int cooldownSeconds = 60;
        private int staleAfterSeconds = 45;
        private int maxChangesPerHour = 12;
        private Map<String, String> demoServiceUrls = new LinkedHashMap<>(Map.of(
                "checkout-api", "http://localhost:8081",
                "inventory-api", "http://localhost:8082",
                "pricing-api", "http://localhost:8083"));

        public int getEvaluationIntervalSeconds() {
            return evaluationIntervalSeconds;
        }

        public void setEvaluationIntervalSeconds(int evaluationIntervalSeconds) {
            this.evaluationIntervalSeconds = requireRange(evaluationIntervalSeconds, 5, 300, "evaluation interval");
        }

        public int getCooldownSeconds() {
            return cooldownSeconds;
        }

        public void setCooldownSeconds(int cooldownSeconds) {
            this.cooldownSeconds = requireRange(cooldownSeconds, 15, 3600, "cooldown");
        }

        public int getStaleAfterSeconds() {
            return staleAfterSeconds;
        }

        public void setStaleAfterSeconds(int staleAfterSeconds) {
            this.staleAfterSeconds = requireRange(staleAfterSeconds, 15, 300, "stale telemetry age");
        }

        public int getMaxChangesPerHour() {
            return maxChangesPerHour;
        }

        public void setMaxChangesPerHour(int maxChangesPerHour) {
            this.maxChangesPerHour = requireRange(maxChangesPerHour, 1, 120, "hourly change limit");
        }

        public Map<String, String> getDemoServiceUrls() {
            return demoServiceUrls;
        }

        public void setDemoServiceUrls(Map<String, String> demoServiceUrls) {
            if (demoServiceUrls == null || demoServiceUrls.isEmpty()) {
                throw new IllegalArgumentException("at least one demo service control URL is required");
            }
            this.demoServiceUrls = new LinkedHashMap<>(demoServiceUrls);
        }

        private int requireRange(int value, int minimum, int maximum, String name) {
            if (value < minimum || value > maximum) {
                throw new IllegalArgumentException(name + " must be between " + minimum + " and " + maximum);
            }
            return value;
        }
    }

    public static class Ai {
        private String serviceUrl = "http://localhost:8090";
        private int timeoutSeconds = 10;

        public String getServiceUrl() {
            return serviceUrl;
        }

        public void setServiceUrl(String serviceUrl) {
            this.serviceUrl = serviceUrl;
        }

        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(int timeoutSeconds) {
            if (timeoutSeconds < 1 || timeoutSeconds > 120) {
                throw new IllegalArgumentException("AI service timeout must be between 1 and 120 seconds");
            }
            this.timeoutSeconds = timeoutSeconds;
        }
    }

    public static class Telemetry {
        private String prometheusUrl = "http://localhost:9090";

        public String getPrometheusUrl() {
            return prometheusUrl;
        }

        public void setPrometheusUrl(String prometheusUrl) {
            this.prometheusUrl = prometheusUrl;
        }
    }

    public static class TraceStore {
        private String jaegerUrl = "http://localhost:16686";

        public String getJaegerUrl() {
            return jaegerUrl;
        }

        public void setJaegerUrl(String jaegerUrl) {
            this.jaegerUrl = jaegerUrl;
        }
    }

    public static class LogStore {
        private String lokiUrl = "http://localhost:3100";

        public String getLokiUrl() {
            return lokiUrl;
        }

        public void setLokiUrl(String lokiUrl) {
            this.lokiUrl = lokiUrl;
        }
    }

    public static class Jwt {
        private String secret;
        private long expiryMinutes = 480;

        public String getSecret() {
            return secret;
        }

        public void setSecret(String secret) {
            this.secret = secret;
        }

        public long getExpiryMinutes() {
            return expiryMinutes;
        }

        public void setExpiryMinutes(long expiryMinutes) {
            this.expiryMinutes = expiryMinutes;
        }
    }

    public static class Bootstrap {
        private String adminUsername;
        private String adminPassword;
        private String analystUsername;
        private String analystPassword;
        private boolean seedCatalog = true;

        public String getAdminUsername() {
            return adminUsername;
        }

        public void setAdminUsername(String adminUsername) {
            this.adminUsername = adminUsername;
        }

        public String getAdminPassword() {
            return adminPassword;
        }

        public void setAdminPassword(String adminPassword) {
            this.adminPassword = adminPassword;
        }

        public String getAnalystUsername() {
            return analystUsername;
        }

        public void setAnalystUsername(String analystUsername) {
            this.analystUsername = analystUsername;
        }

        public String getAnalystPassword() {
            return analystPassword;
        }

        public void setAnalystPassword(String analystPassword) {
            this.analystPassword = analystPassword;
        }

        public boolean isSeedCatalog() {
            return seedCatalog;
        }

        public void setSeedCatalog(boolean seedCatalog) {
            this.seedCatalog = seedCatalog;
        }
    }
}
