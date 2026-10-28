package com.bssa.assetmanagement.controller;

import com.bssa.assetmanagement.repository.AppUserRepository;
import com.bssa.assetmanagement.repository.PhysicalAreaRepository;
import com.bssa.assetmanagement.repository.PropertyCategoryRepository;
import com.bssa.assetmanagement.repository.PropertyTypeRepository;
import com.bssa.assetmanagement.service.DashboardReportService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;
import java.util.List;

@Controller
@RequestMapping("/reports")
public class ReportController {

    /** Shown when a filter value is not one of the accepted options. */
    private static final String INVALID_FILTER_MESSAGE =
            "That filter value is not recognised. Please choose a value from the list.";

    private final DashboardReportService reportService;
    private final PropertyCategoryRepository categoryRepository;
    private final PropertyTypeRepository propertyTypeRepository;
    private final PhysicalAreaRepository physicalAreaRepository;
    private final AppUserRepository appUserRepository;

    public ReportController(
            DashboardReportService reportService,
            PropertyCategoryRepository categoryRepository,
            PropertyTypeRepository propertyTypeRepository,
            PhysicalAreaRepository physicalAreaRepository,
            AppUserRepository appUserRepository) {
        this.reportService = reportService;
        this.categoryRepository = categoryRepository;
        this.propertyTypeRepository = propertyTypeRepository;
        this.physicalAreaRepository = physicalAreaRepository;
        this.appUserRepository = appUserRepository;
    }

    @GetMapping
    public String index(Authentication authentication) {
        verifyAuthorized(authentication);
        return "redirect:/reports/assets";
    }

    @GetMapping("/assets")
    public String assetRegisterReport(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long propertyTypeId,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) Long areaId,
            Authentication authentication,
            Model model) {

        verifyAuthorized(authentication);

        try {
            model.addAttribute("properties",
                    reportService.getAssetRegisterReport(status, propertyTypeId, categoryId, areaId));
        } catch (IllegalArgumentException ex) {
            model.addAttribute("errorMessage", INVALID_FILTER_MESSAGE);
            model.addAttribute("properties", List.of());
        }

        model.addAttribute("categories", categoryRepository.findAllByActiveTrueOrderByNameAsc());
        model.addAttribute("propertyTypes", propertyTypeRepository.findAllByActiveTrueOrderByNameAsc());
        model.addAttribute("physicalAreas", physicalAreaRepository.findAllByActiveTrueOrderByNameAsc());
        model.addAttribute("statusSummary", reportService.getStatusSummary());

        model.addAttribute("selectedStatus", status);
        model.addAttribute("selectedTypeId", propertyTypeId);
        model.addAttribute("selectedCategoryId", categoryId);
        model.addAttribute("selectedAreaId", areaId);

        return "reports/assets";
    }

    @GetMapping("/summary")
    public String summaryReport(Authentication authentication, Model model) {
        verifyAuthorized(authentication);

        model.addAllAttributes(reportService.getInventorySummary());

        return "reports/summary";
    }

    @GetMapping("/assignments")
    public String assignmentsReport(
            @RequestParam(required = false) Long employeeId,
            @RequestParam(required = false) Long areaId,
            @RequestParam(required = false, defaultValue = "false") boolean activeOnly,
            Authentication authentication,
            Model model) {

        verifyAuthorized(authentication);

        model.addAttribute("assignments",
                reportService.getAssignmentsReport(employeeId, areaId, activeOnly));
        model.addAttribute("users", appUserRepository.findByActiveTrueOrderByEmployeeCodeAsc());
        model.addAttribute("physicalAreas", physicalAreaRepository.findAllByActiveTrueOrderByNameAsc());

        model.addAttribute("selectedEmployeeId", employeeId);
        model.addAttribute("selectedAreaId", areaId);
        model.addAttribute("activeOnly", activeOnly);

        return "reports/assignments";
    }

    @GetMapping("/requests")
    public String requestsReport(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long requesterId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            Authentication authentication,
            Model model) {

        verifyAuthorized(authentication);

        String dateError = validateDateRange(startDate, endDate);
        if (dateError != null) {
            model.addAttribute("errorMessage", dateError);
            model.addAttribute("requests", List.of());
        } else {
            try {
                model.addAttribute("requests",
                        reportService.getRequestsReport(status, requesterId, startDate, endDate));
            } catch (IllegalArgumentException ex) {
                model.addAttribute("errorMessage", INVALID_FILTER_MESSAGE);
                model.addAttribute("requests", List.of());
            }
        }

        model.addAttribute("users", appUserRepository.findByActiveTrueOrderByEmployeeCodeAsc());
        model.addAttribute("statusOptions", DashboardReportService.REQUEST_STATUS_OPTIONS);

        model.addAttribute("selectedStatus", status);
        model.addAttribute("selectedRequesterId", requesterId);
        model.addAttribute("startDate", startDate);
        model.addAttribute("endDate", endDate);

        return "reports/requests";
    }

    @GetMapping("/incidents")
    public String incidentsReport(
            @RequestParam(required = false) String incidentType,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            Authentication authentication,
            Model model) {

        verifyAuthorized(authentication);

        String dateError = validateDateRange(startDate, endDate);
        if (dateError != null) {
            model.addAttribute("errorMessage", dateError);
            model.addAttribute("incidents", List.of());
        } else {
            try {
                model.addAttribute("incidents",
                        reportService.getIncidentsReport(incidentType, status, startDate, endDate));
            } catch (IllegalArgumentException ex) {
                model.addAttribute("errorMessage", INVALID_FILTER_MESSAGE);
                model.addAttribute("incidents", List.of());
            }
        }

        model.addAttribute("incidentTypeOptions", DashboardReportService.INCIDENT_TYPE_OPTIONS);
        model.addAttribute("statusOptions", DashboardReportService.INCIDENT_STATUS_OPTIONS);

        model.addAttribute("selectedIncidentType", incidentType);
        model.addAttribute("selectedStatus", status);
        model.addAttribute("startDate", startDate);
        model.addAttribute("endDate", endDate);

        return "reports/incidents";
    }

    private void verifyAuthorized(Authentication authentication) {
        if (authentication == null) {
            throw new AccessDeniedException("Authentication required.");
        }
        boolean authorized = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(a -> a.equals("ROLE_SYSTEM_ADMIN")
                        || a.equals("ROLE_INVENTORY_ADMIN")
                        || a.equals("ROLE_INVENTORY_MANAGER"));

        if (!authorized) {
            throw new AccessDeniedException("You are not authorized to view reports.");
        }
    }

    /**
     * Rejects a reversed date range with a safe, generic message. Detail of the
     * supplied values is deliberately not echoed back.
     */
    private String validateDateRange(LocalDate startDate, LocalDate endDate) {
        if (startDate != null && endDate != null && startDate.isAfter(endDate)) {
            return "The start date must not be after the end date.";
        }
        return null;
    }
}
