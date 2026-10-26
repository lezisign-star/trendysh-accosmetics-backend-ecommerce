package com.nbh.backend.common.config;

import com.nbh.backend.common.security.JwtAuthFilter;
import com.nbh.backend.staff.service.StaffUserDetailsService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.expression.WebExpressionAuthorizationManager;
import com.nbh.backend.customer.entity.CustomerAccount;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/** Adapted from Moments Packaging's SecurityConfig — trimmed to what NBH's staff-only auth needs so far. */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final StaffUserDetailsService staffUserDetailsService;

    @Value("${app.cors.allowed-origins:http://localhost:5173}")
    private String allowedOriginsRaw;

    public SecurityConfig(JwtAuthFilter jwtAuthFilter, StaffUserDetailsService staffUserDetailsService) {
        this.jwtAuthFilter = jwtAuthFilter;
        this.staffUserDetailsService = staffUserDetailsService;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // A live-event stream (SseEmitter) is re-dispatched by the container when it ends or the client goes
                // away. That second dispatch carries no login (the token filter runs once, on the original request), so
                // it was refused and logged as an ERROR stack trace each time a screen closed. The original request was
                // already authorised; method-level @PreAuthorize still guards every endpoint.
                .dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ASYNC).permitAll()
                .requestMatchers(
                    // Only the login itself is public; /auth/me and /auth/change-password are
                    // staff-only and need a token (they read the staff principal).
                    "/api/v1/auth/login",
                    // Customer sign-up / sign-in / emailed-link flows. Everything else under
                    // /api/v1/customer/** needs a customer token (rule just below).
                    "/api/v1/customer/auth/register",
                    "/api/v1/customer/auth/login",
                    "/api/v1/customer/auth/refresh",
                    "/api/v1/customer/auth/logout",
                    "/api/v1/customer/auth/verify-email",
                    "/api/v1/customer/auth/forgot-password",
                    "/api/v1/customer/auth/reset-password",
                    "/api/v1/cart/**",
                    "/api/v1/checkout",
                    "/api/v1/checkout/**",
                    "/api/v1/orders/track/**",
                    "/api/v1/payments/mpesa/callback/**",
                    // Customer's bank pay page: authorised by order number + payment reference.
                    "/api/v1/payments/pay/**",
                    // The paybill details the checkout's payment step previews before an order exists.
                    "/api/v1/payments/paybill-info",
                    // TumaBoda: the webhook authenticates itself by HMAC signature; the quote and address
                    // lookups back the checkout's delivery step and are rate-limited per IP.
                    "/api/v1/tumaboda/webhook",
                    "/api/v1/public/tumaboda/**",
                    // Storefront live hints and the delivery places/price comparison + suggestion form.
                    "/api/v1/public/events",
                    "/api/v1/public/delivery/**",
                    // Co-op Bank deposit notifications: authenticated inside the controller (shared Basic credential).
                    "/api/v1/payments/coop/ins",
                    // Public storefront browse/search/reviews — read-only, only
                    // ever returns published products (CatalogService's javadoc).
                    // Review submission (POST /catalog/products/{id}/reviews) is
                    // also here: it proves eligibility itself via order+phone,
                    // the same way checkout is guest-only with no login to gate on.
                    "/api/v1/catalog/**",
                    "/swagger-ui.html",
                    "/swagger-ui/**",
                    "/api-docs/**",
                    "/actuator/health"
                ).permitAll()
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                .requestMatchers("/api/v1/customer/**").hasAuthority(CustomerAccount.AUTHORITY)
                // Defence in depth: staff endpoints already demand a PERM_* authority via
                // @PreAuthorize, but a shopper's token must never reach anything else that
                // is merely "authenticated" — including endpoints added later and forgotten.
                .anyRequest().access(new WebExpressionAuthorizationManager(
                        "isAuthenticated() and !hasAuthority('" + CustomerAccount.AUTHORITY + "')"))
            )
            .authenticationProvider(authenticationProvider())
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public AuthenticationProvider authenticationProvider() {
        // §1.5 Gap 4 fix (Boot 3.5.16 bump, Spring Security 6.5.11): the
        // no-arg constructor + setUserDetailsService(...) pair is deprecated
        // in favour of passing the UserDetailsService directly to the
        // constructor — confirmed via javap against the actually-resolved
        // 6.5.11 jar (setPasswordEncoder is NOT deprecated, left as a setter).
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(staffUserDetailsService);
        provider.setPasswordEncoder(passwordEncoder());
        return provider;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    /**
     * §1.5 Gap 5 fix: a bare {@code new BCryptPasswordEncoder()} stores hashes
     * with no {@code {id}} prefix, which means the algorithm can never be
     * changed later without forcing a password reset on every account. New
     * hashes are written as {@code {bcrypt}<hash>} from here on (still BCrypt —
     * this makes a FUTURE algorithm switch possible, it does not change today's
     * algorithm). {@code setDefaultPasswordEncoderForMatches} is required, not
     * optional: {@link PasswordEncoderFactories#createDelegatingPasswordEncoder()}
     * on its own throws "There is no PasswordEncoder mapped for the id null" the
     * first time it tries to verify an EXISTING bare-BCrypt hash (every account
     * created before this fix, including the seeded bootstrap SUPER_ADMIN) —
     * this line is what keeps those accounts able to log in.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        DelegatingPasswordEncoder encoder =
                (DelegatingPasswordEncoder) PasswordEncoderFactories.createDelegatingPasswordEncoder();
        encoder.setDefaultPasswordEncoderForMatches(new BCryptPasswordEncoder());
        return encoder;
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        // Browsers send Origin without a trailing slash or padding, so tolerate both in the env value.
        configuration.setAllowedOrigins(Arrays.stream(allowedOriginsRaw.split(","))
                .map(o -> o.trim().replaceAll("/+$", ""))
                .filter(o -> !o.isEmpty())
                .toList());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
