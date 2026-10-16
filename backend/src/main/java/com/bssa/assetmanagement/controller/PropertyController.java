
package com.bssa.assetmanagement.controller;

import com.bssa.assetmanagement.entity.PhysicalArea;
import com.bssa.assetmanagement.entity.Property;
import com.bssa.assetmanagement.entity.PropertyType;
import com.bssa.assetmanagement.repository.PhysicalAreaRepository;
import com.bssa.assetmanagement.repository.PropertyRepository;
import com.bssa.assetmanagement.repository.PropertyTypeRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
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

    public PropertyController(
            PropertyRepository propertyRepository,
            PropertyTypeRepository propertyTypeRepository,
            PhysicalAreaRepository physicalAreaRepository) {
        this.propertyRepository = propertyRepository;
        this.propertyTypeRepository = propertyTypeRepository;
        this.physicalAreaRepository = physicalAreaRepository;
    }

    @GetMapping
    public String listProperties(Model model) {
        model.addAttribute(
                "properties",
                propertyRepository.findAllByOrderByPropertyIdDesc()
        );

        return "properties/list";
    }

    @GetMapping("/new")
    public String showCreateForm(Model model) {
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

    @PostMapping
    public String createProperty(
            @RequestParam String propertyCode,
            @RequestParam Long propertyTypeId,
            @RequestParam Long areaId,
            @RequestParam(required = false) String serialNumber,
            @RequestParam(required = false) String description,
            @RequestParam LocalDate purchaseDate,
            @RequestParam(defaultValue = "GOOD") String condition,
            RedirectAttributes redirectAttributes) {

        String trimmedCode = propertyCode.trim();

        if (trimmedCode.isEmpty()) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "Property code is required."
            );
            return "redirect:/properties/new";
        }

        if (propertyRepository.existsByPropertyCodeIgnoreCase(trimmedCode)) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "A property with this code already exists."
            );
            return "redirect:/properties/new";
        }

        if (!condition.equals("GOOD")
                && !condition.equals("DAMAGED")
                && !condition.equals("BROKEN")) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "Invalid property condition."
            );
            return "redirect:/properties/new";
        }

        PropertyType propertyType = propertyTypeRepository
                .findById(propertyTypeId)
                .orElse(null);

        if (propertyType == null || !propertyType.isActive()) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "Please select an active property type."
            );
            return "redirect:/properties/new";
        }

        PhysicalArea area = physicalAreaRepository
                .findById(areaId)
                .orElse(null);

        if (area == null || !area.isActive()) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "Please select an active physical area."
            );
            return "redirect:/properties/new";
        }

        Property property = new Property();
        property.setPropertyCode(trimmedCode);
        property.setPropertyType(propertyType);
        property.setArea(area);
        property.setSerialNumber(
                serialNumber == null || serialNumber.isBlank()
                        ? null : serialNumber.trim()
        );
        property.setDescription(
                description == null || description.isBlank()
                        ? null : description.trim()
        );
        property.setPurchaseDate(purchaseDate);
        property.setCondition(condition);
        property.setStatus("AVAILABLE");

        propertyRepository.save(property);

        redirectAttributes.addFlashAttribute(
                "successMessage",
                "Property registered successfully."
        );

        return "redirect:/properties";
    }
}
