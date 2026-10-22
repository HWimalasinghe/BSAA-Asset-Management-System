
package com.bssa.assetmanagement.controller;

import com.bssa.assetmanagement.repository.PropertyAssignmentRepository;
import com.bssa.assetmanagement.repository.PropertyReturnRepository;
import com.bssa.assetmanagement.service.PropertyReturnService;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/returns")
public class PropertyReturnController {

    private final PropertyAssignmentRepository assignmentRepository;
    private final PropertyReturnRepository returnRepository;
    private final PropertyReturnService returnService;

    public PropertyReturnController(
            PropertyAssignmentRepository assignmentRepository,
            PropertyReturnRepository returnRepository,
            PropertyReturnService returnService) {
        this.assignmentRepository = assignmentRepository;
        this.returnRepository = returnRepository;
        this.returnService = returnService;
    }

    @GetMapping
    public String listReturns(Model model, Authentication authentication) {
        model.addAttribute(
                "returns",
                returnRepository.findAllByOrderByReturnIdDesc()
        );
        model.addAttribute("hasReturnPrivilege", hasReturnRole(authentication));
        return "returns/list";
    }

    @GetMapping("/new")
    public String showReturnForm(Model model, Authentication authentication,
                                 RedirectAttributes redirectAttributes) {

        // Server-side check: only authorized roles may process returns.
        if (!hasReturnRole(authentication)) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "You are not authorized to process returns."
            );
            return "redirect:/returns";
        }

        model.addAttribute(
                "assignments",
                assignmentRepository.findAllByOrderByAssignmentIdDesc()
                        .stream()
                        .filter(assignment ->
                                "ASSIGNED".equals(
                                        assignment.getProperty().getStatus()))
                        .filter(assignment ->
                                !returnRepository.existsByAssignment_AssignmentId(
                                        assignment.getAssignmentId()))
                        .toList()
        );

        return "returns/form";
    }

    @PostMapping
    public String createReturn(
            @RequestParam Long assignmentId,
            @RequestParam String condition,
            @RequestParam(required = false) String notes,
            Authentication authentication,
            RedirectAttributes redirectAttributes) {

        // Enforce authorization at the handler level — SecurityConfig is the primary
        // guard, but we also check here defensively so any misconfiguration is caught.
        if (!hasReturnRole(authentication)) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "You are not authorized to process returns."
            );
            return "redirect:/returns";
        }

        try {
            returnService.returnProperty(
                    assignmentId,
                    condition,
                    notes,
                    authentication.getName()
            );

            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "Property returned successfully."
            );

            return "redirect:/returns";

        } catch (IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    ex.getMessage()
            );

            return "redirect:/returns/new";
        }
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    /**
     * Returns true if the caller holds a role authorized to process returns.
     * Mirrors the SecurityConfig rule for /returns/**.
     */
    private boolean hasReturnRole(Authentication authentication) {
        if (authentication == null) {
            return false;
        }
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(a -> a.equals("ROLE_SYSTEM_ADMIN")
                        || a.equals("ROLE_INVENTORY_ADMIN")
                        || a.equals("ROLE_INVENTORY_MANAGER"));
    }
}
