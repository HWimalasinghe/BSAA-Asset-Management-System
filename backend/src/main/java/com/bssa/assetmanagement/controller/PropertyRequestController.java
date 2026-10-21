package com.bssa.assetmanagement.controller;

import com.bssa.assetmanagement.entity.AppUser;
import com.bssa.assetmanagement.entity.Property;
import com.bssa.assetmanagement.entity.PropertyRequest;
import com.bssa.assetmanagement.repository.AppUserRepository;
import com.bssa.assetmanagement.repository.PhysicalAreaRepository;
import com.bssa.assetmanagement.repository.PropertyRepository;
import com.bssa.assetmanagement.repository.PropertyTypeRepository;
import com.bssa.assetmanagement.service.PropertyRequestService;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

@Controller
@RequestMapping("/requests")
public class PropertyRequestController {

    private final PropertyRequestService requestService;
    private final PropertyTypeRepository propertyTypeRepository;
    private final AppUserRepository appUserRepository;
    private final PropertyRepository propertyRepository;
    private final PhysicalAreaRepository physicalAreaRepository;

    public PropertyRequestController(
            PropertyRequestService requestService,
            PropertyTypeRepository propertyTypeRepository,
            AppUserRepository appUserRepository,
            PropertyRepository propertyRepository,
            PhysicalAreaRepository physicalAreaRepository) {
        this.requestService = requestService;
        this.propertyTypeRepository = propertyTypeRepository;
        this.appUserRepository = appUserRepository;
        this.propertyRepository = propertyRepository;
        this.physicalAreaRepository = physicalAreaRepository;
    }

    @GetMapping
    public String listRequests(
            Model model,
            Authentication authentication) {

        if (!isCallerActive(authentication)) {
            return "redirect:/login?logout";
        }

        boolean hasReviewPrivilege = hasReviewRole(authentication);
        List<PropertyRequest> myRequests = requestService.getMyRequests(authentication.getName());

        model.addAttribute("myRequests", myRequests);
        model.addAttribute("hasReviewPrivilege", hasReviewPrivilege);

        if (hasReviewPrivilege) {
            model.addAttribute("pendingRequests", requestService.getPendingRequests());
            model.addAttribute("pendingCount", requestService.countPendingRequests());
            model.addAttribute("allRequests", requestService.getAllRequests());
        }

        return "requests/list";
    }

    @GetMapping("/new")
    public String showCreateForm(
            Model model,
            Authentication authentication) {

        if (!isCallerActive(authentication)) {
            return "redirect:/login?logout";
        }

        model.addAttribute(
                "propertyTypes",
                propertyTypeRepository.findAllByActiveTrueOrderByNameAsc()
        );

        return "requests/form";
    }

