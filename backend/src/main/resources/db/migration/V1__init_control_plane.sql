CREATE TABLE app_user (
    id UUID NOT NULL PRIMARY KEY,
    username VARCHAR(64) NOT NULL,
    email VARCHAR(256) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    role VARCHAR(32) NOT NULL,
    enabled BOOLEAN NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_app_user_username UNIQUE (username),
    CONSTRAINT uk_app_user_email UNIQUE (email)
);

CREATE TABLE registered_service (
    id UUID NOT NULL PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    namespace VARCHAR(128),
    environment VARCHAR(32) NOT NULL,
    version VARCHAR(64),
    display_name VARCHAR(128) NOT NULL,
    description VARCHAR(1024),
    owner VARCHAR(128),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_registered_service_name_env UNIQUE (name, environment)
);

CREATE INDEX idx_registered_service_environment ON registered_service (environment);

CREATE TABLE service_dependency (
    id UUID NOT NULL PRIMARY KEY,
    source_service_id UUID NOT NULL,
    target_service_id UUID NOT NULL,
    dependency_type VARCHAR(32) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_service_dependency UNIQUE (source_service_id, target_service_id, dependency_type),
    CONSTRAINT fk_dep_source FOREIGN KEY (source_service_id) REFERENCES registered_service (id),
    CONSTRAINT fk_dep_target FOREIGN KEY (target_service_id) REFERENCES registered_service (id)
);

CREATE TABLE incident (
    id UUID NOT NULL PRIMARY KEY,
    title VARCHAR(256) NOT NULL,
    status VARCHAR(32) NOT NULL,
    severity VARCHAR(32) NOT NULL,
    summary VARCHAR(2000),
    started_at TIMESTAMP WITH TIME ZONE NOT NULL,
    resolved_at TIMESTAMP WITH TIME ZONE,
    created_by VARCHAR(64),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_incident_status_severity ON incident (status, severity);
CREATE INDEX idx_incident_started_at ON incident (started_at);

CREATE TABLE incident_service (
    incident_id UUID NOT NULL,
    service_id UUID NOT NULL,
    PRIMARY KEY (incident_id, service_id),
    CONSTRAINT fk_is_incident FOREIGN KEY (incident_id) REFERENCES incident (id),
    CONSTRAINT fk_is_service FOREIGN KEY (service_id) REFERENCES registered_service (id)
);

CREATE TABLE incident_event (
    id UUID NOT NULL PRIMARY KEY,
    incident_id UUID NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    message VARCHAR(2000) NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_ie_incident FOREIGN KEY (incident_id) REFERENCES incident (id)
);

CREATE INDEX idx_incident_event_incident ON incident_event (incident_id, occurred_at);

CREATE TABLE anomaly (
    id UUID NOT NULL PRIMARY KEY,
    service_id UUID,
    detector_name VARCHAR(128) NOT NULL,
    metric_name VARCHAR(256) NOT NULL,
    score DOUBLE PRECISION,
    status VARCHAR(32) NOT NULL,
    explanation_summary VARCHAR(2000),
    feature_json TEXT,
    detected_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_anomaly_service FOREIGN KEY (service_id) REFERENCES registered_service (id)
);

CREATE INDEX idx_anomaly_detected_at ON anomaly (detected_at);

CREATE TABLE prediction (
    id UUID NOT NULL PRIMARY KEY,
    service_id UUID,
    metric_name VARCHAR(256) NOT NULL,
    horizon_minutes INTEGER NOT NULL,
    predicted_value DOUBLE PRECISION,
    threshold_value DOUBLE PRECISION,
    breach_probability DOUBLE PRECISION,
    status VARCHAR(32) NOT NULL,
    feature_json TEXT,
    predicted_for TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_prediction_service FOREIGN KEY (service_id) REFERENCES registered_service (id)
);

CREATE TABLE explanation (
    id UUID NOT NULL PRIMARY KEY,
    anomaly_id UUID,
    prediction_id UUID,
    method VARCHAR(32) NOT NULL,
    summary VARCHAR(2000) NOT NULL,
    limitations VARCHAR(2000) NOT NULL,
    payload_json TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_exp_anomaly FOREIGN KEY (anomaly_id) REFERENCES anomaly (id),
    CONSTRAINT fk_exp_prediction FOREIGN KEY (prediction_id) REFERENCES prediction (id)
);

CREATE TABLE rca_result (
    id UUID NOT NULL PRIMARY KEY,
    incident_id UUID,
    anomaly_id UUID,
    ranking_json TEXT NOT NULL,
    disclaimer VARCHAR(500) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_rca_incident FOREIGN KEY (incident_id) REFERENCES incident (id),
    CONSTRAINT fk_rca_anomaly FOREIGN KEY (anomaly_id) REFERENCES anomaly (id)
);

CREATE TABLE adaptive_policy (
    id UUID NOT NULL PRIMARY KEY,
    service_id UUID,
    min_trace_sample_rate DOUBLE PRECISION NOT NULL,
    max_trace_sample_rate DOUBLE PRECISION NOT NULL,
    min_scrape_interval_ms INTEGER NOT NULL,
    max_scrape_interval_ms INTEGER NOT NULL,
    cooldown_seconds INTEGER NOT NULL,
    enabled BOOLEAN NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_policy_service FOREIGN KEY (service_id) REFERENCES registered_service (id)
);

CREATE TABLE adaptive_audit (
    id UUID NOT NULL PRIMARY KEY,
    policy_id UUID NOT NULL,
    actor VARCHAR(64) NOT NULL,
    action VARCHAR(64) NOT NULL,
    previous_value TEXT,
    new_value TEXT,
    reason VARCHAR(1000),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_audit_policy FOREIGN KEY (policy_id) REFERENCES adaptive_policy (id)
);

CREATE TABLE telemetry_control_state (
    id UUID NOT NULL PRIMARY KEY,
    service_id UUID NOT NULL,
    trace_sample_rate DOUBLE PRECISION NOT NULL,
    scrape_interval_ms INTEGER NOT NULL,
    source VARCHAR(32) NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_tcs_service UNIQUE (service_id),
    CONSTRAINT fk_tcs_service FOREIGN KEY (service_id) REFERENCES registered_service (id)
);
