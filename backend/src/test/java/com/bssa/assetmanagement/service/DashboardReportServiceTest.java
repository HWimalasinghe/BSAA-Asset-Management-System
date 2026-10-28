package com.bssa.assetmanagement.service;

import com.bssa.assetmanagement.entity.AppUser;
import com.bssa.assetmanagement.entity.Property;
import com.bssa.assetmanagement.entity.PropertyAssignment;
import com.bssa.assetmanagement.entity.PropertyIncident;
import com.bssa.assetmanagement.entity.PropertyRequest;
import com.bssa.assetmanagement.entity.PropertyStatusHistory;
import com.bssa.assetmanagement.repository.AppUserRepository;
import com.bssa.assetmanagement.repository.NotificationRepository;
import com.bssa.assetmanagement.repository.PropertyAssignmentRepository;
import com.bssa.assetmanagement.repository.PropertyIncidentRepository;
import com.bssa.assetmanagement.repository.PropertyRepository;
import com.bssa.assetmanagement.repository.PropertyRequestRepository;
import com.bssa.assetmanagement.repository.PropertyStatusHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DashboardReportServiceTest {

    private final PropertyRepository propertyRepository = mock(PropertyRepository.class);
    private final PropertyRequestRepository requestRepository = mock(PropertyRequestRepository.class);
    private final PropertyIncidentRepository incidentRepository = mock(PropertyIncidentRepository.class);
    private final PropertyAssignmentRepository assignmentRepository = mock(PropertyAssignmentRepository.class);
    private final PropertyStatusHistoryRepository statusHistoryRepository = mock(PropertyStatusHistoryRepository.class);
    private final AppUserRepository appUserRepository = mock(AppUserRepository.class);
    private final NotificationRepository notificationRepository = mock(NotificationRepository.class);

    private final DashboardReportService service = new DashboardReportService(
            propertyRepository,
            requestRepository,
            incidentRepository,
            assignmentRepository,
            statusHistoryRepository,
            appUserRepository,
            notificationRepository);

    private static final List<String> PRIVILEGED = List.of("SYSTEM_ADMIN");

    @BeforeEach
    void setUp() {
        reset(propertyRepository, requestRepository, incidentRepository,
                assignmentRepository, statusHistoryRepository, appUserRepository,
                notificationRepository);
    }

    // ------------------------------------------------------- role gating

    @Test
    void getDashboardMetrics_privilegedRole_exposesOrganisationWideFigures() {
        when(propertyRepository.count()).thenReturn(40L);
        when(propertyRepository.countGroupedByStatus()).thenReturn(List.of(
                groupedCount("AVAILABLE", 25L),
                groupedCount("ASSIGNED", 15L)));
        when(requestRepository.countByStatus("PENDING")).thenReturn(3L);
        when(incidentRepository.countByStatus("OPEN")).thenReturn(2L);
        when(assignmentRepository.countOutstanding()).thenReturn(7L);
        when(statusHistoryRepository.findRecentActivity(any(Pageable.class)))
                .thenReturn(List.of());
        when(appUserRepository.findByEmail(any())).thenReturn(Optional.empty());

        Map<String, Object> metrics = service.getDashboardMetrics("admin@example.com", PRIVILEGED);

        assertEquals(40L, metrics.get("totalAssets"));
        assertEquals(25L, metrics.get("availableAssets"));
        assertEquals(15L, metrics.get("assignedAssets"));
        assertEquals(3L, metrics.get("pendingRequests"));
        assertEquals(2L, metrics.get("openIncidents"));
        assertEquals(7L, metrics.get("outstandingAssignments"));
    }

    @Test
    void getDashboardMetrics_damagedMetricCombinesDamagedAndUnderRepair() {
        stubStatusCounts(Map.of(
                "AVAILABLE", 10L,
                "ASSIGNED", 5L,
                "DAMAGED", 4L,
                "UNDER_REPAIR", 2L,
                "LOST", 1L,
                "RETIRED", 3L,
                "UNDER_INSPECTION", 6L));

        Map<String, Object> metrics = service.getDashboardMetrics("admin@example.com", PRIVILEGED);

        assertEquals(6L, metrics.get("damagedAssets"));
        assertEquals(4L, metrics.get("damagedOnlyAssets"));
        assertEquals(2L, metrics.get("underRepairAssets"));
    }

    @Test
    void getDashboardMetrics_officeStaff_neverQueriesOrganisationWideTotals() {
        when(appUserRepository.findByEmail(any())).thenReturn(Optional.of(user(9L, "staff@example.com")));
        when(assignmentRepository.countByEmployee_UserIdAndProperty_Status(9L, "ASSIGNED")).thenReturn(2L);
        when(requestRepository.countByRequester_UserIdAndStatus(9L, "PENDING")).thenReturn(1L);
        when(incidentRepository.countByReportedBy_UserIdAndStatus(9L, "OPEN")).thenReturn(0L);
        when(notificationRepository.countByUser_UserIdAndReadFalse(9L)).thenReturn(4L);

        Map<String, Object> metrics = service.getDashboardMetrics(
                "staff@example.com", List.of("OFFICE_STAFF"));

        // Server-side enforcement: the queries are not even issued.
        verifyNoInteractions(propertyRepository);
        verify(statusHistoryRepository, never()).findRecentActivity(any());

        assertFalse(metrics.containsKey("totalAssets"));
        assertFalse(metrics.containsKey("pendingRequests"));
        assertFalse(metrics.containsKey("openIncidents"));

        // Staff still get their own figures.
        assertEquals(2L, metrics.get("myAssignedAssets"));
        assertEquals(1L, metrics.get("myPendingRequests"));
        assertEquals(4L, metrics.get("myUnreadNotifications"));
    }

    @Test
    void getDashboardMetrics_multiRoleStaffAndManager_getsOverview() {
        stubStatusCounts(Map.of("AVAILABLE", 1L));
        when(statusHistoryRepository.findRecentActivity(any(Pageable.class))).thenReturn(List.of());
        when(appUserRepository.findByEmail(any())).thenReturn(Optional.empty());

        Map<String, Object> metrics = service.getDashboardMetrics(
                "both@example.com", List.of("OFFICE_STAFF", "INVENTORY_MANAGER"));

        assertTrue(metrics.containsKey("totalAssets"),
                "A privileged role held alongside OFFICE_STAFF must not be masked");
    }

    @Test
    void getDashboardMetrics_unknownUser_omitsPersonalFiguresButKeepsOverview() {
        stubStatusCounts(Map.of("AVAILABLE", 8L));
        when(statusHistoryRepository.findRecentActivity(any(Pageable.class))).thenReturn(List.of());
        when(appUserRepository.findByEmail(any())).thenReturn(Optional.empty());

        Map<String, Object> metrics = service.getDashboardMetrics("ghost@example.com", PRIVILEGED);

        assertTrue(metrics.containsKey("totalAssets"));
        assertFalse(metrics.containsKey("myAssignedAssets"));
    }

    @Test
    void getDashboardMetrics_recentActivityIsFetchedWithABoundedPage() {
        stubStatusCounts(Map.of());
        when(appUserRepository.findByEmail(any())).thenReturn(Optional.empty());
        List<PropertyStatusHistory> activity = List.of(new PropertyStatusHistory());
        when(statusHistoryRepository.findRecentActivity(any(Pageable.class))).thenReturn(activity);

        Map<String, Object> metrics = service.getDashboardMetrics("admin@example.com", PRIVILEGED);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(statusHistoryRepository).findRecentActivity(captor.capture());
        assertEquals(0, captor.getValue().getPageNumber());
        assertEquals(8, captor.getValue().getPageSize());
        assertEquals(activity, metrics.get("recentActivity"));
    }

    // ------------------------------------------------------- status summary

    @Test
    void getStatusSummary_usesOneGroupedQueryAndDefaultsMissingStatusesToZero() {
        when(propertyRepository.countGroupedByStatus()).thenReturn(List.of(
                groupedCount("ASSIGNED", 12L)));

        Map<String, Long> summary = service.getStatusSummary();

        // One query instead of one COUNT per status.
        verify(propertyRepository).countGroupedByStatus();
        verify(propertyRepository, never()).countByStatus(any());

        assertEquals(DashboardReportService.REPORT_STATUSES.size(), summary.size());
        assertEquals(List.copyOf(DashboardReportService.REPORT_STATUSES),
                List.copyOf(summary.keySet()), "Statuses must keep the declared display order");

        assertEquals(12L, summary.get("ASSIGNED"));
        assertEquals(0L, summary.get("RETIRED"));
    }

    @Test
    void getStatusSummary_ignoresUnexpectedStatusFromDatabase() {
        when(propertyRepository.countGroupedByStatus()).thenReturn(List.of(
                groupedCount("AVAILABLE", 3L),
                groupedCount("IN_A_BOX", 99L)));

        Map<String, Long> summary = service.getStatusSummary();

        assertEquals(3L, summary.get("AVAILABLE"));
        assertFalse(summary.containsKey("IN_A_BOX"));
        assertEquals(DashboardReportService.REPORT_STATUSES.size(), summary.size());
    }

    @Test
    void getStatusSummary_emptyInventoryReturnsAllZeroes() {
        when(propertyRepository.countGroupedByStatus()).thenReturn(List.of());

        Map<String, Long> summary = service.getStatusSummary();

        assertEquals(DashboardReportService.REPORT_STATUSES.size(), summary.size());
        assertTrue(summary.values().stream().allMatch(value -> value == 0L));
    }

    @Test
    void getInventorySummary_statusTotalReconcilesWithRegister() {
        stubStatusCounts(Map.of("AVAILABLE", 4L, "ASSIGNED", 6L));
        when(propertyRepository.count()).thenReturn(10L);
        when(propertyRepository.countGroupedByCategory()).thenReturn(List.of());
        when(propertyRepository.countGroupedByType()).thenReturn(List.of());
        when(propertyRepository.countGroupedByArea()).thenReturn(List.of());
        when(assignmentRepository.countActiveAssetsByDepartment()).thenReturn(List.of());
        when(assignmentRepository.countOutstanding()).thenReturn(6L);

        Map<String, Object> summary = service.getInventorySummary();

        assertEquals(10L, summary.get("statusTotal"));
        assertEquals(10L, summary.get("totalAssets"));
        assertEquals(6L, summary.get("outstandingAssignments"));
    }

    // ------------------------------------------------------- asset register

    @Test
    void getAssetRegisterReport_blankAndAllMeanNoFilter() {
        when(propertyRepository.findFilteredProperties(any(), any(), any(), any()))
                .thenReturn(List.of());

        service.getAssetRegisterReport(null, null, null, null);
        service.getAssetRegisterReport("   ", null, null, null);
        service.getAssetRegisterReport("ALL", null, null, null);
        service.getAssetRegisterReport("all", null, null, null);

        verify(propertyRepository, times(4))
                .findFilteredProperties(isNull(), isNull(), isNull(), isNull());
    }

    @Test
    void getAssetRegisterReport_normalisesCaseAndSurroundingWhitespace() {
        when(propertyRepository.findFilteredProperties(any(), any(), any(), any()))
                .thenReturn(List.of());

        service.getAssetRegisterReport("  damaged  ", 7L, 3L, 5L);

        verify(propertyRepository).findFilteredProperties("DAMAGED", 7L, 3L, 5L);
    }

    @Test
    void getAssetRegisterReport_rejectsUnknownStatus() {
        assertThrows(IllegalArgumentException.class,
                () -> service.getAssetRegisterReport("NOT_A_STATUS", null, null, null));

        verify(propertyRepository, never()).findFilteredProperties(any(), any(), any(), any());
    }

    @Test
    void getAssetRegisterReport_returnsMatchingRecords() {
        Property property = new Property();
        when(propertyRepository.findFilteredProperties("LOST", null, null, null))
                .thenReturn(List.of(property));

        assertEquals(1, service.getAssetRegisterReport("LOST", null, null, null).size());
    }

    // ------------------------------------------------------- assignments

    @Test
    void getAssignmentsReport_passesFiltersStraightThrough() {
        PropertyAssignment assignment = new PropertyAssignment();
        when(assignmentRepository.findFilteredAssignments(4L, 2L, true)).thenReturn(List.of(assignment));

        List<PropertyAssignment> result = service.getAssignmentsReport(4L, 2L, true);

        assertEquals(1, result.size());
        verify(assignmentRepository).findFilteredAssignments(4L, 2L, true);
    }

    @Test
    void getAssignmentsReport_emptyResultIsReturnedAsEmptyList() {
        when(assignmentRepository.findFilteredAssignments(isNull(), isNull(), eq(false)))
                .thenReturn(List.of());

        assertTrue(service.getAssignmentsReport(null, null, false).isEmpty());
    }

    // ------------------------------------------------------- requests

    @Test
    void getRequestsReport_normalisesStatusAndExpandsDateRangeToWholeDays() {
        when(requestRepository.findFilteredRequests(any(), any(), any(), any()))
                .thenReturn(List.of());

        LocalDate from = LocalDate.of(2026, 1, 5);
        LocalDate to = LocalDate.of(2026, 1, 12);

        service.getRequestsReport("  pending ", 8L, from, to);

        ArgumentCaptor<LocalDateTime> start = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> end = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(requestRepository).findFilteredRequests(eq("PENDING"), eq(8L), start.capture(), end.capture());

        assertEquals(from.atStartOfDay(), start.getValue(), "Start must include the whole first day");
        assertEquals(to.atTime(23, 59, 59, 999_999_999), end.getValue(), "End must include the whole last day");
    }

    @Test
    void getRequestsReport_acceptsEveryDocumentedStatus() {
        when(requestRepository.findFilteredRequests(any(), any(), any(), any()))
                .thenReturn(List.of());

        for (String status : DashboardReportService.REQUEST_STATUS_OPTIONS) {
            service.getRequestsReport(status, null, null, null);
        }

        verify(requestRepository, times(5))
                .findFilteredRequests(any(), any(), any(), any());
    }

    @Test
    void getRequestsReport_rejectsUnknownStatus() {
        assertThrows(IllegalArgumentException.class,
                () -> service.getRequestsReport("ARCHIVED", null, null, null));
    }

    // ------------------------------------------------------- incidents

    @Test
    void getIncidentsReport_normalisesBothFilters() {
        when(incidentRepository.findFilteredIncidents(any(), any(), any(), any()))
                .thenReturn(List.of());

        service.getIncidentsReport(" lost ", " resolved ", null, null);

        verify(incidentRepository).findFilteredIncidents("LOST", "RESOLVED", null, null);
    }

    @Test
    void getIncidentsReport_rejectsUnknownType() {
        assertThrows(IllegalArgumentException.class,
                () -> service.getIncidentsReport("STOLEN", "ALL", null, null));
    }

    @Test
    void getIncidentsReport_rejectsUnknownStatus() {
        assertThrows(IllegalArgumentException.class,
                () -> service.getIncidentsReport("DAMAGE", "WONTFIX", null, null));
    }

    @Test
    void getIncidentsReport_returnsMatchingRecords() {
        when(incidentRepository.findFilteredIncidents(eq("DAMAGE"), eq("OPEN"), any(), any()))
                .thenReturn(List.of(new PropertyIncident()));

        assertEquals(1, service.getIncidentsReport("DAMAGE", "OPEN", null, null).size());
    }

    @Test
    void reportFilterOptions_stayAlignedWithTheOwningServices() {
        // Guards against the report dropdowns drifting from the workflow rules.
        assertEquals(PropertyIncidentService.ALLOWED_INCIDENT_TYPES, DashboardReportService.INCIDENT_TYPES);
        assertEquals(PropertyIncidentService.ALLOWED_INCIDENT_STATUSES, DashboardReportService.INCIDENT_STATUSES);
        assertEquals(DashboardReportService.INCIDENT_TYPES.size(),
                DashboardReportService.INCIDENT_TYPE_OPTIONS.size());
        assertEquals(DashboardReportService.REQUEST_STATUSES.size(),
                DashboardReportService.REQUEST_STATUS_OPTIONS.size());
    }

    // ---------------------------------------------------------------- fixtures

    private void stubStatusCounts(Map<String, Long> counts) {
        when(propertyRepository.countGroupedByStatus()).thenReturn(
                counts.entrySet().stream()
                        .map(entry -> groupedCount(entry.getKey(), entry.getValue()))
                        .toList());
    }

    /**
     * Concrete stand-in for the GROUP BY projection row. Built directly rather
     * than mocked so it can be created while another stubbing is in progress.
     */
    private record StubGroupedRow(String name, Long total)
            implements PropertyRepository.GroupedCount {

        @Override
        public String getName() {
            return name;
        }

        @Override
        public Long getTotal() {
            return total;
        }
    }

    private PropertyRepository.GroupedCount groupedCount(String name, Long total) {
        return new StubGroupedRow(name, total);
    }

    private AppUser user(Long id, String email) {
        AppUser user = new AppUser();
        user.setUserId(id);
        user.setEmail(email);
        user.setFirstName("Test");
        user.setLastName("User");
        return user;
    }
}
