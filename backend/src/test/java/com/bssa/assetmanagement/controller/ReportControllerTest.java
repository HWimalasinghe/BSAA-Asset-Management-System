package com.bssa.assetmanagement.controller;

import com.bssa.assetmanagement.repository.AppUserRepository;
import com.bssa.assetmanagement.repository.PhysicalAreaRepository;
import com.bssa.assetmanagement.repository.PropertyCategoryRepository;
import com.bssa.assetmanagement.repository.PropertyTypeRepository;
import com.bssa.assetmanagement.service.DashboardReportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ReportControllerTest {

    private final DashboardReportService reportService = mock(DashboardReportService.class);
    private final PropertyCategoryRepository categoryRepository = mock(PropertyCategoryRepository.class);
    private final PropertyTypeRepository propertyTypeRepository = mock(PropertyTypeRepository.class);
    private final PhysicalAreaRepository physicalAreaRepository = mock(PhysicalAreaRepository.class);
    private final AppUserRepository appUserRepository = mock(AppUserRepository.class);

    private final ReportController controller = new ReportController(
            reportService,
            categoryRepository,
            propertyTypeRepository,
            physicalAreaRepository,
            appUserRepository);

    @BeforeEach
    void setUp() {
        reset(reportService, categoryRepository, propertyTypeRepository,
                physicalAreaRepository, appUserRepository);
    }

    // ------------------------------------------------------- authorization

    @ParameterizedTest
    @ValueSource(strings = {"ROLE_OFFICE_STAFF", "ROLE_BOGUS"})
    void everyReportEndpoint_rejectsRolesWithoutReportAccess(String authority) {
        UsernamePasswordAuthenticationToken auth = auth(authority);
        Model model = new ConcurrentModel();

        assertThrows(AccessDeniedException.class, () -> controller.index(auth));
        assertThrows(AccessDeniedException.class,
                () -> controller.assetRegisterReport(null, null, null, null, auth, model));
        assertThrows(AccessDeniedException.class,
                () -> controller.summaryReport(auth, model));
        assertThrows(AccessDeniedException.class,
                () -> controller.assignmentsReport(null, null, false, auth, model));
        assertThrows(AccessDeniedException.class,
                () -> controller.requestsReport(null, null, null, null, auth, model));
        assertThrows(AccessDeniedException.class,
                () -> controller.incidentsReport(null, null, null, null, auth, model));

        // Denied before any report data is touched.
        verifyNoInteractions(reportService);
    }

    @Test
    void everyReportEndpoint_rejectsUnauthenticatedRequest() {
        Model model = new ConcurrentModel();

        assertThrows(AccessDeniedException.class, () -> controller.index(null));
        assertThrows(AccessDeniedException.class,
                () -> controller.assetRegisterReport(null, null, null, null, null, model));
        assertThrows(AccessDeniedException.class, () -> controller.summaryReport(null, model));

        verifyNoInteractions(reportService);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "ROLE_SYSTEM_ADMIN", "ROLE_INVENTORY_ADMIN", "ROLE_INVENTORY_MANAGER"})
    void everyReportEndpoint_allowsInventoryRoles(String authority) {
        UsernamePasswordAuthenticationToken auth = auth(authority);

        assertEquals("redirect:/reports/assets", controller.index(auth));
        assertEquals("reports/assets", controller.assetRegisterReport(
                null, null, null, null, auth, new ConcurrentModel()));
        assertEquals("reports/summary", controller.summaryReport(auth, new ConcurrentModel()));
        assertEquals("reports/assignments", controller.assignmentsReport(
                null, null, false, auth, new ConcurrentModel()));
        assertEquals("reports/requests", controller.requestsReport(
                null, null, null, null, auth, new ConcurrentModel()));
        assertEquals("reports/incidents", controller.incidentsReport(
                null, null, null, null, auth, new ConcurrentModel()));
    }

    // ------------------------------------------------------- asset register

    @Test
    void assetRegisterReport_populatesFilterOptionsAndResults() {
        Model model = new ConcurrentModel();
        when(reportService.getAssetRegisterReport("AVAILABLE", 3L, 2L, 1L)).thenReturn(List.of());
        when(reportService.getStatusSummary()).thenReturn(availableOnlySummary());

        String view = controller.assetRegisterReport(
                "AVAILABLE", 3L, 2L, 1L, auth("ROLE_INVENTORY_MANAGER"), model);

        assertEquals("reports/assets", view);
        assertTrue(((List<?>) model.getAttribute("properties")).isEmpty());
        assertNotNull(model.getAttribute("categories"));
        assertNotNull(model.getAttribute("propertyTypes"));
        assertNotNull(model.getAttribute("physicalAreas"));
        assertNotNull(model.getAttribute("statusSummary"));
        assertEquals("AVAILABLE", model.getAttribute("selectedStatus"));
        assertEquals(3L, model.getAttribute("selectedTypeId"));
        assertEquals(2L, model.getAttribute("selectedCategoryId"));
        assertEquals(1L, model.getAttribute("selectedAreaId"));
        assertEquals(availableOnlySummary(), model.getAttribute("statusSummary"));
    }

    @Test
    void assetRegisterReport_invalidFilterRendersAnEmptyResultWithASafeMessage() {
        Model model = new ConcurrentModel();
        when(reportService.getAssetRegisterReport(anyString(), any(), any(), any()))
                .thenThrow(new IllegalArgumentException("Unrecognised filter value: NOPE"));

        controller.assetRegisterReport("NOPE", null, null, null, auth("ROLE_SYSTEM_ADMIN"), model);

        assertNotNull(model.getAttribute("errorMessage"));
        assertTrue(((List<?>) model.getAttribute("properties")).isEmpty());
        // The raw offending value is not echoed back into the message.
        assertTrue(!model.getAttribute("errorMessage").toString().contains("NOPE"));
    }

    @Test
    void assetRegisterReport_onlyActiveMasterDataIsOfferedInDropdowns() {
        Model model = new ConcurrentModel();
        when(reportService.getStatusSummary()).thenReturn(availableOnlySummary());

        controller.assetRegisterReport(null, null, null, null, auth("ROLE_SYSTEM_ADMIN"), model);

        verify(categoryRepository).findAllByActiveTrueOrderByNameAsc();
        verify(propertyTypeRepository).findAllByActiveTrueOrderByNameAsc();
        verify(physicalAreaRepository).findAllByActiveTrueOrderByNameAsc();
    }

    // ------------------------------------------------------- summary

    @Test
    void summaryReport_exposesGroupedFiguresToTheTemplate() {
        Model model = new ConcurrentModel();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("statusSummary", availableOnlySummary());
        summary.put("statusTotal", 4L);
        summary.put("categorySummary", List.of());
        summary.put("typeSummary", List.of());
        summary.put("areaSummary", List.of());
        summary.put("departmentSummary", List.of());
        summary.put("totalAssets", 4L);
        summary.put("outstandingAssignments", 0L);
        when(reportService.getInventorySummary()).thenReturn(summary);

        assertEquals("reports/summary", controller.summaryReport(auth("ROLE_INVENTORY_ADMIN"), model));

        assertEquals(4L, model.getAttribute("statusTotal"));
        assertEquals(4L, model.getAttribute("totalAssets"));
        assertNotNull(model.getAttribute("departmentSummary"));
    }

    // ------------------------------------------------------- assignments

    @Test
    void assignmentsReport_passesActiveOnlyFilterAndEchoesSelection() {
        Model model = new ConcurrentModel();
        when(reportService.getAssignmentsReport(5L, 2L, true)).thenReturn(List.of());

        String view = controller.assignmentsReport(
                5L, 2L, true, auth("ROLE_INVENTORY_MANAGER"), model);

        assertEquals("reports/assignments", view);
        assertEquals(5L, model.getAttribute("selectedEmployeeId"));
        assertEquals(2L, model.getAttribute("selectedAreaId"));
        assertEquals(true, model.getAttribute("activeOnly"));
        verify(reportService).getAssignmentsReport(5L, 2L, true);
    }

    // ------------------------------------------------------- requests

    @Test
    void requestsReport_passesDateRangeThroughAndEchoesIt() {
        Model model = new ConcurrentModel();
        LocalDate from = LocalDate.of(2026, 1, 1);
        LocalDate to = LocalDate.of(2026, 3, 1);
        when(reportService.getRequestsReport("PENDING", 6L, from, to)).thenReturn(List.of());

        controller.requestsReport("PENDING", 6L, from, to, auth("ROLE_SYSTEM_ADMIN"), model);

        assertEquals(from, model.getAttribute("startDate"));
        assertEquals(to, model.getAttribute("endDate"));
        assertEquals("PENDING", model.getAttribute("selectedStatus"));
        assertNotNull(model.getAttribute("statusOptions"));
        verify(reportService).getRequestsReport("PENDING", 6L, from, to);
    }

    @Test
    void requestsReport_reversedDateRangeIsRejectedWithoutQuerying() {
        Model model = new ConcurrentModel();

        controller.requestsReport(null, null,
                LocalDate.of(2026, 5, 20), LocalDate.of(2026, 5, 1),
                auth("ROLE_SYSTEM_ADMIN"), model);

        assertNotNull(model.getAttribute("errorMessage"));
        assertTrue(((List<?>) model.getAttribute("requests")).isEmpty());
        verify(reportService, never())
                .getRequestsReport(any(), any(), any(), any());
    }

    @Test
    void requestsReport_invalidStatusFallsBackToEmptyResultWithMessage() {
        Model model = new ConcurrentModel();
        when(reportService.getRequestsReport(anyString(), any(), any(), any()))
                .thenThrow(new IllegalArgumentException("Unrecognised filter value: MAYBE"));

        controller.requestsReport("MAYBE", null, null, null, auth("ROLE_SYSTEM_ADMIN"), model);

        assertNotNull(model.getAttribute("errorMessage"));
        assertTrue(((List<?>) model.getAttribute("requests")).isEmpty());
    }

    @Test
    void requestsReport_singleSidedDateRangeIsAccepted() {
        Model model = new ConcurrentModel();
        LocalDate from = LocalDate.of(2026, 1, 1);
        when(reportService.getRequestsReport(any(), any(), any(), any())).thenReturn(List.of());

        controller.requestsReport(null, null, from, null, auth("ROLE_SYSTEM_ADMIN"), model);

        verify(reportService).getRequestsReport(null, null, from, null);
        assertEquals(null, model.getAttribute("errorMessage"));
    }

    // ------------------------------------------------------- incidents

    @Test
    void incidentsReport_exposesTypeAndStatusOptions() {
        Model model = new ConcurrentModel();
        when(reportService.getIncidentsReport(any(), any(), any(), any())).thenReturn(List.of());

        controller.incidentsReport("DAMAGE", "OPEN", null, null, auth("ROLE_INVENTORY_MANAGER"), model);

        assertNotNull(model.getAttribute("incidentTypeOptions"));
        assertNotNull(model.getAttribute("statusOptions"));
        assertEquals("DAMAGE", model.getAttribute("selectedIncidentType"));
        assertEquals("OPEN", model.getAttribute("selectedStatus"));
    }

    @Test
    void incidentsReport_reversedDateRangeIsRejectedWithoutQuerying() {
        Model model = new ConcurrentModel();

        controller.incidentsReport(null, null,
                LocalDate.of(2026, 9, 30), LocalDate.of(2026, 9, 1),
                auth("ROLE_SYSTEM_ADMIN"), model);

        assertNotNull(model.getAttribute("errorMessage"));
        assertTrue(((List<?>) model.getAttribute("incidents")).isEmpty());
        verify(reportService, never())
                .getIncidentsReport(any(), any(), any(), any());
    }

    @Test
    void incidentsReport_invalidTypeFallsBackToEmptyResultWithMessage() {
        Model model = new ConcurrentModel();
        when(reportService.getIncidentsReport(anyString(), any(), any(), any()))
                .thenThrow(new IllegalArgumentException("Unrecognised filter value: STOLEN"));

        controller.incidentsReport("STOLEN", null, null, null, auth("ROLE_SYSTEM_ADMIN"), model);

        assertNotNull(model.getAttribute("errorMessage"));
        assertTrue(((List<?>) model.getAttribute("incidents")).isEmpty());
    }

    // ---------------------------------------------------------------- helpers

    private Map<String, Long> availableOnlySummary() {
        Map<String, Long> summary = new LinkedHashMap<>();
        summary.put("AVAILABLE", 4L);
        return summary;
    }

    private UsernamePasswordAuthenticationToken auth(String authority) {
        return new UsernamePasswordAuthenticationToken(
                "reporter@example.com", "pwd", List.of(new SimpleGrantedAuthority(authority)));
    }
}
