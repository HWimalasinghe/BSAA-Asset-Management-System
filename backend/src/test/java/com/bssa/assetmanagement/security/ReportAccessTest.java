package com.bssa.assetmanagement.security;

import com.bssa.assetmanagement.entity.AppUser;
import com.bssa.assetmanagement.entity.PhysicalArea;
import com.bssa.assetmanagement.entity.Property;
import com.bssa.assetmanagement.entity.PropertyAssignment;
import com.bssa.assetmanagement.entity.PropertyCategory;
import com.bssa.assetmanagement.entity.PropertyIncident;
import com.bssa.assetmanagement.entity.PropertyRequest;
import com.bssa.assetmanagement.entity.PropertyRequestItem;
import com.bssa.assetmanagement.entity.PropertyType;
import com.bssa.assetmanagement.repository.AppUserRepository;
import com.bssa.assetmanagement.repository.PhysicalAreaRepository;
import com.bssa.assetmanagement.repository.PropertyCategoryRepository;
import com.bssa.assetmanagement.repository.PropertyRepository;
import com.bssa.assetmanagement.repository.PropertyTypeRepository;
import com.bssa.assetmanagement.service.DashboardReportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises the report pages through the real security filter chain and the real
 * Thymeleaf engine, so both access rules and page rendering are verified together.
 * Report data is stubbed to guarantee non-empty result sets: a broken expression
 * inside th:each is only evaluated when at least one row is present.
 */
@SpringBootTest
class ReportAccessTest {

    private static final List<String> REPORT_URLS = List.of(
            "/reports",
            "/reports/assets",
            "/reports/summary",
            "/reports/assignments",
            "/reports/requests",
            "/reports/incidents");

    private static final Pattern REPORT_LINK = Pattern.compile("href\"?\\s*=\\s*\"(/reports[^\"]*)\"");

    @Autowired
    private WebApplicationContext context;

    @MockitoBean
    private DashboardReportService reportService;

    @MockitoBean
    private PropertyCategoryRepository categoryRepository;

    @MockitoBean
    private PropertyTypeRepository propertyTypeRepository;

    @MockitoBean
    private PhysicalAreaRepository physicalAreaRepository;

    @MockitoBean
    private AppUserRepository appUserRepository;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();

        PropertyCategory category = category(1L, "Laptops");
        PropertyType laptopType = propertyType(10L, "Dell Latitude 7440", category);
        PropertyType monitorType = propertyType(11L, "Dell UltraSharp U2422H", category);
        PhysicalArea storeArea = area(100L, "Main Store");
        PhysicalArea floorArea = area(101L, "Third Floor");

        when(categoryRepository.findAllByActiveTrueOrderByNameAsc()).thenReturn(List.of(category));
        when(propertyTypeRepository.findAllByActiveTrueOrderByNameAsc())
                .thenReturn(List.of(laptopType, monitorType));
        when(physicalAreaRepository.findAllByActiveTrueOrderByNameAsc())
                .thenReturn(List.of(storeArea, floorArea));

        AppUser holder = user(500L, "EMP-050", "Nimal", "Perera");
        AppUser issuer = user(501L, "EMP-051", "Kamala", "Silva");
        when(appUserRepository.findByActiveTrueOrderByEmployeeCodeAsc())
                .thenReturn(List.of(holder, issuer));
        when(appUserRepository.findByEmail(anyString()))
                .thenReturn(Optional.of(issuer));

        Property property = property(1000L, "LAP-0001", "AVAILABLE", laptopType, storeArea);
        Property damaged = property(1001L, "MON-0002", "DAMAGED", monitorType, floorArea);

        when(reportService.getAssetRegisterReport(any(), any(), any(), any()))
                .thenReturn(List.of(property, damaged));
        when(reportService.getStatusSummary()).thenReturn(statusSummary());
        when(reportService.getInventorySummary()).thenReturn(inventorySummary());
        when(reportService.getAssignmentsReport(any(), any(), anyBoolean()))
                .thenReturn(List.of(
                        assignment(900L, property, holder, issuer, storeArea, "GOOD", null),
                        assignment(901L, damaged, holder, null, floorArea, "FAIR", "Cracked bezel")));
        when(reportService.getRequestsReport(any(), any(), any(), any()))
                .thenReturn(List.of(
                        pendingRequest(700L, holder, laptopType),
                        approvedRequest(701L, holder, monitorType, issuer),
                        rejectedRequest(702L, holder, laptopType, issuer)));
        when(reportService.getIncidentsReport(any(), any(), any(), any()))
                .thenReturn(List.of(
                        openIncident(800L, damaged, holder),
                        resolvedIncident(801L, property, holder, issuer)));

