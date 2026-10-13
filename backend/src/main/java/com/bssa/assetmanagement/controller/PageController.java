package com.bssa.assetmanagement.controller;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class PageController {

    @GetMapping("/login")
    public String loginPage() {
        return "login";
    }

    @GetMapping("/")
    public String homePage(Authentication authentication, Model model) {

        model.addAttribute("userEmail", authentication.getName());

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
}
