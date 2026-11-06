# Phase 1 verification evidence (historical)

Recorded 26 Oct 2026 on Windows 11.

## Automated tests

| Suite | Command | Result |
| --- | --- | --- |
| Backend | `mvn test` in `backend/` | 10 tests, 0 failures (BUILD SUCCESS) |
| Frontend | `npm test` in `frontend/` | 2 tests passed |
| ML | `pytest` in `ml-service/` | 1 passed |

## Live process checks (H2 `local` profile)

| Endpoint | Result |
| --- | --- |
| `GET http://localhost:8080/api/v1/health` | `status=UP`, `database=UP`, `profile=local` |
| `GET http://localhost:8080/actuator/health` | `status=UP`, `components.db=H2 UP` |
| `GET http://127.0.0.1:8090/health` | `status=UP`, `modelStatus=not_trained`, `phase=1` |
| `POST /api/v1/auth/login` | JWT issued for `admin` / `ADMIN` |
| `GET /api/v1/services` | Three **catalog seed** services, labelled “No live telemetry in Phase 1” |
| `GET /api/v1/incidents` | `{ "items": [] }` |

## Not verified on this machine

- Docker Compose PostgreSQL (Docker Desktop is not installed)
- Binding to the existing Windows PostgreSQL 16 instance (superuser password unknown; not guessed)
- Telemetry, ML accuracy, RCA, or adaptive sampling (out of Phase 1)

## Honesty notes

Open-incident KPI is `0` because the incident table is empty, not because a detector evaluated production traffic.
