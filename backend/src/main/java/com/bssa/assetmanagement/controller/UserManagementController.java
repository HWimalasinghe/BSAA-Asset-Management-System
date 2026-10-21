package com.bssa.assetmanagement.controller;

import com.bssa.assetmanagement.entity.AppUser;
import com.bssa.assetmanagement.entity.Department;
import com.bssa.assetmanagement.entity.Role;
import com.bssa.assetmanagement.repository.AppUserRepository;
import com.bssa.assetmanagement.repository.DepartmentRepository;
import com.bssa.assetmanagement.repository.RoleRepository;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/users")
public class UserManagementController {

    private final AppUserRepository appUserRepository;
    private final DepartmentRepository departmentRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;

    public UserManagementController(
            AppUserRepository appUserRepository,
            DepartmentRepository departmentRepository,
            RoleRepository roleRepository,
            PasswordEncoder passwordEncoder) {

        this.appUserRepository = appUserRepository;
        this.departmentRepository = departmentRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
    }

    // List user accounts and identify accounts the current administrator can manage.
    @GetMapping
    public String listUsers(
            Model model,
            Authentication authentication) {

        List<AppUser> users = appUserRepository.findAll();

        model.addAttribute("users", users);
        model.addAttribute(
                "isSystemAdmin",
                hasRole(authentication, "SYSTEM_ADMIN")
        );
        model.addAttribute(
                "manageableUserIds",
                users.stream()
                        .filter(user -> canManageTarget(authentication, user))
                        .map(AppUser::getUserId)
                        .collect(Collectors.toSet())
        );

        return "users/list";
    }

    // Display the create-user form.
    @GetMapping("/new")
    public String showCreateForm(
            Model model,
            Authentication authentication) {

        loadFormOptions(model, authentication);
        return "users/form";
    }

