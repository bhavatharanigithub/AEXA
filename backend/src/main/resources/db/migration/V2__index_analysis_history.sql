CREATE INDEX idx_anomaly_service_detected ON anomaly (service_id, detected_at);
CREATE INDEX idx_prediction_service_created ON prediction (service_id, created_at);