        when(reportService.getDashboardMetrics(anyString(), any()))
                .thenReturn(new LinkedHashMap<>(Map.of("totalAssets", 2L)));
    }

    // ------------------------------------------------- server-side enforcement

    @ParameterizedTest
    @ValueSource(strings = {
            "/reports", "/reports/assets", "/reports/summary",
            "/reports/assignments", "/reports/requests", "/reports/incidents"})
    void anonymousRequestToAnyReportIsSentToLogin(String url) throws Exception {
        mockMvc.perform(get(url))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));

        verifyNoInteractions(reportService);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/reports", "/reports/assets", "/reports/summary",
            "/reports/assignments", "/reports/requests", "/reports/incidents"})
    @WithMockUser(username = "staff@example.com", roles = {"OFFICE_STAFF"})
    void officeStaffIsBlockedFromEveryReportUrl(String url) throws Exception {
        mockMvc.perform(get(url))
                .andExpect(status().isForbidden())
                // Nothing from the report data reaches the refused response.
                .andExpect(content().string(not(containsString("LAP-0001"))));

        // Refused by the filter chain before a single report query runs.
        verifyNoInteractions(reportService);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/reports/assets", "/reports/summary", "/reports/assignments",
            "/reports/requests", "/reports/incidents"})
    @WithMockUser(username = "manager@example.com", roles = {"INVENTORY_MANAGER"})
    void inventoryManagerCanOpenEveryReport(String url) throws Exception {
        mockMvc.perform(get(url))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "sysadmin@example.com", roles = {"SYSTEM_ADMIN"})
    void reportIndexForwardsToTheAssetRegister() throws Exception {
        mockMvc.perform(get("/reports"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/reports/assets"));
    }

    // ------------------------------------------------- pages render real rows

    @Test
    @WithMockUser(username = "sysadmin@example.com", roles = {"SYSTEM_ADMIN"})
    void assetRegisterRendersEveryColumn() throws Exception {
        mockMvc.perform(get("/reports/assets"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("LAP-0001")))
                .andExpect(content().string(containsString("MON-0002")))
                .andExpect(content().string(containsString("Dell Latitude 7440")))
                .andExpect(content().string(containsString("Dell UltraSharp U2422H")))
                .andExpect(content().string(containsString("Main Store")))
                .andExpect(content().string(containsString("2025-06-01")))
                .andExpect(content().string(containsString("AVAILABLE")))
                .andExpect(content().string(containsString("damaged")));
    }

    @Test
    @WithMockUser(username = "sysadmin@example.com", roles = {"SYSTEM_ADMIN"})
    void assignmentsReportRendersHoldersAndBothFilterBranches() throws Exception {
        mockMvc.perform(get("/reports/assignments"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("LAP-0001")))
                .andExpect(content().string(containsString("Nimal Perera")))
                .andExpect(content().string(containsString("Kamala Silva")))
                .andExpect(content().string(containsString("Cracked bezel")))
                // Assignment with no issuing officer falls back to a dash.
                .andExpect(content().string(containsString("EMP-050")))
                .andExpect(content().string(containsString("EMP-051")));
    }

    @Test
    @WithMockUser(username = "sysadmin@example.com", roles = {"SYSTEM_ADMIN"})
    void requestsReportRendersDecisionsAndItemLines() throws Exception {
        mockMvc.perform(get("/reports/requests"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("REQ-700")))
                .andExpect(content().string(containsString("REQ-702")))
                .andExpect(content().string(containsString("PENDING")))
                .andExpect(content().string(containsString("APPROVED")))
                .andExpect(content().string(containsString("REJECTED")))
                .andExpect(content().string(containsString("Needs a machine for field work")))
                .andExpect(content().string(containsString("Not required after review")))
                .andExpect(content().string(containsString("Dell UltraSharp U2422H")))
                .andExpect(content().string(containsString("Nimal Perera")))
                .andExpect(content().string(containsString("Kamala Silva")))
                // Only reviewed requests carry a review timestamp.
                .andExpect(content().string(containsString("on 2026-02-04 10:00")))
                .andExpect(content().string(not(containsString("on 2026-02-01"))));
    }

    @Test
    @WithMockUser(username = "sysadmin@example.com", roles = {"SYSTEM_ADMIN"})
    void incidentsReportRendersOpenAndClosedIncidents() throws Exception {
        mockMvc.perform(get("/reports/incidents"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("INC-800")))
                .andExpect(content().string(containsString("MON-0002")))
                .andExpect(content().string(containsString("DAMAGE")))
                .andExpect(content().string(containsString("LOST")))
                .andExpect(content().string(containsString("Third Floor")))
                .andExpect(content().string(containsString("Screen cracked during move")))
                .andExpect(content().string(containsString("Reimbursed and written off")))
                .andExpect(content().string(containsString("Nimal Perera")))
                // Open incident has no decision yet.
                .andExpect(content().string(containsString("Awaiting review")));
    }

    @Test
    @WithMockUser(username = "sysadmin@example.com", roles = {"SYSTEM_ADMIN"})
    void summaryReportRendersGroupedFigures() throws Exception {
        mockMvc.perform(get("/reports/summary"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Third Floor")))
                .andExpect(content().string(containsString("Finance")))
                .andExpect(content().string(containsString("50.0%")))
                .andExpect(content().string(containsString("Outstanding")));
    }

    // ------------------------------------------------- filters and empty data

    @Test
    @WithMockUser(username = "sysadmin@example.com", roles = {"SYSTEM_ADMIN"})
    void filtersAreForwardedToTheServiceAndEchoedBack() throws Exception {
        mockMvc.perform(get("/reports/assets")
                        .param("status", "DAMAGED")
                        .param("propertyTypeId", "11")
                        .param("categoryId", "1")
                        .param("areaId", "101"))
                .andExpect(status().isOk());

        verify(reportService).getAssetRegisterReport("DAMAGED", 11L, 1L, 101L);

        mockMvc.perform(get("/reports/requests")
                        .param("status", "PENDING")
                        .param("requesterId", "500")
                        .param("startDate", "2026-01-01")
                        .param("endDate", "2026-03-31"))
                .andExpect(status().isOk());

        verify(reportService).getRequestsReport(
                "PENDING", 500L, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31));
    }

    @Test
    @WithMockUser(username = "sysadmin@example.com", roles = {"SYSTEM_ADMIN"})
    void invalidFilterValueStillRendersWithAWarningAndNoRows() throws Exception {
        when(reportService.getAssetRegisterReport(isNull(), isNull(), isNull(), isNull()))
                .thenReturn(List.of());
        when(reportService.getAssetRegisterReport(eq("NOT_A_STATUS"), isNull(), isNull(), isNull()))
                .thenThrow(new IllegalArgumentException("Unrecognised filter value: NOT_A_STATUS"));

        mockMvc.perform(get("/reports/assets").param("status", "NOT_A_STATUS"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("not recognised")))
                // The offending value is never reflected back into the page.
                .andExpect(content().string(not(containsString("NOT_A_STATUS"))));
    }

    @Test
    @WithMockUser(username = "sysadmin@example.com", roles = {"SYSTEM_ADMIN"})
    void emptyResultSetsRenderTheEmptyStateInsteadOfFailing() throws Exception {
        when(reportService.getAssetRegisterReport(any(), any(), any(), any())).thenReturn(List.of());
        when(reportService.getAssignmentsReport(any(), any(), anyBoolean())).thenReturn(List.of());
        when(reportService.getRequestsReport(any(), any(), any(), any())).thenReturn(List.of());
        when(reportService.getIncidentsReport(any(), any(), any(), any())).thenReturn(List.of());

        mockMvc.perform(get("/reports/assets"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("No assets match the selected filters.")));
        mockMvc.perform(get("/reports/assignments"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("No assignments match the selected filters.")));
        mockMvc.perform(get("/reports/requests"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("No requests match the selected filters.")));
        mockMvc.perform(get("/reports/incidents"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("No incidents match the selected filters.")));
    }

    @Test
    @WithMockUser(username = "sysadmin@example.com", roles = {"SYSTEM_ADMIN"})
    void emptyGroupedSummariesRenderZeroes() throws Exception {
        Map<String, Long> zeroes = new LinkedHashMap<>();
        zeroes.put("AVAILABLE", 0L);
        when(reportService.getInventorySummary()).thenReturn(Map.of(
                "statusSummary", zeroes,
                "statusTotal", 0L,
                "categorySummary", List.of(),
                "typeSummary", List.of(),
                "areaSummary", List.of(),
                "departmentSummary", List.of(),
                "totalAssets", 0L,
                "outstandingAssignments", 0L));

        mockMvc.perform(get("/reports/summary"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("No categories have registered assets yet.")));
    }

    @Test
    @WithMockUser(username = "sysadmin@example.com", roles = {"SYSTEM_ADMIN"})
    void reversedDateRangeIsRejectedWithoutQuerying() throws Exception {
        mockMvc.perform(get("/reports/requests")
                        .param("startDate", "2026-04-10")
                        .param("endDate", "2026-04-01"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("start date must not be after")));

        mockMvc.perform(get("/reports/incidents")
                        .param("startDate", "2026-04-10")
                        .param("endDate", "2026-04-01"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("start date must not be after")));

        verify(reportService, never()).getRequestsReport(any(), any(), any(), any());
        verify(reportService, never()).getIncidentsReport(any(), any(), any(), any());
    }

    @Test
    @WithMockUser(username = "sysadmin@example.com", roles = {"SYSTEM_ADMIN"})
    void malformedDateParameterIsHandledByTheFrameworkNotAsData() throws Exception {
        // A non-date value must not reach the report query.
        mockMvc.perform(get("/reports/requests").param("startDate", "yesterday"))
                .andExpect(status().isBadRequest());

        verify(reportService, never()).getRequestsReport(any(), any(), any(), any());
    }

    // ------------------------------------------------- dashboard navigation

    @Test
    @WithMockUser(username = "sysadmin@example.com", roles = {"SYSTEM_ADMIN"})
    void everyReportLinkOnTheDashboardResolves() throws Exception {
        MvcResult dashboard = mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andReturn();

        String html = dashboard.getResponse().getContentAsString();
        Matcher matcher = REPORT_LINK.matcher(html);
        int links = 0;
        while (matcher.find()) {
            links++;
            String url = matcher.group(1).replaceFirst("^/reports$", "/reports/assets");
            int status = mockMvc.perform(get(url)).andReturn().getResponse().getStatus();
            assertTrue(status == 200 || status == 302,
                    "Dashboard link " + matcher.group(1) + " returned " + status);
        }
        assertTrue(links >= 3, "Expected the dashboard to offer report links, found " + links);
    }

    @Test
    @WithMockUser(username = "staff@example.com", roles = {"OFFICE_STAFF"})
    void officeStaffDashboardHidesOrganisationWideFigures() throws Exception {
        MvcResult result = mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andReturn();

        String html = result.getResponse().getContentAsString();
        assertFalse(REPORT_LINK.matcher(html).find(),
                "Reports must not be offered to a role that cannot open them");
    }

    // ---------------------------------------------------------------- fixtures

    private Map<String, Long> statusSummary() {
        Map<String, Long> summary = new LinkedHashMap<>();
        summary.put("AVAILABLE", 1L);
        summary.put("ASSIGNED", 1L);
        summary.put("DAMAGED", 1L);
        summary.put("LOST", 0L);
        summary.put("RETIRED", 0L);
        return summary;
    }

    private Map<String, Object> inventorySummary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("statusSummary", statusSummary());
        summary.put("statusTotal", 3L);
        summary.put("categorySummary", List.of(row("Laptops", 2L)));
        summary.put("typeSummary", List.of(
                categoryRow("Laptops", "Dell Latitude 7440", 1L),
                categoryRow("Laptops", "Dell UltraSharp U2422H", 1L)));
        summary.put("areaSummary", List.of(row("Main Store", 1L), row("Third Floor", 1L)));
        summary.put("departmentSummary", List.of(row("Finance", 2L)));
        summary.put("totalAssets", 2L);
        summary.put("outstandingAssignments", 2L);
        return summary;
    }

    private PropertyRepository.GroupedCount row(String name, Long total) {
        return new StubRow(name, total);
    }

    private PropertyRepository.CategoryGroupedCount categoryRow(
            String categoryName, String name, Long total) {
        return new StubRow(categoryName, name, total);
    }

    /** Concrete stand-ins for the GROUP BY projections used by the summary page. */
    private record StubRow(String categoryName, String name, Long total)
            implements PropertyRepository.CategoryGroupedCount {

        StubRow(String name, Long total) {
            this(null, name, total);
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public Long getTotal() {
            return total;
        }

        @Override
        public String getCategoryName() {
            return categoryName;
        }
    }

    private PropertyCategory category(Long id, String name) {
        PropertyCategory category = new PropertyCategory();
        category.setCategoryId(id);
        category.setName(name);
        category.setActive(true);
        return category;
    }

    private PropertyType propertyType(Long id, String name, PropertyCategory category) {
        PropertyType type = new PropertyType();
        type.setPropertyTypeId(id);
        type.setName(name);
        type.setCategory(category);
        type.setActive(true);
        return type;
    }

    private PhysicalArea area(Long id, String name) {
        PhysicalArea area = new PhysicalArea();
        area.setAreaId(id);
        area.setName(name);
        area.setActive(true);
        return area;
    }

    private Property property(Long id, String code, String status, PropertyType type, PhysicalArea area) {
        Property property = new Property();
        property.setPropertyId(id);
        property.setPropertyCode(code);
        property.setStatus(status);
        property.setCondition("GOOD");
        property.setSerialNumber("SN-" + id);
        property.setPurchaseDate(LocalDate.of(2025, 6, 1));
        property.setPropertyType(type);
        property.setArea(area);
        return property;
    }

    private AppUser user(Long id, String employeeCode, String firstName, String lastName) {
        AppUser user = new AppUser();
        user.setUserId(id);
        user.setEmployeeCode(employeeCode);
        user.setFirstName(firstName);
        user.setLastName(lastName);
        user.setEmail(firstName.toLowerCase() + "@example.com");
        user.setActive(true);
        return user;
    }

    private PropertyAssignment assignment(
            Long id,
            Property property,
            AppUser employee,
            AppUser issuedBy,
            PhysicalArea area,
            String conditionAtIssue,
            String notes) {

        PropertyAssignment assignment = new PropertyAssignment();
        assignment.setAssignmentId(id);
        assignment.setProperty(property);
        assignment.setEmployee(employee);
        assignment.setIssuedBy(issuedBy);
        assignment.setArea(area);
        assignment.setConditionAtIssue(conditionAtIssue);
        assignment.setNotes(notes);
        return assignment;
    }

    private PropertyRequest pendingRequest(Long id, AppUser requester, PropertyType type) {
        PropertyRequest request = new PropertyRequest();
        request.setRequestId(id);
        request.setRequester(requester);
        request.setStatus("PENDING");
        request.setRequestedAt(LocalDateTime.of(2026, 2, 1, 8, 15));
        request.setNotes("Needs a machine for field work");
        request.setItems(List.of(item(1L, type, 1)));
        return request;
    }

    private PropertyRequest approvedRequest(Long id, AppUser requester, PropertyType type, AppUser reviewer) {
        PropertyRequest request = new PropertyRequest();
        request.setRequestId(id);
        request.setRequester(requester);
        request.setStatus("APPROVED");
        request.setRequestedAt(LocalDateTime.of(2026, 2, 2, 8, 15));
        request.setReviewedBy(reviewer);
        request.setReviewedAt(LocalDateTime.of(2026, 2, 4, 10, 0));
        request.setItems(List.of(item(2L, type, 3)));
        return request;
    }

    private PropertyRequest rejectedRequest(Long id, AppUser requester, PropertyType type, AppUser reviewer) {
        PropertyRequest request = new PropertyRequest();
        request.setRequestId(id);
        request.setRequester(requester);
        request.setStatus("REJECTED");
        request.setRequestedAt(LocalDateTime.of(2026, 2, 5, 8, 15));
        request.setReviewedBy(reviewer);
        request.setReviewedAt(LocalDateTime.of(2026, 2, 6, 10, 0));
        request.setRejectionReason("Not required after review");
        request.setItems(List.of());
        return request;
    }

    private PropertyRequestItem item(Long id, PropertyType type, Integer quantity) {
        PropertyRequestItem item = new PropertyRequestItem();
        item.setRequestItemId(id);
        item.setPropertyType(type);
        item.setQuantity(quantity);
        return item;
    }

    private PropertyIncident openIncident(Long id, Property property, AppUser reporter) {
        PropertyIncident incident = new PropertyIncident();
        incident.setIncidentId(id);
        incident.setProperty(property);
        incident.setReportedBy(reporter);
        incident.setIncidentType("DAMAGE");
        incident.setStatus("OPEN");
        incident.setDescription("Screen cracked during move");
        incident.setCreatedAt(LocalDateTime.of(2026, 2, 7, 14, 45));
        return incident;
    }

    private PropertyIncident resolvedIncident(Long id, Property property, AppUser reporter, AppUser reviewer) {
        PropertyIncident incident = new PropertyIncident();
        incident.setIncidentId(id);
        incident.setProperty(property);
        incident.setReportedBy(reporter);
        incident.setIncidentType("LOST");
        incident.setStatus("RESOLVED");
        incident.setDescription("Left on a train");
        incident.setReviewedBy(reviewer);
        incident.setReviewedAt(LocalDateTime.of(2026, 2, 9, 9, 0));
        incident.setDecision("Approved for write-off");
        incident.setActionTaken("Reimbursed and written off");
        incident.setCreatedAt(LocalDateTime.of(2026, 2, 8, 11, 0));
        return incident;
    }
}
