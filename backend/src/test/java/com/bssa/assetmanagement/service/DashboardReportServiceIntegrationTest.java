package com.bssa.assetmanagement.service;

import com.bssa.assetmanagement.entity.AppUser;
import com.bssa.assetmanagement.entity.Department;
import com.bssa.assetmanagement.entity.PhysicalArea;
import com.bssa.assetmanagement.entity.Property;
import com.bssa.assetmanagement.entity.PropertyAssignment;
import com.bssa.assetmanagement.entity.PropertyCategory;
import com.bssa.assetmanagement.entity.PropertyIncident;
import com.bssa.assetmanagement.entity.PropertyRequest;
import com.bssa.assetmanagement.entity.PropertyRequestItem;
import com.bssa.assetmanagement.entity.PropertyReturn;
import com.bssa.assetmanagement.entity.PropertyStatusHistory;
import com.bssa.assetmanagement.entity.PropertyType;
import com.bssa.assetmanagement.repository.AppUserRepository;
import com.bssa.assetmanagement.repository.DepartmentRepository;
import com.bssa.assetmanagement.repository.PhysicalAreaRepository;
import com.bssa.assetmanagement.repository.PropertyAssignmentRepository;
import com.bssa.assetmanagement.repository.PropertyCategoryRepository;
import com.bssa.assetmanagement.repository.PropertyIncidentRepository;
import com.bssa.assetmanagement.repository.PropertyRepository;
import com.bssa.assetmanagement.repository.PropertyRequestRepository;
import com.bssa.assetmanagement.repository.PropertyReturnRepository;
import com.bssa.assetmanagement.repository.PropertyStatusHistoryRepository;
import com.bssa.assetmanagement.repository.PropertyTypeRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the dashboard and summary aggregates against the real database so
 * the generated SQL is verified, not just the wiring. Every test runs inside a
 * rolled-back transaction, and assertions compare against a baseline captured
 * before seeding so pre-existing data cannot make them brittle.
 */
@SpringBootTest
@Transactional
class DashboardReportServiceIntegrationTest {

    @Autowired
    private DashboardReportService reportService;

    @Autowired
    private PropertyRepository propertyRepository;

    @Autowired
    private PropertyAssignmentRepository assignmentRepository;

    @Autowired
    private PropertyStatusHistoryRepository statusHistoryRepository;

    @Autowired
    private PropertyTypeRepository propertyTypeRepository;

    @Autowired
    private PropertyCategoryRepository propertyCategoryRepository;

    @Autowired
    private PhysicalAreaRepository physicalAreaRepository;

    @Autowired
    private DepartmentRepository departmentRepository;

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private PropertyRequestRepository requestRepository;

    @Autowired
    private PropertyIncidentRepository incidentRepository;

    @Autowired
    private PropertyReturnRepository returnRepository;

    @Test
    void dashboardMetrics_assignmentAndHistoryRowsDoNotInflateAssetTotals() {
        // Baseline taken before seeding, so the deltas are the real assertion.
        long baselineTotal = propertyRepository.count();
        long baselineAssigned = reportService.getStatusSummary().get("ASSIGNED");

        Property property = newProperty("ASSIGNED");

        // Several assignment rows and several history rows for one asset.
        AppUser holder = newUser();
        newAssignment(property, holder);
        newAssignment(property, holder);
        newHistory(property, "AVAILABLE", "ASSIGNED");
        newHistory(property, "ASSIGNED", "DAMAGED");
        newHistory(property, "DAMAGED", "ASSIGNED");

        Map<String, Object> metrics = reportService.getDashboardMetrics(
                holder.getEmail(), List.of("INVENTORY_MANAGER"));

        assertEquals(baselineTotal + 1, metrics.get("totalAssets"),
                "One new asset must add exactly one to the total, not one per join row");
        assertEquals(baselineAssigned + 1, metrics.get("assignedAssets"),
                "A single assigned asset must be counted once, not once per assignment row");
    }

