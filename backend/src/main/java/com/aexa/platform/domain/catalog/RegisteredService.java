package com.aexa.platform.domain.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "registered_service")
public class RegisteredService {

    @Id
    private UUID id;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(length = 128)
    private String namespace;

    @Column(nullable = false, length = 32)
    private String environment;

    @Column(length = 64)
    private String version;

    @Column(name = "display_name", nullable = false, length = 128)
    private String displayName;

    @Column(length = 1024)
    private String description;

    @Column(length = 128)
    private String owner;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static RegisteredService create(
            String name,
            String namespace,
            String environment,
            String version,
            String displayName,
            String description,
            String owner) {
        RegisteredService service = new RegisteredService();
        service.id = UUID.randomUUID();
        service.name = name;
        service.namespace = namespace;
        service.environment = environment;
        service.version = version;
        service.displayName = displayName;
        service.description = description;
        service.owner = owner;
        return service;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getNamespace() {
        return namespace;
    }

    public String getEnvironment() {
        return environment;
    }

    public String getVersion() {
        return version;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }

    public String getOwner() {
        return owner;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
