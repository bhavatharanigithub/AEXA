package com.aexa.platform.infrastructure.persistence;

import com.aexa.platform.domain.incident.Incident;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface IncidentRepository extends JpaRepository<Incident, UUID> {
}
