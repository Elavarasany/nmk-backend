package nmk_backend.security;

import nmk_backend.config.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class CorsConfigurationTest {

    @Test
    void configuresOnlyExplicitOriginsAndExpectedMethods() {
        SecurityConfig config = new SecurityConfig(mock(JwtAuthenticationFilter.class));
        ReflectionTestUtils.setField(config, "allowedOrigins",
                "https://shop.example.com, https://admin.example.com");
        ReflectionTestUtils.setField(config, "requireHttpsOrigins", true);

        CorsConfigurationSource source = config.corsConfigurationSource();
        CorsConfiguration cors = source.getCorsConfiguration(
                new org.springframework.mock.web.MockHttpServletRequest("GET", "/api/products"));

        assertNotNull(cors);
        assertEquals(
                java.util.List.of("https://shop.example.com", "https://admin.example.com"),
                cors.getAllowedOrigins());
        assertEquals(
                java.util.List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"),
                cors.getAllowedMethods());
        assertTrue(cors.getAllowCredentials());
    }

    @Test
    void rejectsWildcardOrigins() {
        SecurityConfig config = new SecurityConfig(mock(JwtAuthenticationFilter.class));
        ReflectionTestUtils.setField(config, "allowedOrigins", "*");
        ReflectionTestUtils.setField(config, "requireHttpsOrigins", false);

        assertThrows(IllegalStateException.class, config::corsConfigurationSource);
    }

    @Test
    void rejectsNonHttpsOriginsWhenProductionPolicyIsEnabled() {
        SecurityConfig config = new SecurityConfig(mock(JwtAuthenticationFilter.class));
        ReflectionTestUtils.setField(config, "allowedOrigins", "http://localhost:5173");
        ReflectionTestUtils.setField(config, "requireHttpsOrigins", true);

        assertThrows(IllegalStateException.class, config::corsConfigurationSource);
    }
}
