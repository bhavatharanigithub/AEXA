package com.aexa.platform.application;

import com.aexa.platform.config.AexaProperties;
import com.aexa.platform.domain.catalog.RegisteredService;
import com.aexa.platform.domain.user.AppUser;
import com.aexa.platform.domain.user.UserRole;
import com.aexa.platform.infrastructure.persistence.AppUserRepository;
import com.aexa.platform.infrastructure.persistence.RegisteredServiceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class PlatformBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PlatformBootstrap.class);

    private final AppUserRepository userRepository;
    private final RegisteredServiceRepository serviceRepository;
    private final PasswordEncoder passwordEncoder;
    private final AexaProperties properties;

    public PlatformBootstrap(
            AppUserRepository userRepository,
            RegisteredServiceRepository serviceRepository,
            PasswordEncoder passwordEncoder,
            AexaProperties properties) {
        this.userRepository = userRepository;
        this.serviceRepository = serviceRepository;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        ensureUser(
                properties.getBootstrap().getAdminUsername(),
                properties.getBootstrap().getAdminUsername() + "@aexa.local",
                properties.getBootstrap().getAdminPassword(),
                UserRole.ADMIN);
        ensureUser(
                properties.getBootstrap().getAnalystUsername(),
                properties.getBootstrap().getAnalystUsername() + "@aexa.local",
                properties.getBootstrap().getAnalystPassword(),
                UserRole.ANALYST);
        if (properties.getBootstrap().isSeedCatalog()) {
            seedService(
                    "checkout-api",
                    "Checkout API",
                    "Instrumented demo workload. Traffic is synthetic.");
            seedService(
                    "inventory-api",
                    "Inventory API",
                    "Instrumented demo workload. Traffic is synthetic.");
            seedService(
                    "pricing-api",
                    "Pricing API",
                    "Instrumented demo workload. Traffic is synthetic.");
        }
    }

    private void ensureUser(String username, String email, String rawPassword, UserRole role) {
        userRepository.findByUsername(username).orElseGet(() -> {
            log.info("Bootstrapping local user '{}' with role {}", username, role);
            return userRepository.save(AppUser.create(username, email, passwordEncoder.encode(rawPassword), role));
        });
    }

    private void seedService(String name, String displayName, String description) {
        serviceRepository.findByNameAndEnvironment(name, "demo").orElseGet(() -> serviceRepository.save(
                RegisteredService.create(name, "demo", "demo", "0.2.0", displayName, description, "platform")));
    }
}
