package com.bssa.assetmanagement.controller;

import com.bssa.assetmanagement.entity.PropertyCategory;
import com.bssa.assetmanagement.repository.PropertyCategoryRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/property-categories")
public class PropertyCategoryController {

    private final PropertyCategoryRepository propertyCategoryRepository;

    public PropertyCategoryController(
            PropertyCategoryRepository propertyCategoryRepository) {
        this.propertyCategoryRepository = propertyCategoryRepository;
    }

    @GetMapping
    public String listCategories(Model model) {
        model.addAttribute(
                "categories",
                propertyCategoryRepository.findAllByOrderByNameAsc()
        );

        return "property-categories/list";
    }

    @GetMapping("/new")
    public String showCreateForm() {
        return "property-categories/form";
    }

    @GetMapping("/edit")
    public String showEditForm(
            @RequestParam Long id,
            Model model,
            RedirectAttributes redirectAttributes) {

        PropertyCategory category = propertyCategoryRepository
                .findById(id)
                .orElse(null);

        if (category == null) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "Property category not found."
            );
            return "redirect:/property-categories";
        }

        model.addAttribute("category", category);

        return "property-categories/edit-form";
    }

    @PostMapping
    public String createCategory(
            @RequestParam String name,
            @RequestParam(required = false) String description,
            RedirectAttributes redirectAttributes) {

        String trimmedName = name.trim();

        if (trimmedName.isEmpty()) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "Category name is required."
            );
            return "redirect:/property-categories/new";
        }

        if (propertyCategoryRepository.existsByNameIgnoreCase(trimmedName)) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "A category with this name already exists."
            );
            return "redirect:/property-categories/new";
        }

        PropertyCategory category = new PropertyCategory();
        category.setName(trimmedName);

        if (description != null && !description.isBlank()) {
            category.setDescription(description.trim());
        }

        category.setActive(true);
        propertyCategoryRepository.save(category);

        redirectAttributes.addFlashAttribute(
                "successMessage",
                "Property category created successfully."
        );

        return "redirect:/property-categories";
    }

    @PostMapping("/update")
    public String updateCategory(
            @RequestParam Long id,
            @RequestParam String name,
            @RequestParam(required = false) String description,
            RedirectAttributes redirectAttributes) {

        PropertyCategory category = propertyCategoryRepository
                .findById(id)
                .orElse(null);

        if (category == null) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "Property category not found."
            );
            return "redirect:/property-categories";
        }

        String trimmedName = name.trim();

        if (trimmedName.isEmpty()) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "Category name is required."
            );
            return "redirect:/property-categories/edit?id=" + id;
        }

        if (propertyCategoryRepository
                .existsByNameIgnoreCaseAndCategoryIdNot(trimmedName, id)) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "A category with this name already exists."
            );
            return "redirect:/property-categories/edit?id=" + id;
        }

        category.setName(trimmedName);

        if (description != null && !description.isBlank()) {
            category.setDescription(description.trim());
        } else {
            category.setDescription(null);
        }

        propertyCategoryRepository.save(category);

        redirectAttributes.addFlashAttribute(
                "successMessage",
                "Property category updated successfully."
        );

        return "redirect:/property-categories";
    }
    
    @PostMapping("/toggle-status")
    public String toggleCategoryStatus(
            @RequestParam Long id,
            RedirectAttributes redirectAttributes) {

        PropertyCategory category = propertyCategoryRepository.findById(id)
                .orElse(null);

        if (category == null) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage", "Property category not found.");
            return "redirect:/property-categories";
        }

        category.setActive(!category.isActive());
        propertyCategoryRepository.save(category);

        String message = category.isActive()
                ? "Property category activated successfully."
                : "Property category deactivated successfully.";

        redirectAttributes.addFlashAttribute("successMessage", message);

        return "redirect:/property-categories";
    }
}
