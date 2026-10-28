package com.bssa.assetmanagement.service;

import com.bssa.assetmanagement.entity.*;
import com.bssa.assetmanagement.repository.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;

@Service
@Transactional(readOnly = true)
public class DashboardReportService {

    /** Statuses shown on the dashboard and asset register, in display order. */
    public static final List<String> REPORT_STATUSES = List.of(
            "AVAILABLE",
            "ASSIGNED",
            "UNDER_INSPECTION",
            "DAMAGED",
            "UNDER_REPAIR",
            "LOST",
            "RETIRED");

    public static final Set<String> REPORT_STATUS_SET = Set.copyOf(REPORT_STATUSES);

    /** Roles allowed to see organisation-wide inventory figures. */
    public static final Set<String> OVERVIEW_ROLES = Set.of(
            "SYSTEM_ADMIN", "INVENTORY_ADMIN", "INVENTORY_MANAGER");

    /** Filter values accepted by the requests and incidents reports, in display order. */
    public static final List<String> REQUEST_STATUS_OPTIONS = List.of(
            "PENDING", "APPROVED", "REJECTED", "FULFILLED", "CANCELLED");

    public static final List<String> INCIDENT_TYPE_OPTIONS = List.of("DAMAGE", "BROKEN", "LOST");

    public static final List<String> INCIDENT_STATUS_OPTIONS = List.of(
            "OPEN", "APPROVED", "REJECTED", "RESOLVED");

    /** Validation sets derived from the option lists above. */
    public static final Set<String> REQUEST_STATUSES = Set.copyOf(REQUEST_STATUS_OPTIONS);

    public static final Set<String> INCIDENT_TYPES = Set.copyOf(INCIDENT_TYPE_OPTIONS);

    public static final Set<String> INCIDENT_STATUSES = Set.copyOf(INCIDENT_STATUS_OPTIONS);

    private final PropertyRepository propertyRepository;
    private final PropertyRequestRepository requestRepository;
    private final PropertyIncidentRepository incidentRepository;
    private final PropertyAssignmentRepository assignmentRepository;
    private final PropertyStatusHistoryRepository statusHistoryRepository;
    private final AppUserRepository appUserRepository;
    private final NotificationRepository notificationRepository;

    public DashboardReportService(
            PropertyRepository propertyRepository,
            PropertyRequestRepository requestRepository,
            PropertyIncidentRepository incidentRepository,
            PropertyAssignmentRepository assignmentRepository,
            PropertyStatusHistoryRepository statusHistoryRepository,
            AppUserRepository appUserRepository,
            NotificationRepository notificationRepository) {
        this.propertyRepository = propertyRepository;
        this.requestRepository = requestRepository;
        this.incidentRepository = incidentRepository;
        this.assignmentRepository = assignmentRepository;
        this.statusHistoryRepository = statusHistoryRepository;
        this.appUserRepository = appUserRepository;
        this.notificationRepository = notificationRepository;
    }

    public Map<String, Object> getDashboardMetrics(String userEmail, Collection<String> roleNames) {
        Map<String, Object> metrics = new HashMap<>();

        AppUser user = appUserRepository.findByEmail(userEmail).orElse(null);

        if (hasInventoryOverviewAccess(roleNames)) {
            // One grouped query supplies every status figure, instead of one
            // COUNT per status. Totals come from the properties table, so
            // extra assignment or history rows cannot inflate them.
            Map<String, Long> byStatus = getStatusSummary();
            long damagedAssets = byStatus.get("DAMAGED");
            long underRepairAssets = byStatus.get("UNDER_REPAIR");

            metrics.put("totalAssets", propertyRepository.count());
            metrics.put("availableAssets", byStatus.get("AVAILABLE"));
            metrics.put("assignedAssets", byStatus.get("ASSIGNED"));
            metrics.put("damagedAssets", damagedAssets + underRepairAssets);
            metrics.put("damagedOnlyAssets", damagedAssets);
            metrics.put("underRepairAssets", underRepairAssets);
            metrics.put("lostAssets", byStatus.get("LOST"));
            metrics.put("retiredAssets", byStatus.get("RETIRED"));
            metrics.put("underInspectionAssets", byStatus.get("UNDER_INSPECTION"));
            metrics.put("pendingRequests", requestRepository.countByStatus("PENDING"));
            metrics.put("openIncidents", incidentRepository.countByStatus("OPEN"));
            metrics.put("outstandingAssignments", assignmentRepository.countOutstanding());
            metrics.put("recentActivity",
                    statusHistoryRepository.findRecentActivity(PageRequest.of(0, 8)));
        }

        if (user != null) {
            long myAssignedAssets = assignmentRepository
                    .countByEmployee_UserIdAndProperty_Status(user.getUserId(), "ASSIGNED");
            long myPendingRequests = requestRepository
                    .countByRequester_UserIdAndStatus(user.getUserId(), "PENDING");
            long myOpenIncidents = incidentRepository
                    .countByReportedBy_UserIdAndStatus(user.getUserId(), "OPEN");
            long myUnreadNotifications = notificationRepository
                    .countByUser_UserIdAndReadFalse(user.getUserId());

            metrics.put("myAssignedAssets", myAssignedAssets);
            metrics.put("myPendingRequests", myPendingRequests);
            metrics.put("myOpenIncidents", myOpenIncidents);
            metrics.put("myUnreadNotifications", myUnreadNotifications);
        }

        return metrics;
    }

