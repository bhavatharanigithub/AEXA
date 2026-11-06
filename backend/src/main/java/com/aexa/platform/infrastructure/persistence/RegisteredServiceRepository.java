package com.aexa.platform.infrastructure.persistence;

import com.aexa.platform.domain.catalog.RegisteredService;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface RegisteredServiceRepository extends JpaRepository<RegisteredService, UUID> {

    boolean existsByNameAndEnvironment(String name, String environment);

    Optional<RegisteredService> findByNameAndEnvironment(String name, String environment);
}
