package com.bssa.assetmanagement.controller;

import com.bssa.assetmanagement.entity.PhysicalArea;
import com.bssa.assetmanagement.repository.PhysicalAreaRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/physical-areas")
public class PhysicalAreaController {

    private final PhysicalAreaRepository physicalAreaRepository;

    public PhysicalAreaController(
            PhysicalAreaRepository physicalAreaRepository) {
        this.physicalAreaRepository = physicalAreaRepository;
    }

    @GetMapping
    public String listPhysicalAreas(Model model) {
        model.addAttribute(
                "physicalAreas",
                physicalAreaRepository.findAllByOrderByNameAsc()
        );

        return "physical-areas/list";
    }

    @GetMapping("/new")
    public String showCreateForm() {
        return "physical-areas/form";
    }

    @PostMapping
    public String createPhysicalArea(
            @RequestParam String name,
            @RequestParam(required = false) String description,
            RedirectAttributes redirectAttributes) {

        String trimmedName = name.trim();

        if (trimmedName.isEmpty()) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "Physical area name is required."
            );
            return "redirect:/physical-areas/new";
        }

        if (physicalAreaRepository.existsByNameIgnoreCase(trimmedName)) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "A physical area with this name already exists."
            );
            return "redirect:/physical-areas/new";
        }

        PhysicalArea physicalArea = new PhysicalArea();
        physicalArea.setName(trimmedName);

        if (description != null && !description.isBlank()) {
            physicalArea.setDescription(description.trim());
        }

        physicalArea.setActive(true);
        physicalAreaRepository.save(physicalArea);

        redirectAttributes.addFlashAttribute(
                "successMessage",
                "Physical area created successfully."
        );

        return "redirect:/physical-areas";
    }
}
