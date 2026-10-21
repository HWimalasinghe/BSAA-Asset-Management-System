package com.bssa.assetmanagement.controller;

import com.bssa.assetmanagement.entity.AppUser;
import com.bssa.assetmanagement.entity.Property;
import com.bssa.assetmanagement.repository.AppUserRepository;
import com.bssa.assetmanagement.repository.PhysicalAreaRepository;
import com.bssa.assetmanagement.repository.PropertyRepository;
import com.bssa.assetmanagement.repository.PropertyTypeRepository;
import com.bssa.assetmanagement.service.PropertyService;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;

@Controller
@RequestMapping("/properties")
public class PropertyController {

    private final PropertyRepository propertyRepository;
    private final PropertyTypeRepository propertyTypeRepository;
    private final PhysicalAreaRepository physicalAreaRepository;
    private final PropertyService propertyService;
    private final AppUserRepository appUserRepository;

    public PropertyController(
            PropertyRepository propertyRepository,
            PropertyTypeRepository propertyTypeRepository,
            PhysicalAreaRepository physicalAreaRepository,
            PropertyService propertyService,
            AppUserRepository appUserRepository) {
        this.propertyRepository = propertyRepository;
        this.propertyTypeRepository = propertyTypeRepository;
        this.physicalAreaRepository = physicalAreaRepository;
        this.propertyService = propertyService;
        this.appUserRepository = appUserRepository;
    }

    @GetMapping
    public String listProperties(
            Model model,
            Authentication authentication,
            RedirectAttributes redirectAttributes) {

        if (!isCallerActive(authentication)) {
            return "redirect:/login?logout";
        }

        model.addAttribute(
                "properties",
                propertyRepository.findAllByOrderByPropertyIdDesc()
        );

        return "properties/list";
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
        model.addAttribute(
                "areas",
                physicalAreaRepository.findAllByActiveTrueOrderByNameAsc()
        );

        return "properties/form";
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

        Property property = propertyRepository.findById(id).orElse(null);

        if (property == null) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "Property not found."
            );
            return "redirect:/properties";
        }

        model.addAttribute("property", property);
        model.addAttribute(
                "propertyTypes",
                propertyTypeRepository.findAllByOrderByCategory_NameAscNameAsc()
        );
        model.addAttribute(
                "areas",
                physicalAreaRepository.findAllByOrderByNameAsc()
        );

        return "properties/form";
    }

    @PostMapping
    public String createProperty(
            @RequestParam String propertyCode,
            @RequestParam Long propertyTypeId,
            @RequestParam Long areaId,
            @RequestParam(required = false) String serialNumber,
            @RequestParam(required = false) String description,
            @RequestParam LocalDate purchaseDate,
            @RequestParam(defaultValue = "GOOD") String condition,
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
            propertyService.createProperty(
                    propertyCode,
                    propertyTypeId,
                    areaId,
                    serialNumber,
                    description,
                    purchaseDate,
                    condition,
                    authentication.getName()
            );

            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "Property registered successfully."
            );
            return "redirect:/properties";

        } catch (IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    ex.getMessage()
            );
            return "redirect:/properties/new";
        }
    }

    @PostMapping("/{id}/edit")
    public String updateProperty(
            @PathVariable Long id,
            @RequestParam String propertyCode,
            @RequestParam Long propertyTypeId,
            @RequestParam Long areaId,
            @RequestParam(required = false) String serialNumber,
            @RequestParam(required = false) String description,
            @RequestParam LocalDate purchaseDate,
            @RequestParam(defaultValue = "GOOD") String condition,
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
            propertyService.updateProperty(
                    id,
                    propertyCode,
                    propertyTypeId,
                    areaId,
                    serialNumber,
                    description,
                    purchaseDate,
                    condition,
                    authentication.getName()
            );

            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "Property updated successfully."
            );
            return "redirect:/properties";

        } catch (IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    ex.getMessage()
            );
            return "redirect:/properties/" + id + "/edit";
        }
    }

    @PostMapping("/{id}/retire")
    public String retireProperty(
            @PathVariable Long id,
            @RequestParam(required = false) String reason,
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
            propertyService.retireProperty(id, reason, authentication.getName());
            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "Asset retired successfully."
            );
        } catch (IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    ex.getMessage()
            );
        }

        return "redirect:/properties";
    }

    @PostMapping("/{id}/status")
    public String updateStatus(
            @PathVariable Long id,
            @RequestParam String newStatus,
            @RequestParam(required = false) String reason,
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
            propertyService.changeStatus(id, newStatus, reason, authentication.getName());
            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "Asset status updated successfully."
            );
        } catch (IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    ex.getMessage()
            );
        }

        return "redirect:/properties";
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
