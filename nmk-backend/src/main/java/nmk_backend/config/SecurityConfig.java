package nmk_backend.config;

import nmk_backend.security.JwtAuthenticationFilter;
import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    @Value("${app.cors.allowed-origins:http://localhost:5173,http://localhost:5174}")
    private String allowedOrigins;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .cors(cors -> {})
                .csrf(csrf -> csrf.disable())
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers("/error").permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/admin/auth/login").permitAll()

                        // Register configuration is administrator-only.
                        .requestMatchers(HttpMethod.POST, "/api/admin/pos/registers")
                            .hasAuthority("ROLE_ADMIN")
                        // Sales and refund reporting is manager/admin only.
                        .requestMatchers(HttpMethod.GET, "/api/admin/pos/reports/**")
                            .hasAnyAuthority("ROLE_ADMIN", "ROLE_MANAGER")
                        // Returns/refunds require manager or administrator authority.
                        .requestMatchers(HttpMethod.POST, "/api/admin/pos/sales/*/returns")
                            .hasAnyAuthority("ROLE_ADMIN", "ROLE_MANAGER")
                        // Cashiers may sell but cannot browse the full sales ledger.
                        .requestMatchers(HttpMethod.GET, "/api/admin/pos/sales", "/api/admin/pos/sales/**")
                            .hasAnyAuthority("ROLE_ADMIN", "ROLE_MANAGER")
                        .requestMatchers(HttpMethod.GET,
                                "/api/admin/pos/locations",
                                "/api/admin/pos/catalog",
                                "/api/admin/pos/registers")
                            .hasAnyAuthority("ROLE_ADMIN", "ROLE_MANAGER", "ROLE_CASHIER")
                        .requestMatchers(HttpMethod.POST,
                                "/api/admin/pos/checkout",
                                "/api/admin/pos/shifts/**")
                            .hasAnyAuthority("ROLE_ADMIN", "ROLE_MANAGER", "ROLE_CASHIER")
                        .requestMatchers(HttpMethod.GET, "/api/admin/pos/shifts/**")
                            .hasAnyAuthority("ROLE_ADMIN", "ROLE_MANAGER", "ROLE_CASHIER")
                        .requestMatchers("/api/admin/inventory/**")
                            .hasAnyAuthority("ROLE_ADMIN", "ROLE_MANAGER")
                        .requestMatchers("/api/admin/procurement/**")
                            .hasAnyAuthority("ROLE_ADMIN", "ROLE_MANAGER")

                        .requestMatchers("/api/admin/**").hasRole("ADMIN")

                        .requestMatchers(HttpMethod.GET, "/api/products", "/api/products/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/products", "/api/products/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/products", "/api/products/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PATCH, "/api/products", "/api/products/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/products", "/api/products/**").hasRole("ADMIN")

                        .requestMatchers(
                                HttpMethod.GET,
                                "/api/marketing/banners",
                                "/api/marketing/offer",
                                "/api/website-images"
                        ).permitAll()

                        .requestMatchers(
                                "/", "/index.html", "/admin", "/admin/**",
                                "/track", "/track/**", "/assets/**", "/favicon.ico"
                        ).permitAll()

                        .requestMatchers("/api/payment/**").permitAll()
                        .requestMatchers("/api/orders/checkout").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/orders/track/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/appointments").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/appointments", "/api/appointments/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/appointments/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/appointments/**").hasRole("ADMIN")
                        .anyRequest().authenticated()
                )
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        List<String> origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toList();
        configuration.setAllowedOrigins(origins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}