    // Display the edit-user form only when the target account is permitted.
    @GetMapping("/edit/{userId}")
    public String showEditForm(
            @PathVariable Long userId,
            Model model,
            Authentication authentication,
            RedirectAttributes redirectAttributes) {

        AppUser user = appUserRepository.findById(userId)
                .orElse(null);

        if (user == null) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "User account was not found."
            );
            return "redirect:/users";
        }

        if (!canManageTarget(authentication, user)) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "You are not permitted to manage this account."
            );
            return "redirect:/users";
        }

        loadFormOptions(model, authentication);
        model.addAttribute("editUser", user);

        return "users/form";
    }

    // Create a new user account.
    @PostMapping
    @Transactional
    public String createUser(
            @RequestParam String employeeCode,
            @RequestParam String firstName,
            @RequestParam String lastName,
            @RequestParam String email,
            @RequestParam String password,
            @RequestParam(required = false) String phone,
            @RequestParam Long departmentId,
            @RequestParam(required = false) String position,
            @RequestParam(required = false) List<Long> roleIds,
            Authentication authentication,
            RedirectAttributes redirectAttributes) {

        String code = employeeCode.trim();
        String first = firstName.trim();
        String last = lastName.trim();
        String normalizedEmail = email.trim().toLowerCase();

        if (code.isEmpty() || first.isEmpty()
                || last.isEmpty() || normalizedEmail.isEmpty()) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "Employee code, first name, last name, and email are required."
            );
            return "redirect:/users/new";
        }

        if (code.length() > 50 || first.length() > 100
                || last.length() > 100 || normalizedEmail.length() > 150
                || (phone != null && phone.trim().length() > 30)
                || (position != null && position.trim().length() > 100)) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "One or more fields exceed the maximum allowed length."
            );
            return "redirect:/users/new";
        }

        if (password == null || password.length() < 8
                || password.length() > 100) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "Password must contain between 8 and 100 characters."
            );
            return "redirect:/users/new";
        }

        if (appUserRepository.existsByEmployeeCodeIgnoreCase(code)) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "An account with this employee code already exists."
            );
            return "redirect:/users/new";
        }

        if (appUserRepository.existsByEmailIgnoreCase(normalizedEmail)) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "An account with this email address already exists."
            );
            return "redirect:/users/new";
        }

        Department department = departmentRepository.findById(departmentId)
                .filter(Department::isActive)
                .orElse(null);

        if (department == null) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "Please select an active department."
            );
            return "redirect:/users/new";
        }

        if (roleIds == null || roleIds.isEmpty()) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "Please select a role."
            );
            return "redirect:/users/new";
        }

        Set<Long> uniqueRoleIds = new HashSet<>(roleIds);
        List<Role> selectedRoles = roleRepository.findAllById(uniqueRoleIds);

        if (selectedRoles.size() != uniqueRoleIds.size()) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "One or more selected roles are invalid."
            );
            return "redirect:/users/new";
        }

        if (!canAssignRoles(authentication, selectedRoles)) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "You are not permitted to assign the selected role."
            );
            return "redirect:/users/new";
        }

        // Only one Inventory Admin account is permitted, including inactive accounts.
        if ("INVENTORY_ADMIN".equals(selectedRoles.get(0).getName())
                && appUserRepository.findAll().stream()
                        .anyMatch(existingUser ->
                                existingUser.getRoles().stream()
                                        .anyMatch(role ->
                                                "INVENTORY_ADMIN".equals(
                                                        role.getName())))) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "An Inventory Admin account already exists."
            );
            return "redirect:/users/new";
        }

        AppUser user = new AppUser();
        user.setEmployeeCode(code);
        user.setFirstName(first);
        user.setLastName(last);
        user.setEmail(normalizedEmail);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setPhone(phone == null || phone.isBlank()
                ? null : phone.trim());
        user.setDepartmentId(department.getDepartmentId());
        user.setPosition(position == null || position.isBlank()
                ? null : position.trim());
        user.setActive(true);
        user.setRoles(new HashSet<>(selectedRoles));

        appUserRepository.saveAndFlush(user);

        redirectAttributes.addFlashAttribute(
                "successMessage",
                "User account created successfully."
        );

        return "redirect:/users";
    }

    // Update an existing account only when the administrator can manage it.
    @PostMapping("/{userId}/edit")
    @Transactional
    public String updateUser(
            @PathVariable Long userId,
            @RequestParam String employeeCode,
            @RequestParam String firstName,
            @RequestParam String lastName,
            @RequestParam String email,
            @RequestParam(required = false) String phone,
            @RequestParam Long departmentId,
            @RequestParam(required = false) String position,
            @RequestParam(required = false) List<Long> roleIds,
            Authentication authentication,
            RedirectAttributes redirectAttributes) {

        AppUser user = appUserRepository.findById(userId)
                .orElse(null);

        if (user == null) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "User account was not found."
            );
            return "redirect:/users";
        }

        if (!canManageTarget(authentication, user)) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "You are not permitted to manage this account."
            );
            return "redirect:/users";
        }

        String code = employeeCode.trim();
        String first = firstName.trim();
        String last = lastName.trim();
        String normalizedEmail = email.trim().toLowerCase();

        if (code.isEmpty() || first.isEmpty()
                || last.isEmpty() || normalizedEmail.isEmpty()) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "Employee code, first name, last name, and email are required."
            );
            return "redirect:/users/edit/" + userId;
        }

        if (code.length() > 50 || first.length() > 100
                || last.length() > 100 || normalizedEmail.length() > 150
                || (phone != null && phone.trim().length() > 30)
                || (position != null && position.trim().length() > 100)) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "One or more fields exceed the maximum allowed length."
            );
            return "redirect:/users/edit/" + userId;
        }

        boolean duplicateEmployeeCode =
                appUserRepository.existsByEmployeeCodeIgnoreCase(code)
                && !user.getEmployeeCode().equalsIgnoreCase(code);

        if (duplicateEmployeeCode) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "Another account already uses this employee code."
            );
            return "redirect:/users/edit/" + userId;
        }

        boolean duplicateEmail =
                appUserRepository.existsByEmailIgnoreCase(normalizedEmail)
                && !user.getEmail().equalsIgnoreCase(normalizedEmail);

        if (duplicateEmail) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "Another account already uses this email address."
            );
            return "redirect:/users/edit/" + userId;
        }

        Department department = departmentRepository.findById(departmentId)
                .filter(Department::isActive)
                .orElse(null);

        if (department == null) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "Please select an active department."
            );
            return "redirect:/users/edit/" + userId;
        }

        if (roleIds == null || roleIds.isEmpty()) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "Please select a role."
            );
            return "redirect:/users/edit/" + userId;
        }

        Set<Long> uniqueRoleIds = new HashSet<>(roleIds);
        List<Role> selectedRoles = roleRepository.findAllById(uniqueRoleIds);

        if (selectedRoles.size() != uniqueRoleIds.size()) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "One or more selected roles are invalid."
            );
            return "redirect:/users/edit/" + userId;
        }

        if (!canAssignRoles(authentication, selectedRoles)) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "You are not permitted to assign the selected role."
            );
            return "redirect:/users/edit/" + userId;
        }

        user.setEmployeeCode(code);
        user.setFirstName(first);
        user.setLastName(last);
        user.setEmail(normalizedEmail);
        user.setPhone(phone == null || phone.isBlank()
                ? null : phone.trim());
        user.setDepartmentId(department.getDepartmentId());
        user.setPosition(position == null || position.isBlank()
                ? null : position.trim());
        user.setRoles(new HashSet<>(selectedRoles));

        // Preserve the existing password and active status.
        appUserRepository.saveAndFlush(user);

        redirectAttributes.addFlashAttribute(
                "successMessage",
                "User account updated successfully."
        );

        return "redirect:/users";
    }

    // Activate or deactivate an account within the administrator's scope.
    @PostMapping("/status")
    @Transactional
    public String updateUserStatus(
            @RequestParam Long userId,
            @RequestParam boolean active,
            Authentication authentication,
            RedirectAttributes redirectAttributes) {

        AppUser user = appUserRepository.findById(userId)
                .orElse(null);

        if (user == null) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "User account was not found."
            );
            return "redirect:/users";
        }

        if (!canManageTarget(authentication, user)) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "You are not permitted to change this account's status."
            );
            return "redirect:/users";
        }

        // Prevent deactivating the currently signed-in account.
        if (!active
                && authentication != null
                && user.getEmail().equalsIgnoreCase(
                        authentication.getName())) {

            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "You cannot deactivate your own account."
            );
            return "redirect:/users";
        }

        // Prevent deactivating the last active System Admin.
        if (!active && user.isActive()
                && user.getRoles().stream()
                        .anyMatch(role ->
                                "SYSTEM_ADMIN".equals(role.getName()))) {

            boolean anotherActiveSystemAdminExists =
                    appUserRepository.findAll().stream()
                            .filter(AppUser::isActive)
                            .filter(otherUser ->
                                    !otherUser.getUserId()
                                            .equals(user.getUserId()))
                            .anyMatch(otherUser ->
                                    otherUser.getRoles().stream()
                                            .anyMatch(role ->
                                                    "SYSTEM_ADMIN".equals(
                                                            role.getName())));

            if (!anotherActiveSystemAdminExists) {
                redirectAttributes.addFlashAttribute(
                        "errorMessage",
                        "You cannot deactivate the last active System Admin."
                );
                return "redirect:/users";
            }
        }

        user.setActive(active);
        appUserRepository.save(user);

        redirectAttributes.addFlashAttribute(
                "successMessage",
                active
                        ? "User account activated successfully."
                        : "User account deactivated successfully."
        );

        return "redirect:/users";
    }

    // Load active departments and only the roles this administrator may assign.
    private void loadFormOptions(
            Model model,
            Authentication authentication) {

        model.addAttribute(
                "departments",
                departmentRepository.findAllByOrderByNameAsc()
                        .stream()
                        .filter(Department::isActive)
                        .toList()
        );

        List<Role> availableRoles = roleRepository.findAll()
                .stream()
                .filter(role -> {
                    if (hasRole(authentication, "SYSTEM_ADMIN")) {
                        return "INVENTORY_ADMIN".equals(role.getName());
                    }

                    if (hasRole(authentication, "INVENTORY_ADMIN")) {
                        return "OFFICE_STAFF".equals(role.getName())
                                || "INVENTORY_MANAGER".equals(role.getName());
                    }

                    return false;
                })
                .toList();

        model.addAttribute("roles", availableRoles);
    }

    // Check whether the signed-in administrator has a specific role.
    private boolean hasRole(
            Authentication authentication,
            String roleName) {

        return authentication != null
                && authentication.getAuthorities().stream()
                        .map(GrantedAuthority::getAuthority)
                        .anyMatch(authority ->
                                authority.equals("ROLE_" + roleName));
    }

    // Check whether this administrator may manage the target account.
    private boolean canManageTarget(
            Authentication authentication,
            AppUser target) {

        if (authentication == null || target == null
                || target.getRoles() == null
                || target.getRoles().size() != 1) {
            return false;
        }

        String targetRole = target.getRoles().iterator()
                .next()
                .getName();

        if (hasRole(authentication, "SYSTEM_ADMIN")) {
            return "INVENTORY_ADMIN".equals(targetRole);
        }

        if (hasRole(authentication, "INVENTORY_ADMIN")) {
            return "OFFICE_STAFF".equals(targetRole)
                    || "INVENTORY_MANAGER".equals(targetRole);
        }

        return false;
    }

    // Validate submitted roles independently of the form.
    private boolean canAssignRoles(
            Authentication authentication,
            List<Role> selectedRoles) {

        if (authentication == null || selectedRoles == null
                || selectedRoles.size() != 1) {
            return false;
        }

        String selectedRole = selectedRoles.get(0).getName();

        if (hasRole(authentication, "SYSTEM_ADMIN")) {
            return "INVENTORY_ADMIN".equals(selectedRole);
        }

        if (hasRole(authentication, "INVENTORY_ADMIN")) {
            return "OFFICE_STAFF".equals(selectedRole)
                    || "INVENTORY_MANAGER".equals(selectedRole);
        }

        return false;
    }
}
