package com.bssa.assetmanagement.security;

import com.bssa.assetmanagement.entity.AppUser;
import com.bssa.assetmanagement.entity.Role;
import com.bssa.assetmanagement.repository.AppUserRepository;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.stream.Collectors;

@Service
public class DatabaseUserDetailsService implements UserDetailsService {

    private final AppUserRepository appUserRepository;

    public DatabaseUserDetailsService(AppUserRepository appUserRepository) {
        this.appUserRepository = appUserRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String email)
            throws UsernameNotFoundException {

        AppUser appUser = appUserRepository.findByEmail(email)
                .orElseThrow(() ->
                        new UsernameNotFoundException("User not found"));

        var authorities = appUser.getRoles().stream()
                .map(Role::getName)
                .map(roleName ->
                        new SimpleGrantedAuthority("ROLE_" + roleName))
                .collect(Collectors.toSet());

        return User.withUsername(appUser.getEmail())
                .password(appUser.getPasswordHash())
                .authorities(authorities)
                .disabled(!appUser.isActive())
                .build();
    }
}
