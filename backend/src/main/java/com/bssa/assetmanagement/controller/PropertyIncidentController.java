package com.bssa.assetmanagement.controller;

import com.bssa.assetmanagement.entity.AppUser;
import com.bssa.assetmanagement.entity.Property;
import com.bssa.assetmanagement.entity.PropertyIncident;
import com.bssa.assetmanagement.repository.AppUserRepository;
import com.bssa.assetmanagement.repository.PhysicalAreaRepository;
import com.bssa.assetmanagement.repository.PropertyRepository;
import com.bssa.assetmanagement.service.PagingSupport;
import com.bssa.assetmanagement.service.PropertyIncidentService;
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
@RequestMapping("/incidents")
public class PropertyIncidentController {

    private final PropertyIncidentService incidentService;
    private final PropertyRepository propertyRepository;
    private final PhysicalAreaRepository physicalAreaRepository;
    private final AppUserRepository appUserRepository;

    public PropertyIncidentController(
            PropertyIncidentService incidentService,
            PropertyRepository propertyRepository,
            PhysicalAreaRepository physicalAreaRepository,
            AppUserRepository appUserRepository) {
        this.incidentService = incidentService;
        this.propertyRepository = propertyRepository;
        this.physicalAreaRepository = physicalAreaRepository;
        this.appUserRepository = appUserRepository;
    }

    @GetMapping
    public String listIncidents(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            Model model,
            Authentication authentication) {

        if (!isCallerActive(authentication)) {
            return "redirect:/login?logout";
        }

        boolean hasReviewPrivilege = hasReviewRole(authentication);
        List<PropertyIncident> myIncidents = incidentService.getMyIncidents(authentication.getName());

        model.addAttribute("myIncidents", myIncidents);
        model.addAttribute("hasReviewPrivilege", hasReviewPrivilege);

        // The organization-wide register belongs to reviewers only, so the page
        // count and the query behind it are never produced for anybody else.
        if (hasReviewPrivilege) {
            model.addAttribute("openIncidents", incidentService.getOpenIncidents());
            model.addAttribute("openCount", incidentService.countOpenIncidents());

            int pageSize = PagingSupport.safeSize(size);
            ListPageView.addPagingState(
                    model, pageSize, ListPageView.query().add("size", pageSize).toString(), false);
            ListPageView.addPageAttributes(
                    model, "allIncidents", incidentService.getAllIncidentsPage(page, pageSize));
        }

        return "incidents/list";
    }

    @GetMapping("/new")
    public String showReportForm(
            @RequestParam(required = false) Long propertyId,
            Model model,
            Authentication authentication) {

        if (!isCallerActive(authentication)) {
            return "redirect:/login?logout";
        }

        List<Property> properties = propertyRepository.findAllByOrderByPropertyIdDesc();

        model.addAttribute("properties", properties);
        model.addAttribute("selectedPropertyId", propertyId);

        return "incidents/form";
    }

    @PostMapping
    public String reportIncident(
            @RequestParam Long propertyId,
            @RequestParam String incidentType,
            @RequestParam String description,
            Authentication authentication,
            RedirectAttributes redirectAttributes) {

        if (!isCallerActive(authentication)) {
            redirectAttributes.addFlashAttribute("errorMessage", "Your account has been deactivated.");
            return "redirect:/login?logout";
        }

        try {
            PropertyIncident incident = incidentService.reportIncident(
                    propertyId,
                    incidentType,
                    description,
                    authentication.getName()
            );

            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "Incident reported successfully for " + incident.getProperty().getPropertyCode() + "."
            );
            return "redirect:/incidents/" + incident.getIncidentId();

        } catch (IllegalStateException | IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
            return "redirect:/incidents/new" + (propertyId != null ? "?propertyId=" + propertyId : "");
        }
    }

    @GetMapping("/{id}")
    public String viewIncident(
            @PathVariable Long id,
            Model model,
            Authentication authentication,
            RedirectAttributes redirectAttributes) {

        if (!isCallerActive(authentication)) {
            return "redirect:/login?logout";
        }

        try {
            boolean hasReviewPrivilege = hasReviewRole(authentication);
            PropertyIncident incident = incidentService.getIncidentForUser(
                    id,
                    authentication.getName(),
                    hasReviewPrivilege
            );

            boolean isReporter = incident.getReportedBy().getEmail().equalsIgnoreCase(authentication.getName());

            model.addAttribute("incident", incident);
            model.addAttribute("isReporter", isReporter);
            model.addAttribute("hasReviewPrivilege", hasReviewPrivilege);
            model.addAttribute("areas", physicalAreaRepository.findAllByActiveTrueOrderByNameAsc());

            return "incidents/view";

        } catch (AccessDeniedException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
            return "redirect:/incidents";
        } catch (IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", "Incident not found.");
            return "redirect:/incidents";
        }
    }

    @PostMapping("/{id}/resolve")
    public String resolveIncident(
            @PathVariable Long id,
            @RequestParam String status,
            @RequestParam String decision,
            @RequestParam(required = false) String actionTaken,
            Authentication authentication,
            RedirectAttributes redirectAttributes) {

        if (!isCallerActive(authentication)) {
            redirectAttributes.addFlashAttribute("errorMessage", "Your account has been deactivated.");
            return "redirect:/login?logout";
        }

        if (!hasReviewRole(authentication)) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "You are not authorized to review or resolve incidents."
            );
            return "redirect:/incidents";
        }

        try {
            incidentService.resolveIncident(
                    id,
                    status,
                    decision,
                    actionTaken,
                    authentication.getName()
            );

            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "Incident has been " + status.toLowerCase() + " successfully."
            );

        } catch (AccessDeniedException | IllegalStateException | IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }

        return "redirect:/incidents/" + id;
    }

    @PostMapping("/return-to-service")
    public String returnToService(
            @RequestParam Long propertyId,
            @RequestParam(required = false) Long areaId,
            @RequestParam(required = false) String notes,
            Authentication authentication,
            RedirectAttributes redirectAttributes) {

        if (!isCallerActive(authentication)) {
            redirectAttributes.addFlashAttribute("errorMessage", "Your account has been deactivated.");
            return "redirect:/login?logout";
        }

        if (!hasReviewRole(authentication)) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "You are not authorized to return assets to service."
            );
            return "redirect:/incidents";
        }

        try {
            Property property = incidentService.returnToService(
                    propertyId,
                    areaId,
                    notes,
                    authentication.getName()
            );

            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "Asset " + property.getPropertyCode() + " has been certified in good condition and returned to AVAILABLE status."
            );

        } catch (AccessDeniedException | IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }

        return "redirect:/properties";
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
