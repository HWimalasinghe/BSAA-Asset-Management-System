
package com.bssa.assetmanagement.controller;

import com.bssa.assetmanagement.entity.AppUser;
import com.bssa.assetmanagement.repository.AppUserRepository;
import com.bssa.assetmanagement.repository.PhysicalAreaRepository;
import com.bssa.assetmanagement.repository.PropertyRepository;
import com.bssa.assetmanagement.service.PagingSupport;
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

    private final PropertyRepository propertyRepository;
    private final AppUserRepository appUserRepository;
    private final PhysicalAreaRepository physicalAreaRepository;
    private final PropertyAssignmentService assignmentService;

    public PropertyAssignmentController(
            PropertyRepository propertyRepository,
            AppUserRepository appUserRepository,
            PhysicalAreaRepository physicalAreaRepository,
            PropertyAssignmentService assignmentService) {
        this.propertyRepository = propertyRepository;
        this.appUserRepository = appUserRepository;
        this.physicalAreaRepository = physicalAreaRepository;
        this.assignmentService = assignmentService;
    }

    @GetMapping
    public String listAssignments(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Long areaId,
            @RequestParam(required = false) String holding,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String dir,
            Model model,
            Authentication authentication) {

        // A deactivated account must not keep reading the log through a session
        // that was opened before it was switched off.
        if (!isCallerActive(authentication)) {
            return "redirect:/login?logout";
        }

        String sortKey = PagingSupport.safeSortKey(
                sort, PropertyAssignmentService.ASSIGNMENT_LOG_SORT_OPTIONS,
                PropertyAssignmentService.DEFAULT_ASSIGNMENT_LOG_SORT);
        String direction = PagingSupport.safeDirection(dir);
        int pageSize = PagingSupport.safeSize(size);
        String holdingFilter = PropertyAssignmentService.acceptedHolding(holding);

        model.addAttribute("search", search);
        model.addAttribute("areaId", areaId);
        model.addAttribute("holdingFilter", holdingFilter);
        model.addAttribute("holdingOptions", PropertyAssignmentService.HOLDING_OPTIONS);
        model.addAttribute("areaOptions", physicalAreaRepository.findAllByActiveTrueOrderByNameAsc());

        // Only values the log actually accepts, so a filter written by hand is
        // neither queried nor echoed back into the pager and header links.
        String baseQuery = ListPageView.query()
                .add("search", search)
                .add("areaId", areaId)
                .add("holding", holdingFilter)
                .add("size", pageSize)
                .toString();

        ListPageView.addListState(model, sortKey, direction, pageSize, baseQuery,
                notBlank(search) || areaId != null || notBlank(holding));

        try {
            ListPageView.addPageAttributes(model, "assignments", assignmentService.searchAssignments(
                    search, areaId, holding, page, pageSize, sortKey, direction));
        } catch (IllegalArgumentException ex) {
            // Reported without repeating the offending value back.
            model.addAttribute("errorMessage", "That filter is not one of the available options.");
            ListPageView.addEmptyPageAttributes(model, "assignments");
        }

        return "assignments/list";
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private boolean isCallerActive(Authentication authentication) {
        if (authentication == null) {
            return false;
        }

        return appUserRepository.findByEmail(authentication.getName())
                .map(AppUser::isActive)
                .orElse(true);
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
