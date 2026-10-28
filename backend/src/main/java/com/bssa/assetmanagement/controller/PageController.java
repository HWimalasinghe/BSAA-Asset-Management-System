package com.bssa.assetmanagement.controller;

import com.bssa.assetmanagement.entity.AppUser;
import com.bssa.assetmanagement.repository.AppUserRepository;

import com.bssa.assetmanagement.service.DashboardReportService;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Controller
public class PageController {

    private final AppUserRepository appUserRepository;
    private final DashboardReportService dashboardReportService;

    public PageController(
            AppUserRepository appUserRepository,
            DashboardReportService dashboardReportService) {
        this.appUserRepository = appUserRepository;
        this.dashboardReportService = dashboardReportService;
    }

    @GetMapping("/login")
    public String loginPage() {
        return "login";
    }

    @GetMapping("/")
    public String homePage(Authentication authentication, Model model) {
        addUserDetails(authentication, model);
        return "dashboard";
    }
    
    @GetMapping("/admin")
    public String systemAdminPage(Authentication authentication, Model model) {
        addUserDetails(authentication, model);
        return "dashboard";
    }

    @GetMapping("/inventory-admin")
    public String inventoryAdminPage(Authentication authentication, Model model) {
        addUserDetails(authentication, model);
        return "dashboard";
    }

    @GetMapping("/inventory-manager")
    public String inventoryManagerPage(Authentication authentication, Model model) {
        addUserDetails(authentication, model);
        return "dashboard";
    }

    @GetMapping("/office-staff")
    public String officeStaffPage(Authentication authentication, Model model) {
        addUserDetails(authentication, model);
        return "dashboard";
    }


    private void addUserDetails(Authentication authentication, Model model) {

        AppUser appUser = appUserRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new IllegalStateException(
                        "Authenticated user was not found in the database"));

        model.addAttribute(
                "userFullName",
                appUser.getFirstName() + " " + appUser.getLastName()
        );

        model.addAttribute("userEmail", appUser.getEmail());

        Set<String> roleNames = authentication.getAuthorities().stream()
                .map(authority -> authority.getAuthority())
                .filter(authority -> authority.startsWith("ROLE_"))
                .map(authority -> authority.substring(5))
                .collect(Collectors.toSet());

        // A user may hold several roles. Show the first one as a label, but
        // decide dashboard visibility from the whole set.
        String primaryRole = roleNames.stream()
                .filter(DashboardReportService.OVERVIEW_ROLES::contains)
                .findFirst()
                .orElse(roleNames.stream().findFirst().orElse("USER"));

        model.addAttribute("userRole", primaryRole);

        Map<String, Object> metrics = dashboardReportService.getDashboardMetrics(
                appUser.getEmail(), roleNames);
        model.addAllAttributes(metrics);
    }
}
