package com.bssa.assetmanagement.controller;

import com.bssa.assetmanagement.entity.AppUser;
import com.bssa.assetmanagement.repository.AppUserRepository;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class PageController {

    private final AppUserRepository appUserRepository;

    public PageController(AppUserRepository appUserRepository) {
        this.appUserRepository = appUserRepository;
    }

    @GetMapping("/login")
    public String loginPage() {
        return "login";
    }

    @GetMapping("/")
    public String homePage(Authentication authentication, Model model) {

        AppUser appUser = appUserRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new IllegalStateException(
                        "Authenticated user was not found in the database"));

        model.addAttribute(
                "userFullName",
                appUser.getFirstName() + " " + appUser.getLastName()
        );

        model.addAttribute("userEmail", appUser.getEmail());

        String role = authentication.getAuthorities().stream()
                .map(authority -> authority.getAuthority())
                .filter(authority -> authority.startsWith("ROLE_"))
                .map(authority -> authority.substring(5))
                .findFirst()
                .orElse("USER");

        model.addAttribute("userRole", role);

        return "dashboard";
    }

    @GetMapping("/admin")
    public String systemAdminPage() {
        return "dashboard";
    }

    @GetMapping("/inventory-admin")
    public String inventoryAdminPage() {
        return "dashboard";
    }

    @GetMapping("/inventory-manager")
    public String inventoryManagerPage() {
        return "dashboard";
    }

    @GetMapping("/office-staff")
    public String officeStaffPage() {
        return "dashboard";
    }
}
