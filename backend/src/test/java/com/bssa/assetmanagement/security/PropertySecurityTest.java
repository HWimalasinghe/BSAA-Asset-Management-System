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
class PropertySecurityTest {

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
        role.setName("INVENTORY_ADMIN");

        AppUser testUser = new AppUser();
        testUser.setUserId(1L);
        testUser.setEmail("admin@example.com");
        testUser.setActive(true);
        testUser.setRoles(Set.of(role));

        when(appUserRepository.findByEmail(anyString())).thenReturn(Optional.of(testUser));
    }

    @Test
    void unauthenticatedUserCannotAccessProperties() throws Exception {
        mockMvc.perform(get("/properties"))
                .andExpect(status().is3xxRedirection());

        mockMvc.perform(get("/properties/new"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    @WithMockUser(username = "sysadmin@example.com", roles = {"SYSTEM_ADMIN"})
    void systemAdminCanAccessProperties() throws Exception {
        mockMvc.perform(get("/properties"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/properties/new"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "invadmin@example.com", roles = {"INVENTORY_ADMIN"})
    void inventoryAdminCanAccessProperties() throws Exception {
        mockMvc.perform(get("/properties"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/properties/new"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "manager@example.com", roles = {"INVENTORY_MANAGER"})
    void inventoryManagerCannotAccessProperties() throws Exception {
        mockMvc.perform(get("/properties"))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/properties/new"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "staff@example.com", roles = {"OFFICE_STAFF"})
    void officeStaffCannotAccessProperties() throws Exception {
        mockMvc.perform(get("/properties"))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/properties/new"))
                .andExpect(status().isForbidden());
    }
}