    @Test
    void dashboardMetrics_countsMatchSeededData() {
        long baselineTotal = propertyRepository.count();
        long baselineAvailable = reportService.getStatusSummary().get("AVAILABLE");
        long baselineRetired = reportService.getStatusSummary().get("RETIRED");

        newProperty("AVAILABLE");
        newProperty("RETIRED");

        Map<String, Object> metrics = reportService.getDashboardMetrics(
                "nobody-" + UUID.randomUUID() + "@example.com",
                List.of("SYSTEM_ADMIN"));

        assertEquals(baselineTotal + 2, metrics.get("totalAssets"));

        Map<String, Long> statusSummary = reportService.getStatusSummary();
        assertEquals(baselineAvailable + 1, statusSummary.get("AVAILABLE"));
        assertEquals(baselineRetired + 1, statusSummary.get("RETIRED"));
    }

    @Test
    void inventorySummary_groupsByDepartmentWithoutDoubleCounting() {
        Department department = firstDepartment();
        long baselineOutstanding = assignmentRepository.countOutstanding();
        long baselineHeld = heldByDepartment(department.getName());

        AppUser holder = newUser(department);
        Property property = newProperty("ASSIGNED");

        // Two outstanding assignments of the same asset to the same holder.
        newAssignment(property, holder);
        newAssignment(property, holder);

        assertEquals(baselineHeld + 1, heldByDepartment(department.getName()),
                "COUNT(DISTINCT property) must collapse the duplicate assignment rows to one asset");

        assertEquals(baselineOutstanding + 2,
                reportService.getInventorySummary().get("outstandingAssignments"),
                "Outstanding returns counts assignment rows, one per unreturned assignment");
    }

    @Test
    void inventorySummary_statusTotalsReconcileWithRegisteredAssets() {
        Map<String, Object> summary = reportService.getInventorySummary();

        assertEquals(summary.get("totalAssets"), summary.get("statusTotal"),
                "Status buckets must partition the register exactly - no missing or duplicated rows");
    }

    @Test
    void inventorySummary_groupingsReturnConsistentTotals() {
        Map<String, Object> summary = reportService.getInventorySummary();
        long registered = (Long) summary.get("totalAssets");

        // Each grouping covers every property, so each must sum to the register.
        assertEquals(registered, sumOf((List<?>) summary.get("categorySummary")));
        assertEquals(registered, sumOf((List<?>) summary.get("typeSummary")));
        assertEquals(registered, sumOf((List<?>) summary.get("areaSummary")));
    }

    @Test
    void statusSummary_exposesEveryReportableStatusEvenWhenEmpty() {
        Map<String, Long> summary = reportService.getStatusSummary();

        assertEquals(DashboardReportService.REPORT_STATUSES.size(), summary.size());
        for (String status : DashboardReportService.REPORT_STATUSES) {
            assertNotNull(summary.get(status), "Every status must be present: " + status);
        }
    }

    @Test
    void hasInventoryOverviewAccess_anyPrivilegedRoleInMultiRoleSetIsGranted() {
        assertTrue(reportService.hasInventoryOverviewAccess(List.of("OFFICE_STAFF", "INVENTORY_MANAGER")));
        assertTrue(reportService.hasInventoryOverviewAccess(List.of("SYSTEM_ADMIN")));
        assertTrue(!reportService.hasInventoryOverviewAccess(List.of("OFFICE_STAFF")));
        assertTrue(!reportService.hasInventoryOverviewAccess(List.of()));
        assertTrue(!reportService.hasInventoryOverviewAccess(null));
    }

    @Test
    void dashboardMetrics_recentActivityIsNewestFirstAndBounded() {
        Property property = newProperty("AVAILABLE");
        PropertyStatusHistory oldest = newHistory(property, "AVAILABLE", "ASSIGNED");
        PropertyStatusHistory middle = newHistory(property, "ASSIGNED", "DAMAGED");
        PropertyStatusHistory newest = newHistory(property, "DAMAGED", "UNDER_REPAIR");

        Map<String, Object> metrics = reportService.getDashboardMetrics(
                "nobody-" + UUID.randomUUID() + "@example.com",
                List.of("SYSTEM_ADMIN"));

        @SuppressWarnings("unchecked")
        List<PropertyStatusHistory> activity =
                (List<PropertyStatusHistory>) metrics.get("recentActivity");

        assertNotNull(activity, "Privileged roles must receive the activity list");
        assertTrue(activity.size() <= 8,
                "The dashboard tile must stay bounded instead of loading every history row");

        List<Long> order = activity.stream().map(PropertyStatusHistory::getHistoryId).toList();
        int newestIndex = order.indexOf(newest.getHistoryId());
        int middleIndex = order.indexOf(middle.getHistoryId());
        int oldestIndex = order.indexOf(oldest.getHistoryId());

        assertTrue(newestIndex >= 0 && middleIndex >= 0 && oldestIndex >= 0,
                "All seeded history rows must appear inside the fetched window");
        assertTrue(newestIndex < middleIndex && middleIndex < oldestIndex,
                "Recent inventory activity must be ordered newest first");

        // The entity graph must eager-load the joins the dashboard renders.
        assertNotNull(newest.getProperty().getPropertyCode());
        assertNotNull(newest.getChangedBy().getFirstName());
    }

