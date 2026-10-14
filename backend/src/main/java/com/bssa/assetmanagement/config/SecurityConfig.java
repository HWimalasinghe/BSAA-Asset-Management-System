package com.bssa.assetmanagement.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http)
            throws Exception {

        http
            .authorizeHttpRequests(authorize -> authorize
                .requestMatchers(
                    "/login",
                    "/css/**",
                    "/images/**",
                    "/api/health"
                ).permitAll()

                .requestMatchers("/admin", "/admin/**")
                    .hasRole("SYSTEM_ADMIN")

                .requestMatchers("/inventory-admin", "/inventory-admin/**")
                    .hasAnyRole("SYSTEM_ADMIN", "INVENTORY_ADMIN")

                .requestMatchers("/departments", "/departments/**")
                    .hasAnyRole("SYSTEM_ADMIN", "INVENTORY_ADMIN")
                
                .requestMatchers("/inventory-manager", "/inventory-manager/**")
                    .hasAnyRole(
                        "SYSTEM_ADMIN",
                        "INVENTORY_ADMIN",
                        "INVENTORY_MANAGER"
                    )

                .requestMatchers("/office-staff", "/office-staff/**")
                    .hasAnyRole(
                        "SYSTEM_ADMIN",
                        "OFFICE_STAFF"
                    )

                .anyRequest().authenticated()
            )
            .formLogin(form -> form
                .loginPage("/login")
                .usernameParameter("username")
                .passwordParameter("password")
                .defaultSuccessUrl("/", true)
                .permitAll()
            )
            .logout(logout -> logout
                .logoutSuccessUrl("/login?logout")
                .permitAll()
            );

        return http.build();
    }
}
