package com.bssa.assetmanagement.security;

import com.bssa.assetmanagement.entity.AppUser;
import com.bssa.assetmanagement.entity.Role;
import com.bssa.assetmanagement.repository.AppUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.Optional;
import java.util.Set;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
class SecurityAuthorizationTest {

    @Autowired
    private WebApplicationContext context;

    @MockitoBean
    private AppUserRepository appUserRepository;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();

        Role role = new Role();
        role.setName("SYSTEM_ADMIN");

        AppUser testUser = new AppUser();
        testUser.setUserId(1L);
        testUser.setFirstName("Test");
        testUser.setLastName("User");
        testUser.setEmail("test@example.com");
        testUser.setActive(true);
        testUser.setRoles(Set.of(role));

        when(appUserRepository.findByEmail(anyString())).thenReturn(Optional.of(testUser));
    }

    @Test
    void unauthenticatedUserShouldBeRedirectedToLoginForProtectedUrls() throws Exception {
        mockMvc.perform(get("/users"))
                .andExpect(status().is3xxRedirection());

        mockMvc.perform(get("/admin"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void publicEndpointsShouldBeAccessibleWithoutAuth() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "admin@example.com", roles = {"SYSTEM_ADMIN"})
    void systemAdminCanAccessAdminAndUsers() throws Exception {
        mockMvc.perform(get("/admin"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/users"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "invadmin@example.com", roles = {"INVENTORY_ADMIN"})
    void inventoryAdminCanAccessUsersButNotAdmin() throws Exception {
        mockMvc.perform(get("/users"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/admin"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "manager@example.com", roles = {"INVENTORY_MANAGER"})
    void inventoryManagerCannotAccessUsersOrAdmin() throws Exception {
        mockMvc.perform(get("/users"))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/admin"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "staff@example.com", roles = {"OFFICE_STAFF"})
    void officeStaffCannotAccessUsersOrAdminOrManagerEndpoints() throws Exception {
        mockMvc.perform(get("/users"))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/admin"))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/inventory-manager"))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/assignments"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "manager@example.com", roles = {"INVENTORY_MANAGER"})
    void inventoryManagerCanAccessOfficeStaffAndManagerEndpoints() throws Exception {
        mockMvc.perform(get("/inventory-manager"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/office-staff"))
                .andExpect(status().isOk());
    }
}