    /**
     * A user may hold several roles, so any matching role grants the overview.
     */
    public boolean hasInventoryOverviewAccess(Collection<String> roleNames) {
        return roleNames != null && roleNames.stream().anyMatch(OVERVIEW_ROLES::contains);
    }

    public List<Property> getAssetRegisterReport(
            String status,
            Long propertyTypeId,
            Long categoryId,
            Long areaId) {

        String safeStatus = normalizeStatusFilter(status, REPORT_STATUS_SET);

        return propertyRepository.findFilteredProperties(safeStatus, propertyTypeId, categoryId, areaId);
    }

    public Map<String, Long> getStatusSummary() {
        // Seed every reportable status with zero so an empty or partially
        // populated inventory still renders a complete, stable set of figures.
        Map<String, Long> summary = new LinkedHashMap<>();
        REPORT_STATUSES.forEach(status -> summary.put(status, 0L));

        for (PropertyRepository.GroupedCount row : propertyRepository.countGroupedByStatus()) {
            String status = row.getName();
            if (status != null && summary.containsKey(status)) {
                summary.put(status, row.getTotal());
            }
        }

        return summary;
    }

    /**
     * Grouped inventory figures for the summary report. Each grouping is one
     * database aggregate query rather than a load of every property row.
     */
    public Map<String, Object> getInventorySummary() {
        Map<String, Long> statusSummary = getStatusSummary();

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("statusSummary", statusSummary);
        summary.put("statusTotal", statusSummary.values().stream().mapToLong(Long::longValue).sum());
        summary.put("categorySummary", propertyRepository.countGroupedByCategory());
        summary.put("typeSummary", propertyRepository.countGroupedByType());
        summary.put("areaSummary", propertyRepository.countGroupedByArea());
        summary.put("departmentSummary", assignmentRepository.countActiveAssetsByDepartment());
        summary.put("totalAssets", propertyRepository.count());
        summary.put("outstandingAssignments", assignmentRepository.countOutstanding());
        return summary;
    }

    public List<PropertyAssignment> getAssignmentsReport(
            Long employeeId,
            Long areaId,
            boolean activeOnly) {

        return assignmentRepository.findFilteredAssignments(employeeId, areaId, activeOnly);
    }

    public List<PropertyRequest> getRequestsReport(
            String status,
            Long requesterId,
            LocalDate startDate,
            LocalDate endDate) {

        String safeStatus = normalizeStatusFilter(status, REQUEST_STATUSES);

        LocalDateTime startDateTime = startDate != null ? startDate.atStartOfDay() : null;
        LocalDateTime endDateTime = endDate != null ? endDate.atTime(LocalTime.MAX) : null;

        return requestRepository.findFilteredRequests(safeStatus, requesterId, startDateTime, endDateTime);
    }

    public List<PropertyIncident> getIncidentsReport(
            String incidentType,
            String status,
            LocalDate startDate,
            LocalDate endDate) {

        String safeType = normalizeStatusFilter(incidentType, INCIDENT_TYPES);
        String safeStatus = normalizeStatusFilter(status, INCIDENT_STATUSES);

        LocalDateTime startDateTime = startDate != null ? startDate.atStartOfDay() : null;
        LocalDateTime endDateTime = endDate != null ? endDate.atTime(LocalTime.MAX) : null;

        return incidentRepository.findFilteredIncidents(safeType, safeStatus, startDateTime, endDateTime);
    }

    /**
     * Normalises a free-text filter value: blank or "ALL" means no filter,
     * otherwise the value must be one of the accepted options.
     *
     * @throws IllegalArgumentException if the value is not an accepted option
     */
    private String normalizeStatusFilter(String raw, Set<String> allowed) {
        if (raw == null || raw.isBlank() || "ALL".equalsIgnoreCase(raw.trim())) {
            return null;
        }

        String candidate = raw.trim().toUpperCase();
        if (!allowed.contains(candidate)) {
            throw new IllegalArgumentException("Unrecognised filter value: " + raw.trim());
        }
        return candidate;
    }
}
