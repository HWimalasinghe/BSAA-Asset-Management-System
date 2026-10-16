package com.bssa.assetmanagement.controller;

import com.bssa.assetmanagement.entity.PropertyCategory;
import com.bssa.assetmanagement.entity.PropertyType;
import com.bssa.assetmanagement.repository.PropertyCategoryRepository;
import com.bssa.assetmanagement.repository.PropertyTypeRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/property-types")
public class PropertyTypeController {

    private final PropertyTypeRepository propertyTypeRepository;
    private final PropertyCategoryRepository propertyCategoryRepository;

    public PropertyTypeController(
            PropertyTypeRepository propertyTypeRepository,
            PropertyCategoryRepository propertyCategoryRepository) {
        this.propertyTypeRepository = propertyTypeRepository;
        this.propertyCategoryRepository = propertyCategoryRepository;
    }

    @GetMapping
    public String listPropertyTypes(Model model) {
        model.addAttribute(
                "propertyTypes",
                propertyTypeRepository.findAllByOrderByCategory_NameAscNameAsc()
        );

        return "property-types/list";
    }

    @GetMapping("/new")
    public String showCreateForm(Model model) {
        model.addAttribute(
                "categories",
                propertyCategoryRepository.findAllByOrderByNameAsc()
        );

        return "property-types/form";
    }

    @GetMapping("/edit")
    public String showEditForm(
            @RequestParam Long id,
            Model model,
            RedirectAttributes redirectAttributes) {

        PropertyType propertyType = propertyTypeRepository.findById(id)
                .orElse(null);

        if (propertyType == null) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "Property type not found."
            );
            return "redirect:/property-types";
        }

        model.addAttribute("propertyType", propertyType);
        model.addAttribute(
                "categories",
                propertyCategoryRepository.findAllByOrderByNameAsc()
        );

        return "property-types/edit-form";
    }

    @PostMapping
    public String createPropertyType(
            @RequestParam Long categoryId,
            @RequestParam String name,
            @RequestParam String usageType,
            @RequestParam(required = false) String description,
            RedirectAttributes redirectAttributes) {

        String trimmedName = name.trim();

        if (trimmedName.isEmpty()) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "Property type name is required."
            );
            return "redirect:/property-types/new";
        }

        if (!usageType.equals("COMMON") && !usageType.equals("INDIVIDUAL")) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "Invalid usage type."
            );
            return "redirect:/property-types/new";
        }

        PropertyCategory category = propertyCategoryRepository
                .findById(categoryId)
                .orElse(null);

        if (category == null || !category.isActive()) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "Please select an active property category."
            );
            return "redirect:/property-types/new";
        }

        if (propertyTypeRepository
                .existsByCategory_CategoryIdAndNameIgnoreCaseAndUsageType(
                        categoryId, trimmedName, usageType)) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "A property type with this name and usage type already exists in this category."
            );
            return "redirect:/property-types/new";
        }

        PropertyType propertyType = new PropertyType();
        propertyType.setCategory(category);
        propertyType.setName(trimmedName);
        propertyType.setUsageType(usageType);
        propertyType.setDescription(
                description == null || description.isBlank()
                        ? null
                        : description.trim()
        );
        propertyType.setActive(true);

        propertyTypeRepository.save(propertyType);

        redirectAttributes.addFlashAttribute(
                "successMessage",
                "Property type created successfully."
        );

        return "redirect:/property-types";
    }

    @PostMapping("/update")
    public String updatePropertyType(
            @RequestParam Long id,
            @RequestParam Long categoryId,
            @RequestParam String name,
            @RequestParam String usageType,
            @RequestParam(required = false) String description,
            RedirectAttributes redirectAttributes) {

        PropertyType propertyType = propertyTypeRepository.findById(id)
                .orElse(null);

        if (propertyType == null) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "Property type not found."
            );
            return "redirect:/property-types";
        }

        String trimmedName = name.trim();

        if (trimmedName.isEmpty()) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "Property type name is required."
            );
            return "redirect:/property-types/edit?id=" + id;
        }

        if (!usageType.equals("COMMON") && !usageType.equals("INDIVIDUAL")) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "Invalid usage type."
            );
            return "redirect:/property-types/edit?id=" + id;
        }

        PropertyCategory category = propertyCategoryRepository
                .findById(categoryId)
                .orElse(null);

        if (category == null || !category.isActive()) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "Please select an active property category."
            );
            return "redirect:/property-types/edit?id=" + id;
        }

        if (propertyTypeRepository
                .existsByCategory_CategoryIdAndNameIgnoreCaseAndUsageTypeAndPropertyTypeIdNot(
                        categoryId, trimmedName, usageType, id)) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "A property type with this name and usage type already exists in this category."
            );
            return "redirect:/property-types/edit?id=" + id;
        }

        propertyType.setCategory(category);
        propertyType.setName(trimmedName);
        propertyType.setUsageType(usageType);
        propertyType.setDescription(
                description == null || description.isBlank()
                        ? null
                        : description.trim()
        );

        propertyTypeRepository.save(propertyType);

        redirectAttributes.addFlashAttribute(
                "successMessage",
                "Property type updated successfully."
        );

        return "redirect:/property-types";
    }

    @PostMapping("/toggle-status")
    public String togglePropertyTypeStatus(
            @RequestParam Long id,
            RedirectAttributes redirectAttributes) {

        PropertyType propertyType = propertyTypeRepository.findById(id)
                .orElse(null);

        if (propertyType == null) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "Property type not found."
            );
            return "redirect:/property-types";
        }

        propertyType.setActive(!propertyType.isActive());
        propertyTypeRepository.save(propertyType);

        String message = propertyType.isActive()
                ? "Property type activated successfully."
                : "Property type deactivated successfully.";

        redirectAttributes.addFlashAttribute("successMessage", message);

        return "redirect:/property-types";
    }
}
