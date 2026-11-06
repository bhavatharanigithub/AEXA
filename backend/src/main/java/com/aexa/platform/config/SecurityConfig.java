package com.aexa.platform.config;

import com.aexa.platform.infrastructure.security.JwtAuthenticationFilter;
import com.aexa.platform.infrastructure.web.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.nio.charset.StandardCharsets;
import java.util.List;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtAuthenticationFilter jwtFilter)
            throws Exception {
        http.csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.requestMatchers(
                                "/api/v1/health",
                                "/actuator/health",
                                "/actuator/info",
                                "/api/v1/auth/login",
                                "/v3/api-docs/**",
                                "/swagger-ui/**",
                                "/swagger-ui.html",
                                "/h2-console/**")
                        .permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/services")
                        .hasRole("ADMIN")
                        .requestMatchers(
                                        HttpMethod.POST,
                                        "/api/v1/analysis/**")
                                .hasAnyRole("ADMIN", "ANALYST")
                        .requestMatchers(HttpMethod.POST, "/api/v1/ai/**")
                                .hasAnyRole("ADMIN", "ANALYST")
                        .requestMatchers(
                                        "/api/v1/adaptive/enabled",
                                        "/api/v1/adaptive/evaluate",
                                        "/api/v1/adaptive/rollback")
                                .hasRole("ADMIN")
                        .requestMatchers("/api/v1/**")
                        .hasAnyRole("ADMIN", "ANALYST", "VIEWER")
                        .anyRequest()
                        .authenticated())
                .headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()))
                .exceptionHandling(ex -> ex.authenticationEntryPoint((request, response, exception) -> {
                            writeProblem(response, HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized", "Authentication is required");
                        })
                        .accessDeniedHandler((request, response, exception) -> {
                            writeProblem(response, HttpServletResponse.SC_FORBIDDEN, "Forbidden", "Insufficient role");
                        }))
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.of("http://localhost:5173", "http://127.0.0.1:5173"));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setExposedHeaders(List.of("X-Correlation-Id"));
        configuration.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    private void writeProblem(HttpServletResponse response, int status, String title, String detail)
            throws java.io.IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        String body =
                """
                {"type":"https://aexa.dev/problems/%s","title":"%s","status":%d,"detail":"%s","instance":"","correlationId":"%s"}
                """
                        .formatted(title.toLowerCase(), title, status, detail, correlationId == null ? "" : correlationId);
        response.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
    }
}
