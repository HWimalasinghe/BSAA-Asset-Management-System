
package com.bssa.assetmanagement.controller;

import com.bssa.assetmanagement.entity.PropertyAssignment;
import com.bssa.assetmanagement.repository.AppUserRepository;
import com.bssa.assetmanagement.repository.PhysicalAreaRepository;
import com.bssa.assetmanagement.repository.PropertyAssignmentRepository;
import com.bssa.assetmanagement.repository.PropertyRepository;
import com.bssa.assetmanagement.service.PropertyAssignmentService;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/assignments")
public class PropertyAssignmentController {

    private final PropertyAssignmentRepository assignmentRepository;
    private final PropertyRepository propertyRepository;
    private final AppUserRepository appUserRepository;
    private final PhysicalAreaRepository physicalAreaRepository;
    private final PropertyAssignmentService assignmentService;

    public PropertyAssignmentController(
            PropertyAssignmentRepository assignmentRepository,
            PropertyRepository propertyRepository,
            AppUserRepository appUserRepository,
            PhysicalAreaRepository physicalAreaRepository,
            PropertyAssignmentService assignmentService) {
        this.assignmentRepository = assignmentRepository;
        this.propertyRepository = propertyRepository;
        this.appUserRepository = appUserRepository;
        this.physicalAreaRepository = physicalAreaRepository;
        this.assignmentService = assignmentService;
    }

    @GetMapping
    public String listAssignments(Model model) {
        model.addAttribute(
                "assignments",
                assignmentRepository.findAllByOrderByAssignmentIdDesc()
        );

        return "assignments/list";
    }

    @GetMapping("/new")
    public String showAssignmentForm(Model model) {
        model.addAttribute(
                "properties",
                propertyRepository.findByStatusOrderByPropertyCodeAsc("AVAILABLE")
        );
        model.addAttribute(
                "employees",
                appUserRepository.findByActiveTrueOrderByEmployeeCodeAsc()
        );
        model.addAttribute(
                "areas",
                physicalAreaRepository.findAllByActiveTrueOrderByNameAsc()
        );

        return "assignments/form";
    }

    @PostMapping
    public String createAssignment(
            @RequestParam Long propertyId,
            @RequestParam Long employeeId,
            @RequestParam Long areaId,
            @RequestParam String conditionAtIssue,
            @RequestParam(required = false) String notes,
            Authentication authentication,
            RedirectAttributes redirectAttributes) {

        try {
            assignmentService.assignProperty(
                    propertyId,
                    employeeId,
                    areaId,
                    conditionAtIssue,
                    notes,
                    authentication.getName()
            );

            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "Property assigned successfully."
            );

            return "redirect:/assignments";

        } catch (IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    ex.getMessage()
            );

            return "redirect:/assignments/new";
        }
    }
}
