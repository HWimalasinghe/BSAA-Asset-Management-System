package com.bssa.assetmanagement.security;

import com.bssa.assetmanagement.entity.AppUser;
import com.bssa.assetmanagement.entity.Role;
import com.bssa.assetmanagement.repository.AppUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DatabaseUserDetailsServiceTest {

    private final AppUserRepository appUserRepository = mock(AppUserRepository.class);
    private final DatabaseUserDetailsService userDetailsService =
            new DatabaseUserDetailsService(appUserRepository);

    @Test
    void shouldLoadActiveUserWithRoleAuthorities() {
        Role role = new Role();
        role.setName("INVENTORY_MANAGER");

        AppUser user = new AppUser();
        user.setEmail("manager@example.com");
        user.setPasswordHash("hashedPassword");
        user.setActive(true);
        user.setRoles(Set.of(role));

        when(appUserRepository.findByEmail("manager@example.com")).thenReturn(Optional.of(user));

        UserDetails details = userDetailsService.loadUserByUsername("manager@example.com");

        assertNotNull(details);
        assertEquals("manager@example.com", details.getUsername());
        assertEquals("hashedPassword", details.getPassword());
        assertTrue(details.isEnabled());
        assertTrue(details.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_INVENTORY_MANAGER")));
    }

    @Test
    void shouldLoadInactiveUserAsDisabled() {
        Role role = new Role();
        role.setName("OFFICE_STAFF");

        AppUser user = new AppUser();
        user.setEmail("disabled@example.com");
        user.setPasswordHash("hashedPassword");
        user.setActive(false);
        user.setRoles(Set.of(role));

        when(appUserRepository.findByEmail("disabled@example.com")).thenReturn(Optional.of(user));

        UserDetails details = userDetailsService.loadUserByUsername("disabled@example.com");

        assertNotNull(details);
        assertFalse(details.isEnabled());
    }

    @Test
    void shouldThrowWhenUserNotFound() {
        when(appUserRepository.findByEmail("unknown@example.com")).thenReturn(Optional.empty());

        assertThrows(UsernameNotFoundException.class, () ->
                userDetailsService.loadUserByUsername("unknown@example.com")
        );
    }
}
