# Database design

Canonical RDBMS: **PostgreSQL 16**. Local profile: **H2 2.x PostgreSQL mode**. Schema is applied by Flyway `V1__init_control_plane.sql`.

## 1. Ownership

PostgreSQL stores control-plane data only. It does **not** store raw metric samples, raw log lines, or raw spans.

## 2. Entity relationship (logical)

```
app_user
registered_service <── service_dependency ──> registered_service
registered_service <── incident_service ──> incident ── incident_event
anomaly ── explanation
prediction ── explanation
rca_result (incident- or anomaly-scoped)
adaptive_policy ── adaptive_audit
telemetry_control_state (per service)
```

## 3. Tables

### app_user
Application operators, not end-customers of the demo shop.

| Column | Type | Notes |
| --- | --- | --- |
| id | UUID PK | |
| username | VARCHAR(64) UNIQUE NOT NULL | |
| email | VARCHAR(256) UNIQUE NOT NULL | |
| password_hash | VARCHAR(100) NOT NULL | BCrypt |
| role | VARCHAR(32) NOT NULL | `ADMIN`, `ANALYST`, `VIEWER` |
| enabled | BOOLEAN NOT NULL | |
| created_at / updated_at | TIMESTAMP WITH TIME ZONE | |

Indexes: `username`, `email`.

### registered_service
Catalog of known microservices.

| Column | Type | Notes |
| --- | --- | --- |
| id | UUID PK | |
| name | VARCHAR(128) NOT NULL | `service.name` |
| namespace | VARCHAR(128) | `service.namespace` |
| environment | VARCHAR(32) NOT NULL | |
| version | VARCHAR(64) | |
| display_name | VARCHAR(128) | |
| description | VARCHAR(1024) | |
| owner | VARCHAR(128) | |
| created_at / updated_at | timestamptz | |

Unique: `(name, environment)`. Index: `environment`.

### service_dependency
Directed edge `source` depends on `target`.

| Column | Type |
| --- | --- |
| id | UUID PK |
| source_service_id | UUID FK NOT NULL |
| target_service_id | UUID FK NOT NULL |
| dependency_type | VARCHAR(32) | `SYNC_HTTP`, `ASYNC_MESSAGING`, `DATABASE` |
| created_at | timestamptz |

Unique: `(source_service_id, target_service_id, dependency_type)`.

### incident
Confirmed investigation object (not an anomaly and not a forecast).

| Column | Type |
| --- | --- |
| id | UUID PK |
| title | VARCHAR(256) NOT NULL |
| status | VARCHAR(32) NOT NULL | `OPEN`, `INVESTIGATING`, `RESOLVED`, `CLOSED` |
| severity | VARCHAR(32) NOT NULL | `LOW`, `MEDIUM`, `HIGH`, `CRITICAL` |
| summary | VARCHAR(2000) | |
| started_at / resolved_at | timestamptz | |
| created_by | VARCHAR(64) | |
| created_at / updated_at | timestamptz | |

Indexes: `(status, severity)`, `started_at`.

### incident_service
M2M affected services.

### incident_event
Timeline entries (`DETECTOR`, `OPERATOR`, `SYSTEM`).

### anomaly / prediction / explanation / rca_result
Created empty in Phase 1 so later phases do not rewrite migrations. `feature_json` and `evidence_json` are `TEXT` holding JSON documents (H2-portable; PostgreSQL can still index later with `jsonb` via a dedicated phase migration if needed).

### adaptive_policy
Global or per-service clamps: `min_trace_sample_rate`, `max_trace_sample_rate`, `min_scrape_interval_ms`, `max_scrape_interval_ms`, `cooldown_seconds`, `enabled`.

### adaptive_audit
Who proposed, who applied, previous/new values, reason.

### telemetry_control_state
Currently **applied** sample rate / interval per service (last safe values).

## 4. JSON payload conventions

TEXT JSON must include `"schemaVersion": 1`. Unknown fields are ignored by readers.

## 5. Migration policy

- Never edit an applied versioned file.
- Additive migrations only (`V2__...`).
- Hibernate `ddl-auto` is `validate` on postgres and `none` when Flyway runs. Local H2 also uses Flyway.
