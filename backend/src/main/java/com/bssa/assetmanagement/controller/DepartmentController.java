package com.bssa.assetmanagement.controller;

import com.bssa.assetmanagement.entity.Department;
import com.bssa.assetmanagement.repository.DepartmentRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/departments")
public class DepartmentController {

    private final DepartmentRepository departmentRepository;

    public DepartmentController(
            DepartmentRepository departmentRepository) {
        this.departmentRepository = departmentRepository;
    }

    @GetMapping
    public String listDepartments(Model model) {
        model.addAttribute(
                "departments",
                departmentRepository.findAllByOrderByNameAsc()
        );

        return "departments/list";
    }

    @GetMapping("/new")
    public String showCreateForm() {
        return "departments/form";
    }

    @GetMapping("/edit")
    public String showEditForm(
            @RequestParam Long id,
            Model model,
            RedirectAttributes redirectAttributes) {

        Department department = departmentRepository.findById(id)
                .orElse(null);

        if (department == null) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "Department not found."
            );
            return "redirect:/departments";
        }

        model.addAttribute("department", department);

        return "departments/edit-form";
    }

    @PostMapping
    public String createDepartment(
            @RequestParam String name,
            @RequestParam(required = false) String description,
            RedirectAttributes redirectAttributes) {

        String trimmedName = name.trim();

        if (trimmedName.isEmpty()) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "Department name is required."
            );
            return "redirect:/departments/new";
        }

        if (departmentRepository.existsByNameIgnoreCase(trimmedName)) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "A department with this name already exists."
            );
            return "redirect:/departments/new";
        }

        Department department = new Department();
        department.setName(trimmedName);

        if (description != null && !description.isBlank()) {
            department.setDescription(description.trim());
        }

        department.setActive(true);
        departmentRepository.save(department);

        redirectAttributes.addFlashAttribute(
                "successMessage",
                "Department created successfully."
        );

        return "redirect:/departments";
    }

    @PostMapping("/update")
    public String updateDepartment(
            @RequestParam Long id,
            @RequestParam String name,
            @RequestParam(required = false) String description,
            RedirectAttributes redirectAttributes) {

        Department department = departmentRepository.findById(id)
                .orElse(null);

        if (department == null) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "Department not found."
            );
            return "redirect:/departments";
        }

        String trimmedName = name.trim();

        if (trimmedName.isEmpty()) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "Department name is required."
            );
            return "redirect:/departments/edit?id=" + id;
        }

        if (departmentRepository
                .existsByNameIgnoreCaseAndDepartmentIdNot(
                        trimmedName,
                        id)) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "A department with this name already exists."
            );
            return "redirect:/departments/edit?id=" + id;
        }

        department.setName(trimmedName);

        if (description != null && !description.isBlank()) {
            department.setDescription(description.trim());
        } else {
            department.setDescription(null);
        }

        departmentRepository.save(department);

        redirectAttributes.addFlashAttribute(
                "successMessage",
                "Department updated successfully."
        );

        return "redirect:/departments";
    }
}
