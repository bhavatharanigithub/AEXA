import asyncio
import os
import time
from typing import Literal

import httpx
from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import JSONResponse
from opentelemetry.instrumentation.fastapi import FastAPIInstrumentor
from opentelemetry.instrumentation.httpx import HTTPXClientInstrumentor
from pydantic import BaseModel, Field

from app.sampling import MAX_TRACE_SAMPLE_RATE, MIN_TRACE_SAMPLE_RATE
from app.telemetry import logger, meter_provider, request_counter, request_duration, trace_sampler, tracer_provider

SERVICE_NAME = os.getenv("SERVICE_NAME", "demo-service")
DOWNSTREAM_URL = os.getenv("DOWNSTREAM_URL")
app = FastAPI(title=f"Aexa demo: {SERVICE_NAME}", version="0.2.0")
app.state.fault_mode = os.getenv("DEMO_FAULT_MODE", "none")


class FaultRequest(BaseModel):
    mode: Literal["none", "latency", "error", "resource"]


class TraceSamplingRequest(BaseModel):
    trace_sample_rate: float = Field(ge=MIN_TRACE_SAMPLE_RATE, le=MAX_TRACE_SAMPLE_RATE)


@app.middleware("http")
async def record_request_metrics(request: Request, call_next):
    started = time.perf_counter()
    response = await call_next(request)
    duration = time.perf_counter() - started
    attributes = {
        "http.route": request.scope.get("route").path if request.scope.get("route") else "unmatched",
        "http.request.method": request.method,
        "status_code": str(response.status_code),
    }
    request_counter.add(1, attributes)
    request_duration.record(duration, attributes)
    return response


@app.get("/health")
async def health() -> dict[str, str | float]:
    return {
        "status": "UP",
        "service": SERVICE_NAME,
        "faultMode": app.state.fault_mode,
        "traceSampleRate": trace_sampler.sample_rate,
    }


@app.get("/internal/telemetry/sampling")
async def get_trace_sampling() -> dict[str, float | str]:
    return {"service": SERVICE_NAME, "trace_sample_rate": trace_sampler.sample_rate}


@app.put("/internal/telemetry/sampling")
async def set_trace_sampling(request: TraceSamplingRequest) -> dict[str, float | str]:
    previous_rate = trace_sampler.set_sample_rate(request.trace_sample_rate)
    logger.warning(
        "Trace sampling rate changed",
        extra={
            "sampling.previous_rate": previous_rate,
            "sampling.trace_sample_rate": trace_sampler.sample_rate,
        },
    )
    return {"service": SERVICE_NAME, "trace_sample_rate": trace_sampler.sample_rate}


@app.get("/work")
async def work() -> dict[str, object]:
    if app.state.fault_mode == "latency":
        await asyncio.sleep(1)
    if app.state.fault_mode == "resource":
        deadline = time.perf_counter() + 0.02
        workload = 1
        while time.perf_counter() < deadline:
            workload = (workload * 1_664_525 + 1_013_904_223) % 4_294_967_296
    if app.state.fault_mode == "error":
        logger.error("Controlled demo failure", extra={"service.name": SERVICE_NAME})
        raise HTTPException(status_code=503, detail="Controlled demo failure")

    downstream = None
    if DOWNSTREAM_URL:
        async with httpx.AsyncClient(timeout=3) as client:
            response = await client.get(f"{DOWNSTREAM_URL}/work")
        if response.is_error:
            logger.warning(
                "Downstream request failed",
                extra={"downstream.url": DOWNSTREAM_URL, "downstream.status_code": response.status_code},
            )
            raise HTTPException(status_code=502, detail=f"Downstream service returned {response.status_code}")
        downstream = response.json()

    logger.info("Demo request completed", extra={"service.name": SERVICE_NAME})
    return {"service": SERVICE_NAME, "downstream": downstream}


@app.get("/demo/fault")
async def get_fault_mode() -> dict[str, str]:
    return {"mode": app.state.fault_mode}


@app.put("/demo/fault")
async def set_fault_mode(request: FaultRequest) -> dict[str, str]:
    app.state.fault_mode = request.mode
    logger.warning("Demo fault mode changed", extra={"fault.mode": request.mode})
    return {"service": SERVICE_NAME, "mode": app.state.fault_mode}


@app.exception_handler(Exception)
async def unhandled_error_handler(request: Request, exc: Exception) -> JSONResponse:
    logger.exception("Unhandled demo service error", extra={"path": request.url.path})
    return JSONResponse(status_code=500, content={"detail": "Internal demo service error"})


FastAPIInstrumentor.instrument_app(app, tracer_provider=tracer_provider, meter_provider=meter_provider)
HTTPXClientInstrumentor().instrument(tracer_provider=tracer_provider)
