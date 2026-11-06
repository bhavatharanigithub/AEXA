package com.aexa.platform.application;

import com.aexa.platform.api.dto.LoginRequest;
import com.aexa.platform.api.dto.LoginResponse;
import com.aexa.platform.api.error.ApiException;
import com.aexa.platform.infrastructure.persistence.AppUserRepository;
import com.aexa.platform.infrastructure.security.JwtService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(AppUserRepository userRepository, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    public LoginResponse login(LoginRequest request) {
        var user = userRepository
                .findByUsername(request.username())
                .orElseThrow(() -> ApiException.unauthorized("Invalid username or password"));
        if (!user.isEnabled() || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw ApiException.unauthorized("Invalid username or password");
        }
        return new LoginResponse(
                jwtService.issueToken(user), "Bearer", jwtService.expirySeconds(), user.getUsername(), user.getRole().name());
    }
}
