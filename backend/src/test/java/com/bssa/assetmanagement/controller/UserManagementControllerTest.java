package com.bssa.assetmanagement.controller;

import com.bssa.assetmanagement.entity.AppUser;
import com.bssa.assetmanagement.entity.Department;
import com.bssa.assetmanagement.entity.Role;
import com.bssa.assetmanagement.repository.AppUserRepository;
import com.bssa.assetmanagement.repository.DepartmentRepository;
import com.bssa.assetmanagement.repository.RoleRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class UserManagementControllerTest {

    private final AppUserRepository appUserRepository =
            mock(AppUserRepository.class);

    private final DepartmentRepository departmentRepository =
            mock(DepartmentRepository.class);

    private final RoleRepository roleRepository =
            mock(RoleRepository.class);

    private final PasswordEncoder passwordEncoder =
            mock(PasswordEncoder.class);

    private final UserManagementController controller =
            new UserManagementController(
                    appUserRepository,
                    departmentRepository,
                    roleRepository,
                    passwordEncoder
            );

    @Test
    void shouldRejectSecondInventoryAdminWhenExistingAdminIsActive() {
        verifyDuplicateInventoryAdminIsRejected(true);
    }

    @Test
    void shouldRejectSecondInventoryAdminWhenExistingAdminIsInactive() {
        verifyDuplicateInventoryAdminIsRejected(false);
    }

    private void verifyDuplicateInventoryAdminIsRejected(
            boolean existingAdminActive) {

        // Arrange: an existing Inventory Admin account.
        Role adminRole = new Role();
        adminRole.setRoleId(10L);
        adminRole.setName("INVENTORY_ADMIN");

        AppUser existingAdmin = new AppUser();
        existingAdmin.setEmployeeCode("EXISTING001");
        existingAdmin.setEmail("existing.admin@example.com");
        existingAdmin.setActive(existingAdminActive);
        existingAdmin.setRoles(Set.of(adminRole));

        // Arrange: valid department and role selection.
        Department department = new Department();
        department.setDepartmentId(1L);
        department.setName("Administration");
        department.setActive(true);

        when(appUserRepository.existsByEmployeeCodeIgnoreCase(anyString()))
                .thenReturn(false);

        when(appUserRepository.existsByEmailIgnoreCase(anyString()))
                .thenReturn(false);

        when(departmentRepository.findById(1L))
                .thenReturn(Optional.of(department));

        when(roleRepository.findAllById(any()))
                .thenReturn(List.of(adminRole));

        when(appUserRepository.findAll())
                .thenReturn(List.of(existingAdmin));

        var authentication =
                new UsernamePasswordAuthenticationToken(
                        "system-admin",
                        "test",
                        List.of(
                                new SimpleGrantedAuthority("ROLE_SYSTEM_ADMIN")
                        )
                );

        RedirectAttributesModelMap redirectAttributes =
                new RedirectAttributesModelMap();

        // Act: attempt to create another Inventory Admin.
        String result = controller.createUser(
                "NEWADMIN001",
                "New",
                "Administrator",
                "new.admin@example.com",
                "StrongPass123",
                null,
                1L,
                null,
                List.of(10L),
                authentication,
                redirectAttributes
        );

        // Assert: the request is rejected and nothing is saved.
        assertEquals("redirect:/users/new", result);

        assertEquals(
                "An Inventory Admin account already exists.",
                redirectAttributes.getFlashAttributes().get("errorMessage")
        );

        verify(appUserRepository, never()).saveAndFlush(any(AppUser.class));
    }
}
