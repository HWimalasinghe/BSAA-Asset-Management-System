package com.bssa.assetmanagement.controller;

import com.bssa.assetmanagement.entity.AppUser;
import com.bssa.assetmanagement.entity.Department;
import com.bssa.assetmanagement.entity.Role;
import com.bssa.assetmanagement.repository.AppUserRepository;
import com.bssa.assetmanagement.repository.DepartmentRepository;
import com.bssa.assetmanagement.repository.RoleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class UserManagementControllerTest {

    private final AppUserRepository appUserRepository = mock(AppUserRepository.class);
    private final DepartmentRepository departmentRepository = mock(DepartmentRepository.class);
    private final RoleRepository roleRepository = mock(RoleRepository.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);

    private final UserManagementController controller = new UserManagementController(
            appUserRepository,
            departmentRepository,
            roleRepository,
            passwordEncoder
    );

    private Role inventoryAdminRole;
    private Role officeStaffRole;
    private Role inventoryManagerRole;
    private Department activeDepartment;

    @BeforeEach
    void setUp() {
        reset(appUserRepository, departmentRepository, roleRepository, passwordEncoder);

        inventoryAdminRole = new Role();
        inventoryAdminRole.setRoleId(1L);
        inventoryAdminRole.setName("INVENTORY_ADMIN");

        officeStaffRole = new Role();
        officeStaffRole.setRoleId(2L);
        officeStaffRole.setName("OFFICE_STAFF");

        inventoryManagerRole = new Role();
        inventoryManagerRole.setRoleId(3L);
        inventoryManagerRole.setName("INVENTORY_MANAGER");

        activeDepartment = new Department();
        activeDepartment.setDepartmentId(1L);
        activeDepartment.setName("Information Technology");
        activeDepartment.setActive(true);

        when(passwordEncoder.encode(anyString())).thenReturn("encodedPassword123");
    }

    private UsernamePasswordAuthenticationToken createAuth(String email, String role) {
        return new UsernamePasswordAuthenticationToken(
                email,
                "test",
                List.of(new SimpleGrantedAuthority("ROLE_" + role))
        );
    }

    @Test
    void shouldRejectSecondInventoryAdminWhenExistingAdminIsActive() {
        verifyDuplicateInventoryAdminIsRejected(true);
    }

    @Test
    void shouldRejectSecondInventoryAdminWhenExistingAdminIsInactive() {
        verifyDuplicateInventoryAdminIsRejected(false);
    }

    private void verifyDuplicateInventoryAdminIsRejected(boolean existingAdminActive) {
        AppUser existingAdmin = new AppUser();
        existingAdmin.setEmployeeCode("EXISTING001");
        existingAdmin.setEmail("existing.admin@example.com");
        existingAdmin.setActive(existingAdminActive);
        existingAdmin.setRoles(Set.of(inventoryAdminRole));

        when(appUserRepository.existsByEmployeeCodeIgnoreCase(anyString())).thenReturn(false);
        when(appUserRepository.existsByEmailIgnoreCase(anyString())).thenReturn(false);
        when(departmentRepository.findById(1L)).thenReturn(Optional.of(activeDepartment));
        when(roleRepository.findAllById(any())).thenReturn(List.of(inventoryAdminRole));
        when(roleRepository.findByNameForUpdate("INVENTORY_ADMIN")).thenReturn(Optional.of(inventoryAdminRole));
        when(appUserRepository.findAll()).thenReturn(List.of(existingAdmin));

        var authentication = createAuth("system-admin@example.com", "SYSTEM_ADMIN");
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        String result = controller.createUser(
                "NEWADMIN001", "New", "Administrator", "new.admin@example.com",
                "StrongPass123", null, 1L, null, List.of(1L),
                authentication, redirectAttributes
        );

        assertEquals("redirect:/users/new", result);
        assertEquals("An Inventory Admin account already exists.", redirectAttributes.getFlashAttributes().get("errorMessage"));
        verify(appUserRepository, never()).saveAndFlush(any(AppUser.class));
    }

    @Test
    void shouldAllowSystemAdminToCreateFirstInventoryAdmin() {
        when(appUserRepository.existsByEmployeeCodeIgnoreCase("IA001")).thenReturn(false);
        when(appUserRepository.existsByEmailIgnoreCase("ia@example.com")).thenReturn(false);
        when(departmentRepository.findById(1L)).thenReturn(Optional.of(activeDepartment));
        when(roleRepository.findAllById(Set.of(1L))).thenReturn(List.of(inventoryAdminRole));
        when(roleRepository.findByNameForUpdate("INVENTORY_ADMIN")).thenReturn(Optional.of(inventoryAdminRole));
        when(appUserRepository.findAll()).thenReturn(Collections.emptyList());

        var authentication = createAuth("system-admin@example.com", "SYSTEM_ADMIN");
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        String result = controller.createUser(
                "IA001", "Admin", "User", "ia@example.com",
                "StrongPass123", "0771234567", 1L, "Head of Inventory", List.of(1L),
                authentication, redirectAttributes
        );

        assertEquals("redirect:/users", result);
        assertEquals("User account created successfully.", redirectAttributes.getFlashAttributes().get("successMessage"));
        verify(appUserRepository).saveAndFlush(any(AppUser.class));
    }

    @Test
    void shouldAllowInventoryAdminToCreateStaffUser() {
        when(appUserRepository.existsByEmployeeCodeIgnoreCase("STAFF001")).thenReturn(false);
        when(appUserRepository.existsByEmailIgnoreCase("staff@example.com")).thenReturn(false);
        when(departmentRepository.findById(1L)).thenReturn(Optional.of(activeDepartment));
        when(roleRepository.findAllById(Set.of(2L))).thenReturn(List.of(officeStaffRole));

        var authentication = createAuth("inv-admin@example.com", "INVENTORY_ADMIN");
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        String result = controller.createUser(
                "STAFF001", "John", "Doe", "staff@example.com",
                "Password123", null, 1L, "Officer", List.of(2L),
                authentication, redirectAttributes
        );

        assertEquals("redirect:/users", result);
        assertEquals("User account created successfully.", redirectAttributes.getFlashAttributes().get("successMessage"));
        verify(appUserRepository).saveAndFlush(any(AppUser.class));
    }

    @Test
    void shouldAllowInventoryAdminToCreateInventoryManager() {
        when(appUserRepository.existsByEmployeeCodeIgnoreCase("MGR001")).thenReturn(false);
        when(appUserRepository.existsByEmailIgnoreCase("mgr@example.com")).thenReturn(false);
        when(departmentRepository.findById(1L)).thenReturn(Optional.of(activeDepartment));
        when(roleRepository.findAllById(Set.of(3L))).thenReturn(List.of(inventoryManagerRole));

        var authentication = createAuth("inv-admin@example.com", "INVENTORY_ADMIN");
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        String result = controller.createUser(
                "MGR001", "Jane", "Smith", "mgr@example.com",
                "Password123", null, 1L, "Warehouse Manager", List.of(3L),
                authentication, redirectAttributes
        );

        assertEquals("redirect:/users", result);
        assertEquals("User account created successfully.", redirectAttributes.getFlashAttributes().get("successMessage"));
        verify(appUserRepository).saveAndFlush(any(AppUser.class));
    }

    @Test
    void shouldRejectInventoryAdminAttemptingToCreateInventoryAdmin() {
        when(appUserRepository.existsByEmployeeCodeIgnoreCase("IA002")).thenReturn(false);
        when(appUserRepository.existsByEmailIgnoreCase("ia2@example.com")).thenReturn(false);
        when(departmentRepository.findById(1L)).thenReturn(Optional.of(activeDepartment));
        when(roleRepository.findAllById(Set.of(1L))).thenReturn(List.of(inventoryAdminRole));

        var authentication = createAuth("inv-admin@example.com", "INVENTORY_ADMIN");
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        String result = controller.createUser(
                "IA002", "Jane", "Smith", "ia2@example.com",
                "Password123", null, 1L, null, List.of(1L),
                authentication, redirectAttributes
        );

        assertEquals("redirect:/users/new", result);
        assertEquals("You are not permitted to assign the selected role.", redirectAttributes.getFlashAttributes().get("errorMessage"));
        verify(appUserRepository, never()).saveAndFlush(any(AppUser.class));
    }

    @Test
    void shouldRejectSystemAdminAttemptingToCreateStaff() {
        when(appUserRepository.existsByEmployeeCodeIgnoreCase("STAFF002")).thenReturn(false);
        when(appUserRepository.existsByEmailIgnoreCase("staff2@example.com")).thenReturn(false);
        when(departmentRepository.findById(1L)).thenReturn(Optional.of(activeDepartment));
        when(roleRepository.findAllById(Set.of(2L))).thenReturn(List.of(officeStaffRole));

        var authentication = createAuth("sysadmin@example.com", "SYSTEM_ADMIN");
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        String result = controller.createUser(
                "STAFF002", "Jack", "Black", "staff2@example.com",
                "Password123", null, 1L, null, List.of(2L),
                authentication, redirectAttributes
        );

        assertEquals("redirect:/users/new", result);
        assertEquals("You are not permitted to assign the selected role.", redirectAttributes.getFlashAttributes().get("errorMessage"));
        verify(appUserRepository, never()).saveAndFlush(any(AppUser.class));
    }

    @Test
    void shouldRejectDuplicateEmployeeCode() {
        when(appUserRepository.existsByEmployeeCodeIgnoreCase("STAFF001")).thenReturn(true);

        var authentication = createAuth("inv-admin@example.com", "INVENTORY_ADMIN");
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        String result = controller.createUser(
                "STAFF001", "John", "Doe", "john@example.com",
                "Password123", null, 1L, null, List.of(2L),
                authentication, redirectAttributes
        );

        assertEquals("redirect:/users/new", result);
        assertEquals("An account with this employee code already exists.", redirectAttributes.getFlashAttributes().get("errorMessage"));
        verify(appUserRepository, never()).saveAndFlush(any(AppUser.class));
    }

    @Test
    void shouldRejectDuplicateEmail() {
        when(appUserRepository.existsByEmployeeCodeIgnoreCase("STAFF001")).thenReturn(false);
        when(appUserRepository.existsByEmailIgnoreCase("staff@example.com")).thenReturn(true);

        var authentication = createAuth("inv-admin@example.com", "INVENTORY_ADMIN");
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        String result = controller.createUser(
                "STAFF001", "John", "Doe", "staff@example.com",
                "Password123", null, 1L, null, List.of(2L),
                authentication, redirectAttributes
        );

        assertEquals("redirect:/users/new", result);
        assertEquals("An account with this email address already exists.", redirectAttributes.getFlashAttributes().get("errorMessage"));
        verify(appUserRepository, never()).saveAndFlush(any(AppUser.class));
    }

    @Test
    void shouldRejectInvalidEmailFormat() {
        var authentication = createAuth("inv-admin@example.com", "INVENTORY_ADMIN");
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        String result = controller.createUser(
                "STAFF001", "John", "Doe", "not-a-valid-email",
                "Password123", null, 1L, null, List.of(2L),
                authentication, redirectAttributes
        );

        assertEquals("redirect:/users/new", result);
        assertEquals("Please provide a valid email address.", redirectAttributes.getFlashAttributes().get("errorMessage"));
        verify(appUserRepository, never()).saveAndFlush(any(AppUser.class));
    }

    @Test
    void shouldRejectShortPassword() {
        var authentication = createAuth("inv-admin@example.com", "INVENTORY_ADMIN");
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        String result = controller.createUser(
                "STAFF001", "John", "Doe", "staff@example.com",
                "short", null, 1L, null, List.of(2L),
                authentication, redirectAttributes
        );

        assertEquals("redirect:/users/new", result);
        assertEquals("Password must contain between 8 and 100 characters.", redirectAttributes.getFlashAttributes().get("errorMessage"));
        verify(appUserRepository, never()).saveAndFlush(any(AppUser.class));
    }

    @Test
    void shouldRejectInactiveDepartment() {
        when(appUserRepository.existsByEmployeeCodeIgnoreCase("STAFF001")).thenReturn(false);
        when(appUserRepository.existsByEmailIgnoreCase("staff@example.com")).thenReturn(false);

        Department inactiveDept = new Department();
        inactiveDept.setDepartmentId(99L);
        inactiveDept.setActive(false);
        when(departmentRepository.findById(99L)).thenReturn(Optional.of(inactiveDept));

        var authentication = createAuth("inv-admin@example.com", "INVENTORY_ADMIN");
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        String result = controller.createUser(
                "STAFF001", "John", "Doe", "staff@example.com",
                "Password123", null, 99L, null, List.of(2L),
                authentication, redirectAttributes
        );

        assertEquals("redirect:/users/new", result);
        assertEquals("Please select an active department.", redirectAttributes.getFlashAttributes().get("errorMessage"));
        verify(appUserRepository, never()).saveAndFlush(any(AppUser.class));
    }

    @Test
    void shouldRejectInactiveCaller() {
        AppUser inactiveCaller = new AppUser();
        inactiveCaller.setEmail("inv-admin@example.com");
        inactiveCaller.setActive(false);
        when(appUserRepository.findByEmail("inv-admin@example.com")).thenReturn(Optional.of(inactiveCaller));

        var authentication = createAuth("inv-admin@example.com", "INVENTORY_ADMIN");
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        String result = controller.createUser(
                "STAFF001", "John", "Doe", "staff@example.com",
                "Password123", null, 1L, null, List.of(2L),
                authentication, redirectAttributes
        );

        assertEquals("redirect:/login?logout", result);
        assertEquals("Your account has been deactivated.", redirectAttributes.getFlashAttributes().get("errorMessage"));
        verify(appUserRepository, never()).saveAndFlush(any(AppUser.class));
    }

    @Test
    void shouldPreventUserFromDeactivatingOwnAccount() {
        AppUser caller = new AppUser();
        caller.setUserId(5L);
        caller.setEmail("admin@example.com");
        caller.setActive(true);
        caller.setRoles(Set.of(inventoryAdminRole));

        when(appUserRepository.findById(5L)).thenReturn(Optional.of(caller));

        var authentication = createAuth("admin@example.com", "SYSTEM_ADMIN");
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        String result = controller.updateUserStatus(5L, false, authentication, redirectAttributes);

        assertEquals("redirect:/users", result);
        assertEquals("You cannot deactivate your own account.", redirectAttributes.getFlashAttributes().get("errorMessage"));
        verify(appUserRepository, never()).save(any(AppUser.class));
    }

    @Test
    void shouldPreventInventoryAdminFromManagingSystemAdmin() {
        Role sysAdminRole = new Role();
        sysAdminRole.setRoleId(99L);
        sysAdminRole.setName("SYSTEM_ADMIN");

        AppUser sysAdmin = new AppUser();
        sysAdmin.setUserId(10L);
        sysAdmin.setEmail("sysadmin@example.com");
        sysAdmin.setActive(true);
        sysAdmin.setRoles(Set.of(sysAdminRole));

        when(appUserRepository.findById(10L)).thenReturn(Optional.of(sysAdmin));

        var authentication = createAuth("inv-admin@example.com", "INVENTORY_ADMIN");
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        String result = controller.updateUserStatus(10L, false, authentication, redirectAttributes);

        assertEquals("redirect:/users", result);
        assertEquals("You are not permitted to change this account's status.", redirectAttributes.getFlashAttributes().get("errorMessage"));
        verify(appUserRepository, never()).save(any(AppUser.class));
    }

    @Test
    void shouldPreventSystemAdminFromManagingStaff() {
        AppUser staffUser = new AppUser();
        staffUser.setUserId(20L);
        staffUser.setEmail("staff@example.com");
        staffUser.setActive(true);
        staffUser.setRoles(Set.of(officeStaffRole));

        when(appUserRepository.findById(20L)).thenReturn(Optional.of(staffUser));

        var authentication = createAuth("sysadmin@example.com", "SYSTEM_ADMIN");
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        String result = controller.updateUserStatus(20L, false, authentication, redirectAttributes);

        assertEquals("redirect:/users", result);
        assertEquals("You are not permitted to change this account's status.", redirectAttributes.getFlashAttributes().get("errorMessage"));
        verify(appUserRepository, never()).save(any(AppUser.class));
    }

    @Test
    void shouldAllowInventoryAdminToUpdateStaff() {
        AppUser staffUser = new AppUser();
        staffUser.setUserId(20L);
        staffUser.setEmployeeCode("STAFF001");
        staffUser.setEmail("staff@example.com");
        staffUser.setFirstName("Old");
        staffUser.setLastName("Name");
        staffUser.setActive(true);
        staffUser.setRoles(Set.of(officeStaffRole));

        when(appUserRepository.findById(20L)).thenReturn(Optional.of(staffUser));
        when(departmentRepository.findById(1L)).thenReturn(Optional.of(activeDepartment));
        when(roleRepository.findAllById(Set.of(2L))).thenReturn(List.of(officeStaffRole));

        var authentication = createAuth("inv-admin@example.com", "INVENTORY_ADMIN");
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        String result = controller.updateUser(
                20L, "STAFF001", "NewFirst", "NewLast", "staff@example.com",
                "0779998877", 1L, "Senior Officer", List.of(2L),
                authentication, redirectAttributes
        );

        assertEquals("redirect:/users", result);
        assertEquals("User account updated successfully.", redirectAttributes.getFlashAttributes().get("successMessage"));
        assertEquals("NewFirst", staffUser.getFirstName());
        assertEquals("NewLast", staffUser.getLastName());
        verify(appUserRepository).saveAndFlush(staffUser);
    }

    @Test
    void shouldAllowInventoryAdminToToggleStaffStatus() {
        AppUser staffUser = new AppUser();
        staffUser.setUserId(20L);
        staffUser.setEmail("staff@example.com");
        staffUser.setActive(true);
        staffUser.setRoles(Set.of(officeStaffRole));

        when(appUserRepository.findById(20L)).thenReturn(Optional.of(staffUser));

        var authentication = createAuth("inv-admin@example.com", "INVENTORY_ADMIN");
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        String result = controller.updateUserStatus(20L, false, authentication, redirectAttributes);

        assertEquals("redirect:/users", result);
        assertEquals("User account deactivated successfully.", redirectAttributes.getFlashAttributes().get("successMessage"));
        assertTrue(!staffUser.isActive());
        verify(appUserRepository).save(staffUser);
    }
}