    @Test
    void requestsReport_filtersByStatusRequesterAndWholeDayDateRange() {
        AppUser requester = newUser();
        PropertyType type = anyActiveType();

        // Two item rows on one request: a fetch-joined collection must not
        // duplicate the request in the result.
        PropertyRequest pending = newRequest(requester, "PENDING", type, type);
        PropertyRequest approved = newRequest(requester, "APPROVED", type);

        List<Long> ids = reportService.getRequestsReport(null, requester.getUserId(), null, null)
                .stream().map(PropertyRequest::getRequestId).toList();
        assertEquals(ids.size(), new java.util.HashSet<>(ids).size(),
                "A request with several items must be reported once, not once per item");
        assertTrue(ids.containsAll(List.of(pending.getRequestId(), approved.getRequestId())));

        List<PropertyRequest> onlyPending =
                reportService.getRequestsReport("PENDING", requester.getUserId(), null, null);
        assertEquals(1, onlyPending.size());
        assertEquals(pending.getRequestId(), onlyPending.get(0).getRequestId());

        // requested_at is stamped by the database clock, so the ranges are
        // expressed relative to the epoch rather than to the JVM's local day.
        assertEquals(2, reportService.getRequestsReport(
                null, requester.getUserId(), LocalDate.of(2000, 1, 1), LocalDate.of(2100, 1, 1)).size(),
                "A range spanning today must keep both requests");

        assertTrue(reportService.getRequestsReport(
                null, requester.getUserId(), LocalDate.of(2000, 1, 1), LocalDate.of(2000, 1, 2)).isEmpty(),
                "A range long in the past must exclude today's requests");
    }

    @Test
    void incidentsReport_filtersByTypeStatusAndDateRange() {
        AppUser reporter = newUser();
        Property property = newProperty("DAMAGED");

        // DAMAGE/BROKEN and OPEN/APPROVED are the widest vocabulary the shared
        // development database currently accepts: its chk_incidents_type and
        // chk_incidents_status constraints predate the LOST type and RESOLVED
        // status now declared in database/schema.sql. LOST/RESOLVED rendering is
        // covered by ReportAccessTest; the drift is reported to the project owner.
        PropertyIncident damage = newIncident(property, reporter, "DAMAGE", "OPEN");
        PropertyIncident broken = newIncident(property, reporter, "BROKEN", "APPROVED");

        List<Long> ids = reportService.getIncidentsReport(null, null, null, null)
                .stream().map(PropertyIncident::getIncidentId).toList();
        assertTrue(ids.containsAll(List.of(damage.getIncidentId(), broken.getIncidentId())));

        // The incident report has no reporter filter, so assertions on the date
        // window are scoped to this test's own asset to stay deterministic.
        assertEquals(List.of(damage.getIncidentId()),
                forProperty(reportService.getIncidentsReport("DAMAGE", null, null, null), property));

        assertEquals(List.of(broken.getIncidentId()),
                forProperty(reportService.getIncidentsReport(null, "APPROVED", null, null), property));

        LocalDate epochStart = LocalDate.of(2000, 1, 1);
        assertEquals(2, forProperty(reportService.getIncidentsReport(
                null, null, epochStart, LocalDate.of(2100, 1, 1)), property).size(),
                "A range spanning today must keep both incidents");

        assertTrue(forProperty(reportService.getIncidentsReport(
                null, null, epochStart, LocalDate.of(2000, 1, 2)), property).isEmpty(),
                "A range long in the past must exclude today's incidents");
    }