    @PostMapping
    public String createRequest(
            @RequestParam Long propertyTypeId,
            @RequestParam Integer quantity,
            @RequestParam(required = false) String notes,
            @RequestParam(required = false) String itemNotes,
            Authentication authentication,
            RedirectAttributes redirectAttributes) {

        if (!isCallerActive(authentication)) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "Your account has been deactivated."
            );
            return "redirect:/login?logout";
        }

        try {
            requestService.createRequest(
                    authentication.getName(),
                    propertyTypeId,
                    quantity,
                    notes,
                    itemNotes
            );

            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "Asset request submitted successfully."
            );
            return "redirect:/requests";

        } catch (IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    ex.getMessage()
            );
            return "redirect:/requests/new";
        }
    }

    @GetMapping("/{id}")
    public String viewRequest(
            @PathVariable Long id,
            Model model,
            Authentication authentication,
            RedirectAttributes redirectAttributes) {

        if (!isCallerActive(authentication)) {
            return "redirect:/login?logout";
        }

        try {
            boolean hasReviewPrivilege = hasReviewRole(authentication);
            PropertyRequest request = requestService.getRequestForUser(
                    id,
                    authentication.getName(),
                    hasReviewPrivilege
            );

            boolean isOwner = request.getRequester().getEmail().equalsIgnoreCase(authentication.getName());
            boolean isSelfRequest = isOwner;

            model.addAttribute("request", request);
            model.addAttribute("isOwner", isOwner);
            model.addAttribute("isSelfRequest", isSelfRequest);
            model.addAttribute("hasReviewPrivilege", hasReviewPrivilege);

            return "requests/view";

        } catch (AccessDeniedException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
            return "redirect:/requests";
        } catch (IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", "Request not found.");
            return "redirect:/requests";
        }
    }

    @PostMapping("/{id}/cancel")
    public String cancelRequest(
            @PathVariable Long id,
            Authentication authentication,
            RedirectAttributes redirectAttributes) {

        if (!isCallerActive(authentication)) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "Your account has been deactivated."
            );
            return "redirect:/login?logout";
        }

        try {
            requestService.cancelRequest(id, authentication.getName());
            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "Request cancelled successfully."
            );
        } catch (AccessDeniedException | IllegalStateException | IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    ex.getMessage()
            );
        }

        return "redirect:/requests";
    }

    @GetMapping("/{id}/edit")
    public String showEditForm(
            @PathVariable Long id,
            Model model,
            Authentication authentication,
            RedirectAttributes redirectAttributes) {

        if (!isCallerActive(authentication)) {
            return "redirect:/login?logout";
        }

        try {
            PropertyRequest request = requestService.getRequestForUser(
                    id,
                    authentication.getName(),
                    false
            );

            if (!"PENDING".equals(request.getStatus())) {
                redirectAttributes.addFlashAttribute(
                        "errorMessage",
                        "Only pending requests can be edited."
                );
                return "redirect:/requests/" + id;
            }

            model.addAttribute("request", request);
            model.addAttribute(
                    "propertyTypes",
                    propertyTypeRepository.findAllByActiveTrueOrderByNameAsc()
            );
            model.addAttribute("isEdit", true);

            return "requests/form";

        } catch (AccessDeniedException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
            return "redirect:/requests";
        } catch (IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", "Request not found.");
            return "redirect:/requests";
        }
    }

    @PostMapping("/{id}/edit")
    public String updateRequest(
            @PathVariable Long id,
            @RequestParam Long propertyTypeId,
            @RequestParam Integer quantity,
            @RequestParam(required = false) String notes,
            @RequestParam(required = false) String itemNotes,
            Authentication authentication,
            RedirectAttributes redirectAttributes) {

        if (!isCallerActive(authentication)) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "Your account has been deactivated."
            );
            return "redirect:/login?logout";
        }

        try {
            requestService.updateRequest(
                    id,
                    authentication.getName(),
                    propertyTypeId,
                    quantity,
                    notes,
                    itemNotes
            );

            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "Asset request updated successfully."
            );
            return "redirect:/requests/" + id;

        } catch (AccessDeniedException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
            return "redirect:/requests";
        } catch (IllegalStateException | IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
            return "redirect:/requests/" + id + "/edit";
        }
    }

    @PostMapping("/{id}/review")
    public String reviewRequest(
            @PathVariable Long id,
            @RequestParam String status,
            @RequestParam(required = false) String rejectionReason,
            Authentication authentication,
            RedirectAttributes redirectAttributes) {

        if (!isCallerActive(authentication)) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "Your account has been deactivated."
            );
            return "redirect:/login?logout";
        }

        if (!hasReviewRole(authentication)) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "You are not authorized to review asset requests."
            );
            return "redirect:/requests";
        }

        try {
            requestService.reviewRequest(
                    id,
                    authentication.getName(),
                    status,
                    rejectionReason
            );

            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "Request " + status.toLowerCase() + " successfully."
            );

        } catch (AccessDeniedException | IllegalStateException | IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }

        return "redirect:/requests/" + id;
    }

    @GetMapping("/{id}/fulfill")
    public String showFulfillForm(
            @PathVariable Long id,
            Model model,
            Authentication authentication,
            RedirectAttributes redirectAttributes) {

        if (!isCallerActive(authentication)) {
            return "redirect:/login?logout";
        }

        if (!hasReviewRole(authentication)) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "You are not authorized to fulfill asset requests."
            );
            return "redirect:/requests";
        }

        try {
            PropertyRequest request = requestService.getRequestForUser(
                    id,
                    authentication.getName(),
                    true
            );

            if (!"APPROVED".equals(request.getStatus())) {
                redirectAttributes.addFlashAttribute(
                        "errorMessage",
                        "Only approved requests can be fulfilled. Current status is " + request.getStatus() + "."
                );
                return "redirect:/requests/" + id;
            }

            Long propertyTypeId = !request.getItems().isEmpty()
                    ? request.getItems().get(0).getPropertyType().getPropertyTypeId()
                    : null;

            List<Property> availableProperties = propertyTypeId != null
                    ? propertyRepository.findByPropertyType_PropertyTypeIdAndStatusAndConditionOrderByPropertyCodeAsc(
                            propertyTypeId, "AVAILABLE", "GOOD")
                    : List.of();

            model.addAttribute("request", request);
            model.addAttribute("availableProperties", availableProperties);
            model.addAttribute("areas", physicalAreaRepository.findAllByActiveTrueOrderByNameAsc());

            return "requests/fulfill";

        } catch (AccessDeniedException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
            return "redirect:/requests";
        } catch (IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", "Request not found.");
            return "redirect:/requests";
        }
    }

    @PostMapping("/{id}/fulfill")
    public String fulfillRequest(
            @PathVariable Long id,
            @RequestParam Long propertyId,
            @RequestParam Long areaId,
            @RequestParam(required = false, defaultValue = "GOOD") String conditionAtIssue,
            @RequestParam(required = false) String notes,
            Authentication authentication,
            RedirectAttributes redirectAttributes) {

        if (!isCallerActive(authentication)) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "Your account has been deactivated."
            );
            return "redirect:/login?logout";
        }

        if (!hasReviewRole(authentication)) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "You are not authorized to fulfill asset requests."
            );
            return "redirect:/requests";
        }

        try {
            requestService.fulfillRequest(
                    id,
                    propertyId,
                    areaId,
                    conditionAtIssue,
                    notes,
                    authentication.getName()
            );

            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "Asset request fulfilled successfully and property assigned."
            );
            return "redirect:/requests/" + id;

        } catch (AccessDeniedException | IllegalStateException | IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
            return "redirect:/requests/" + id + "/fulfill";
        }
    }

    private boolean hasReviewRole(Authentication authentication) {
        if (authentication == null) {
            return false;
        }

        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(a -> a.equals("ROLE_SYSTEM_ADMIN")
                        || a.equals("ROLE_INVENTORY_ADMIN")
                        || a.equals("ROLE_INVENTORY_MANAGER"));
    }

    private boolean isCallerActive(Authentication authentication) {
        if (authentication == null) {
            return false;
        }

        return appUserRepository.findByEmail(authentication.getName())
                .map(AppUser::isActive)
                .orElse(true);
    }
}
