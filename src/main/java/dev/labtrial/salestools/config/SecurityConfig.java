package dev.labtrial.salestools.config;

import dev.labtrial.salestools.identity.AccessControl;
import dev.labtrial.salestools.identity.SessionValidationFilter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfig {
    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    SecurityFilterChain filterChain(HttpSecurity http, AccessControl access) throws Exception {
        http.authorizeHttpRequests(auth -> auth.requestMatchers("/health", "/login", "/error", "/styles.css").permitAll().anyRequest().authenticated())
            .requestCache(cache -> cache.disable())
            .formLogin(form -> form.loginPage("/login").successHandler((request, response, authentication) -> {
                var session = request.getSession(true);
                session.setMaxInactiveInterval(30 * 60);
                session.setAttribute(SessionValidationFilter.LOGIN_TIME, System.currentTimeMillis());
                response.sendRedirect(request.getContextPath() + "/workspaces");
            }).permitAll())
            .sessionManagement(session -> session.sessionFixation(fixation -> fixation.changeSessionId()))
            .logout(logout -> logout.logoutSuccessUrl("/login?logout").invalidateHttpSession(true).deleteCookies("JSESSIONID"))
            .addFilterAfter(new SessionValidationFilter(access), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