    @Test
    void assignmentsReport_executesBothFilterBranchesAndSeparatesReturnedAssets() {
        AppUser holder = newUser();
        PropertyAssignment outstanding = newAssignment(newProperty("ASSIGNED"), holder);
        PropertyAssignment returned = newAssignment(newProperty("ASSIGNED"), holder);
        newReturn(returned, holder);

        List<Long> all = idsOf(
                reportService.getAssignmentsReport(holder.getUserId(), null, false));
        assertTrue(all.containsAll(List.of(outstanding.getAssignmentId(), returned.getAssignmentId())),
                "activeOnly = false must also reach assignments that have been returned");

        assertEquals(List.of(outstanding.getAssignmentId()), idsOf(
                reportService.getAssignmentsReport(holder.getUserId(), null, true)),
                "activeOnly = true must exclude assignments that have been returned");

        assertTrue(reportService.getAssignmentsReport(
                        holder.getUserId(), -1L, false).isEmpty(),
                "An area that holds nothing must return no rows");
    }

    @Test
    void assetRegisterReport_appliesStatusTypeCategoryAndAreaFilters() {
        Property property = newProperty("LOST");
        Long typeId = property.getPropertyType().getPropertyTypeId();
        Long categoryId = property.getPropertyType().getCategory().getCategoryId();
        Long areaId = property.getArea().getAreaId();

        List<Property> lost = reportService.getAssetRegisterReport("LOST", null, null, null);
        assertTrue(lost.stream().anyMatch(p -> p.getPropertyId().equals(property.getPropertyId())));
        assertTrue(lost.stream().allMatch(p -> "LOST".equals(p.getStatus())),
                "The status filter must not let other assets through");

        assertTrue(reportService.getAssetRegisterReport(null, typeId, categoryId, areaId).stream()
                .anyMatch(p -> p.getPropertyId().equals(property.getPropertyId())));

        assertTrue(reportService.getAssetRegisterReport("LOST", -1L, null, null).isEmpty(),
                "A type that matches nothing must return no rows");

        // The register fetch-joins type, category and area; rows must stay unique.
        List<Long> ids = reportService.getAssetRegisterReport(null, null, null, null)
                .stream().map(Property::getPropertyId).toList();
        assertEquals(ids.size(), new java.util.HashSet<>(ids).size(),
                "No asset may appear twice in the register");

        // The joins the register template renders must be loaded eagerly.
        Property loaded = reportService.getAssetRegisterReport(null, null, null, null).get(0);
        assertNotNull(loaded.getPropertyType().getCategory().getName());
        assertNotNull(loaded.getArea().getName());
    }

    // ---------------------------------------------------------------- helpers

    /** Total assets currently attributed to a department by the grouped query. */
    private long heldByDepartment(String departmentName) {
        return assignmentRepository.countActiveAssetsByDepartment().stream()
                .filter(row -> departmentName.equals(row.getName()))
                .mapToLong(PropertyRepository.GroupedCount::getTotal)
                .sum();
    }

    private long sumOf(List<?> rows) {
        return rows.stream()
                .map(PropertyRepository.GroupedCount.class::cast)
                .mapToLong(PropertyRepository.GroupedCount::getTotal)
                .sum();
    }

    private Department firstDepartment() {
        List<Department> departments = departmentRepository.findAll();
        if (!departments.isEmpty()) {
            return departments.get(0);
        }

        // schema.sql seeds areas and categories but not departments.
        Department department = new Department();
        department.setName("TMP-DEPT-" + UUID.randomUUID().toString().substring(0, 8));
        department.setActive(true);
        return departmentRepository.saveAndFlush(department);
    }

    /** Incident ids belonging to one asset, so shared data cannot skew an assertion. */
    private List<Long> forProperty(List<PropertyIncident> incidents, Property property) {
        return incidents.stream()
                .filter(incident -> incident.getProperty().getPropertyId()
                        .equals(property.getPropertyId()))
                .map(PropertyIncident::getIncidentId)
                .toList();
    }

    private PropertyType anyActiveType() {
        List<PropertyType> types = propertyTypeRepository.findAllByActiveTrueOrderByNameAsc();
        if (!types.isEmpty()) {
            return types.get(0);
        }

        PropertyCategory category = propertyCategoryRepository
                .findAllByActiveTrueOrderByNameAsc().stream().findFirst()
                .orElseGet(this::newCategory);

        PropertyType type = new PropertyType();
        type.setCategory(category);
        type.setName("IT-" + UUID.randomUUID().toString().substring(0, 8));
        type.setUsageType("COMMON");
        return propertyTypeRepository.saveAndFlush(type);
    }

