package com.bssa.assetmanagement.controller;

import com.bssa.assetmanagement.entity.AppUser;
import com.bssa.assetmanagement.entity.Department;
import com.bssa.assetmanagement.entity.Role;
import com.bssa.assetmanagement.repository.AppUserRepository;
import com.bssa.assetmanagement.repository.DepartmentRepository;
import com.bssa.assetmanagement.repository.RoleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    // -------------------------------------------------------------------------
    // Password storage
    // -------------------------------------------------------------------------

    /**
     * The other tests hand the controller a mocked encoder, which proves the
     * controller calls it and nothing more. This one hands it the encoder the
     * application actually configures, so the value that reaches the repository
     * is what would reach the database: a verifiable hash, and never the text
     * somebody typed.
     */
    @Test
    void aSubmittedPasswordIsStoredAsAHashAndNeverAsTheSubmittedText() {
        PasswordEncoder realEncoder = new BCryptPasswordEncoder();
        UserManagementController controllerWithRealEncoder = new UserManagementController(
                appUserRepository, departmentRepository, roleRepository, realEncoder);

        when(appUserRepository.existsByEmployeeCodeIgnoreCase("STAFF010")).thenReturn(false);
        when(appUserRepository.existsByEmailIgnoreCase("staff10@example.com")).thenReturn(false);
        when(departmentRepository.findById(1L)).thenReturn(Optional.of(activeDepartment));
        when(roleRepository.findAllById(Set.of(2L))).thenReturn(List.of(officeStaffRole));

        var authentication = createAuth("inv-admin@example.com", "INVENTORY_ADMIN");

        String result = controllerWithRealEncoder.createUser(
                "STAFF010", "Ada", "Lovelace", "staff10@example.com",
                "StrongPass123", null, 1L, "Officer", List.of(2L),
                authentication, new RedirectAttributesModelMap());

        assertEquals("redirect:/users", result);

        ArgumentCaptor<AppUser> saved = ArgumentCaptor.forClass(AppUser.class);
        verify(appUserRepository).saveAndFlush(saved.capture());
        String stored = saved.getValue().getPasswordHash();

        assertNotEquals("StrongPass123", stored);
        assertFalse(stored.contains("StrongPass123"),
                "the submitted password must not survive anywhere inside the stored value");
        assertTrue(stored.startsWith("$2"), "expected a bcrypt hash, got: " + stored);
        assertTrue(realEncoder.matches("StrongPass123", stored));
        assertFalse(realEncoder.matches("wrongPassword", stored));
    }

    /**
     * A password that is too short is refused before anything is stored, so no
     * hash of a guessable secret can reach the database.
     */
    @Test
    void aShortPasswordIsRefusedBeforeAnythingIsStored() {
        when(appUserRepository.existsByEmployeeCodeIgnoreCase("STAFF011")).thenReturn(false);
        when(appUserRepository.existsByEmailIgnoreCase("staff11@example.com")).thenReturn(false);
        when(departmentRepository.findById(1L)).thenReturn(Optional.of(activeDepartment));
        when(roleRepository.findAllById(Set.of(2L))).thenReturn(List.of(officeStaffRole));

        var authentication = createAuth("inv-admin@example.com", "INVENTORY_ADMIN");
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        String result = controller.createUser(
                "STAFF011", "Grace", "Hopper", "staff11@example.com",
                "short", null, 1L, "Officer", List.of(2L),
                authentication, redirectAttributes);

        assertEquals("redirect:/users/new", result);
        verify(appUserRepository, never()).saveAndFlush(any(AppUser.class));
        verify(passwordEncoder, never()).encode(anyString());
    }

    // -------------------------------------------------------------------------
    // How far an administrator's reach extends
    // -------------------------------------------------------------------------

    /**
     * The controller also refuses to deactivate the last active System Admin, but
     * that guard is only ever reachable behind this one: no administrator may
     * touch another System Admin account at all, so the top account cannot be
     * switched off through the application by anybody - including a peer. Pinned
     * here because it is the rule the account list actually enforces.
     */
    @Test
    void aSystemAdminAccountCannotBeDeactivatedByAnotherSystemAdmin() {
        Role systemAdminRole = new Role();
        systemAdminRole.setRoleId(4L);
        systemAdminRole.setName("SYSTEM_ADMIN");

        AppUser otherSystemAdmin = new AppUser();
        otherSystemAdmin.setUserId(30L);
        otherSystemAdmin.setEmail("other.admin@example.com");
        otherSystemAdmin.setActive(true);
        otherSystemAdmin.setRoles(Set.of(systemAdminRole));

        when(appUserRepository.findById(30L)).thenReturn(Optional.of(otherSystemAdmin));

        var authentication = createAuth("admin@example.com", "SYSTEM_ADMIN");
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        String result = controller.updateUserStatus(30L, false, authentication, redirectAttributes);

        assertEquals("redirect:/users", result);
        assertEquals("You are not permitted to change this account's status.",
                redirectAttributes.getFlashAttributes().get("errorMessage"));
        assertTrue(otherSystemAdmin.isActive(), "the refused account must be left as it was");
        verify(appUserRepository, never()).save(any(AppUser.class));
    }

    @Test
    void aSystemAdminAccountCannotBeDeactivatedByAnInventoryAdmin() {
        Role systemAdminRole = new Role();
        systemAdminRole.setRoleId(4L);
        systemAdminRole.setName("SYSTEM_ADMIN");

        AppUser systemAdmin = new AppUser();
        systemAdmin.setUserId(31L);
        systemAdmin.setEmail("sysadmin2@example.com");
        systemAdmin.setActive(true);
        systemAdmin.setRoles(Set.of(systemAdminRole));

        when(appUserRepository.findById(31L)).thenReturn(Optional.of(systemAdmin));

        var authentication = createAuth("inv-admin@example.com", "INVENTORY_ADMIN");
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        String result = controller.updateUserStatus(31L, false, authentication, redirectAttributes);

        assertEquals("redirect:/users", result);
        assertEquals("You are not permitted to change this account's status.",
                redirectAttributes.getFlashAttributes().get("errorMessage"));
        verify(appUserRepository, never()).save(any(AppUser.class));
    }

    /**
     * Accounts holding more than one role fall outside every administrator's
     * scope, so a mixed account cannot be reached by somebody who may manage only
     * part of what it is.
     */
    @Test
    void anAccountHoldingTwoRolesIsOutsideEveryAdministratorsReach() {
        AppUser mixed = new AppUser();
        mixed.setUserId(32L);
        mixed.setEmail("mixed@example.com");
        mixed.setActive(true);
        mixed.setRoles(Set.of(officeStaffRole, inventoryManagerRole));

        when(appUserRepository.findById(32L)).thenReturn(Optional.of(mixed));

        var authentication = createAuth("inv-admin@example.com", "INVENTORY_ADMIN");
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        String result = controller.updateUserStatus(32L, false, authentication, redirectAttributes);

        assertEquals("redirect:/users", result);
        assertEquals("You are not permitted to change this account's status.",
                redirectAttributes.getFlashAttributes().get("errorMessage"));
        verify(appUserRepository, never()).save(any(AppUser.class));
    }

    @Test
    void anAccountThatNoLongerExistsIsRefusedWithoutSavingAnything() {
        when(appUserRepository.findById(99L)).thenReturn(Optional.empty());

        var authentication = createAuth("inv-admin@example.com", "INVENTORY_ADMIN");
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        String result = controller.updateUserStatus(99L, false, authentication, redirectAttributes);

        assertEquals("redirect:/users", result);
        assertEquals("User account was not found.",
                redirectAttributes.getFlashAttributes().get("errorMessage"));
        verify(appUserRepository, never()).save(any(AppUser.class));
    }

    /**
     * Editing an account is the route through which somebody with authority over
     * another person's record could quietly take ownership of that person's
     * sign-in. The form carries no password field and the handler keeps the hash
     * it loaded, which is worth stating in a test: a future edit that accepted a
     * blank password, or re-encoded something, would otherwise pass every test
     * that exists today and leave the account unable to sign in - or openable by
     * whoever knows what was typed into the new field.
     */
    @Test
    void anEditedAccountKeepsThePasswordItAlreadyHas() {
        AppUser staffUser = new AppUser();
        staffUser.setUserId(20L);
        staffUser.setEmployeeCode("STAFF001");
        staffUser.setEmail("staff@example.com");
        staffUser.setFirstName("Old");
        staffUser.setLastName("Name");
        staffUser.setPasswordHash("$2a$10$alreadyStoredHashForThisAccount");
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
        assertEquals("$2a$10$alreadyStoredHashForThisAccount",
                staffUser.getPasswordHash(), "an edit should not touch the stored password");
        assertTrue(staffUser.isActive(), "an edit should not switch the account off");
        verify(passwordEncoder, never()).encode(any());
    }
}
