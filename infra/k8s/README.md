# Kubernetes demonstration manifests

`aexa-app.yaml` contains the namespace and app-tier Deployments/Services for the FastAPI ML service, Spring backend, and React frontend. It includes readiness/liveness probes and CPU/memory requests and limits.

This is a deployment demonstration scaffold, **not a self-contained cluster install and not yet validated against a live cluster**. PostgreSQL, Prometheus, Loki, Jaeger, the OTel Collector, and demo-service endpoints must already be reachable using the service names in `aexa-config`, or those values must be edited for the target cluster. PostgreSQL storage and backup are managed by that external deployment.

## Build/load the local images

From the repository root:

```powershell
docker compose -f infra\docker-compose.yml build backend frontend ml-service
kind load docker-image aexa-backend:local aexa-frontend:local aexa-ml-service:local
```

For other clusters, publish these images to a registry and replace the image names in `aexa-app.yaml`.

Create the required secret out-of-band. Do not put credentials in this manifest or commit them:

```powershell
kubectl create namespace aexa
kubectl -n aexa create secret generic aexa-secrets `
  --from-literal=POSTGRES_PASSWORD='<database-password>' `
  --from-literal=AEXA_JWT_SECRET='<random-secret-at-least-32-characters>' `
  --from-literal=AEXA_ADMIN_USERNAME='<admin-user>' `
  --from-literal=AEXA_ADMIN_PASSWORD='<admin-password>' `
  --from-literal=AEXA_ANALYST_USERNAME='<analyst-user>' `
  --from-literal=AEXA_ANALYST_PASSWORD='<analyst-password>'
kubectl apply -f infra\k8s\aexa-app.yaml
kubectl -n aexa rollout status deployment/ml-service
kubectl -n aexa rollout status deployment/backend
kubectl -n aexa rollout status deployment/frontend
```

The manifest creates the namespace itself; if already created, the `kubectl create namespace` command may report `AlreadyExists`, which is safe to ignore. Check external service DNS names, TLS/network policies, secret rotation, persistent storage, and the actual cluster's resource capacity before using this scaffold. No Kubernetes cluster deployment or performance result is claimed.