    private PropertyCategory newCategory() {
        PropertyCategory category = new PropertyCategory();
        category.setName("CAT-" + UUID.randomUUID().toString().substring(0, 8));
        return propertyCategoryRepository.saveAndFlush(category);
    }

    private PhysicalArea anyActiveArea() {
        List<PhysicalArea> areas = physicalAreaRepository.findAllByActiveTrueOrderByNameAsc();
        assertTrue(!areas.isEmpty(), "schema.sql must seed at least one physical area");
        return areas.get(0);
    }

    private Property newProperty(String status) {
        return newProperty(status, anyActiveType(), anyActiveArea());
    }

    private Property newProperty(String status, PropertyType type, PhysicalArea area) {
        Property property = new Property();
        property.setPropertyCode("TMP-" + UUID.randomUUID());
        property.setPropertyType(type);
        property.setArea(area);
        property.setPurchaseDate(LocalDate.of(2024, 1, 1));
        property.setCondition("GOOD");
        property.setStatus(status);
        return propertyRepository.saveAndFlush(property);
    }

    private AppUser newUser() {
        return newUser(firstDepartment());
    }

    private AppUser newUser(Department department) {
        String token = UUID.randomUUID().toString().substring(0, 10);
        AppUser user = new AppUser();
        user.setEmployeeCode("TMP-" + token);
        user.setFirstName("Temp");
        user.setLastName("Holder");
        user.setEmail("temp-" + token + "@example.com");
        user.setPasswordHash("not-a-real-hash");
        user.setDepartmentId(department.getDepartmentId());
        user.setActive(true);
        return appUserRepository.saveAndFlush(user);
    }

    private PropertyAssignment newAssignment(Property property, AppUser holder) {
        PropertyAssignment assignment = new PropertyAssignment();
        assignment.setProperty(property);
        assignment.setEmployee(holder);
        assignment.setIssuedBy(holder);
        assignment.setArea(property.getArea());
        assignment.setConditionAtIssue("GOOD");
        return assignmentRepository.saveAndFlush(assignment);
    }

    private PropertyStatusHistory newHistory(Property property, String oldStatus, String newStatus) {
        PropertyStatusHistory history = new PropertyStatusHistory();
        history.setProperty(property);
        history.setOldStatus(oldStatus);
        history.setNewStatus(newStatus);
        history.setChangedBy(newUser());
        return statusHistoryRepository.saveAndFlush(history);
    }

    private PropertyRequest newRequest(AppUser requester, String status, PropertyType... types) {
        PropertyRequest request = new PropertyRequest();
        request.setRequester(requester);
        request.setStatus(status);
        request.setNotes("Report test request");
        PropertyRequest saved = requestRepository.saveAndFlush(request);

        for (PropertyType type : types) {
            PropertyRequestItem item = new PropertyRequestItem();
            item.setRequest(saved);
            item.setPropertyType(type);
            item.setQuantity(1);
            saved.getItems().add(item);
        }
        return requestRepository.saveAndFlush(saved);
    }

    private PropertyIncident newIncident(
            Property property, AppUser reporter, String incidentType, String status) {
        PropertyIncident incident = new PropertyIncident();
        incident.setProperty(property);
        incident.setReportedBy(reporter);
        incident.setIncidentType(incidentType);
        incident.setStatus(status);
        incident.setDescription("Report test incident");
        return incidentRepository.saveAndFlush(incident);
    }

    private PropertyReturn newReturn(PropertyAssignment assignment, AppUser staff) {
        PropertyReturn propertyReturn = new PropertyReturn();
        propertyReturn.setAssignment(assignment);
        propertyReturn.setReturnedBy(staff);
        propertyReturn.setReceivedBy(staff);
        propertyReturn.setCondition("GOOD");
        return returnRepository.saveAndFlush(propertyReturn);
    }

    private List<Long> idsOf(List<PropertyAssignment> assignments) {
        return assignments.stream().map(PropertyAssignment::getAssignmentId).toList();
    }
}
