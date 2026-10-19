
package com.bssa.assetmanagement.controller;

import com.bssa.assetmanagement.repository.PropertyAssignmentRepository;
import com.bssa.assetmanagement.repository.PropertyReturnRepository;
import com.bssa.assetmanagement.service.PropertyReturnService;
import org.springframework.security.core.Authentication;
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
    public String listReturns(Model model) {
        model.addAttribute(
                "returns",
                returnRepository.findAllByOrderByReturnIdDesc()
        );
        return "returns/list";
    }

    @GetMapping("/new")
    public String showReturnForm(Model model) {
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
}
