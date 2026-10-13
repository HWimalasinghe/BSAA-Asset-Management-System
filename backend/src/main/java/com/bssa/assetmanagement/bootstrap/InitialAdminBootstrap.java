package com.bssa.assetmanagement.bootstrap;

import com.bssa.assetmanagement.entity.AppUser;
import com.bssa.assetmanagement.entity.Role;
import com.bssa.assetmanagement.repository.AppUserRepository;
import com.bssa.assetmanagement.repository.RoleRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

@Component
@ConditionalOnProperty(
        name = "app.bootstrap-admin.enabled",
        havingValue = "true"
)
public class InitialAdminBootstrap implements CommandLineRunner {

    private static final Logger log =
            LoggerFactory.getLogger(InitialAdminBootstrap.class);

    private final AppUserRepository appUserRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final Environment environment;

    public InitialAdminBootstrap(
            AppUserRepository appUserRepository,
            RoleRepository roleRepository,
            PasswordEncoder passwordEncoder,
            Environment environment) {

        this.appUserRepository = appUserRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
        this.environment = environment;
    }

    @Override
    @Transactional
    public void run(String... args) {

        if (appUserRepository.count() > 0) {
            log.warn("Admin bootstrap skipped: users already exist.");
            return;
        }

        String employeeCode = required("BOOTSTRAP_ADMIN_EMPLOYEE_CODE");
        String firstName = required("BOOTSTRAP_ADMIN_FIRST_NAME");
        String lastName = required("BOOTSTRAP_ADMIN_LAST_NAME");
        String email = required("BOOTSTRAP_ADMIN_EMAIL");
        String password = required("BOOTSTRAP_ADMIN_PASSWORD");

        String phone = environment.getProperty("BOOTSTRAP_ADMIN_PHONE", "");
        String departmentValue =
                environment.getProperty("BOOTSTRAP_ADMIN_DEPARTMENT_ID", "1");

        Long departmentId = Long.parseLong(departmentValue);

        if (departmentId <= 0) {
            throw new IllegalArgumentException(
                    "The department ID must be a positive number.");
        }

        Role systemAdminRole = roleRepository.findByName("SYSTEM_ADMIN")
                .orElseThrow(() -> new IllegalStateException(
                        "SYSTEM_ADMIN role was not found."));

        AppUser admin = new AppUser();
        admin.setEmployeeCode(employeeCode);
        admin.setFirstName(firstName);
        admin.setLastName(lastName);
        admin.setEmail(email);
        admin.setPasswordHash(passwordEncoder.encode(password));
        admin.setPhone(phone.isBlank() ? null : phone);
        admin.setDepartmentId(departmentId);
        admin.setPosition("System Administrator");
        admin.setActive(true);
        admin.setRoles(Set.of(systemAdminRole));

        appUserRepository.saveAndFlush(admin);

        log.info("Initial System Admin account created successfully.");
    }

    private String required(String key) {
        String value = environment.getProperty(key);

        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "Required environment variable is missing: " + key);
        }

        return value.trim();
    }
}
