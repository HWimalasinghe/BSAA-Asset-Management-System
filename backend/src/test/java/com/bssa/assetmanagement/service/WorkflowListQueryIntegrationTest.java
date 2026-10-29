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
import com.bssa.assetmanagement.entity.PropertyType;
import com.bssa.assetmanagement.entity.Role;
import com.bssa.assetmanagement.repository.AppUserRepository;
import com.bssa.assetmanagement.repository.DepartmentRepository;
import com.bssa.assetmanagement.repository.PhysicalAreaRepository;
import com.bssa.assetmanagement.repository.PropertyAssignmentRepository;
import com.bssa.assetmanagement.repository.PropertyCategoryRepository;
import com.bssa.assetmanagement.repository.PropertyIncidentRepository;
import com.bssa.assetmanagement.repository.PropertyRepository;
import com.bssa.assetmanagement.repository.PropertyRequestRepository;
import com.bssa.assetmanagement.repository.PropertyReturnRepository;
import com.bssa.assetmanagement.repository.PropertyTypeRepository;
import com.bssa.assetmanagement.repository.RoleRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the paged workflow queries against the real database.
 *
 * Mocks cannot show whether the JPQL works: the joins behind asset, holder and
 * issuer, the sub-selects that decide whether an asset is still held, the
 * separate count queries, the identifier-first paging of the account list, and
 * the fetch plans that let a template read an association after the session has
 * closed are only proven by running them.
 *
 * Every assertion is about rows this test seeds, and searches are made on values
 * this test invents, so data already in the development database cannot change
 * the outcome.
 */
@SpringBootTest
@Transactional
class WorkflowListQueryIntegrationTest {

    @Autowired
    private PropertyAssignmentService assignmentService;

    @Autowired
    private PropertyReturnService returnService;

    @Autowired
    private PropertyRequestService requestService;

    @Autowired
    private PropertyIncidentService incidentService;

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private DepartmentRepository departmentRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private PropertyRepository propertyRepository;

    @Autowired
    private PropertyTypeRepository propertyTypeRepository;

    @Autowired
    private PropertyCategoryRepository propertyCategoryRepository;

    @Autowired
    private PhysicalAreaRepository physicalAreaRepository;

    @Autowired
    private PropertyAssignmentRepository assignmentRepository;

    @Autowired
    private PropertyReturnRepository returnRepository;

    @Autowired
    private PropertyRequestRepository requestRepository;

    @Autowired
    private PropertyIncidentRepository incidentRepository;

    @Test
    void assignmentLogSearchMatchesAssetHolderAndIssuer() {
        PhysicalArea area = anyActiveArea();
        AppUser holder = newUser("HOLDER");
        AppUser issuer = newUser("ISSUER");
        Property asset = newAsset(anyActiveType(), area);

        assignmentRepository.saveAndFlush(newAssignment(asset, holder, issuer, area, "GOOD"));

        assertTrue(assignmentCodes(asset.getPropertyCode()).contains(asset.getPropertyCode()),
                "an asset code is searchable in the assignment log");
        assertTrue(assignmentCodes(holder.getLastName().toLowerCase()).contains(asset.getPropertyCode()),
                "the holder's name is searchable");
        assertTrue(assignmentCodes(issuer.getFirstName().toLowerCase()).contains(asset.getPropertyCode()),
                "the issuing officer's name is searchable");
        assertTrue(assignmentCodes("nothing-matches-" + UUID.randomUUID()).isEmpty(),
                "an unrelated term must find nothing");
        assertEquals(assignmentCodes(null).size(), assignmentCodes("   ").size(),
                "a blank search box must not narrow the log");
    }

    @Test
    void assignmentAreaAndHoldingFiltersCombineWithTheSearch() {
        PhysicalArea area = anyActiveArea();
        PhysicalArea otherArea = anyOtherActiveArea();
        PropertyType type = newType(newCategory());
        AppUser holder = newUser("HOLDER");
        AppUser issuer = newUser("ISSUER");

        Property heldAsset = newAsset(type, area);
        Property returnedAsset = newAsset(type, area);
        Property elsewhereAsset = newAsset(type, otherArea);

        assignmentRepository.saveAndFlush(newAssignment(heldAsset, holder, issuer, area, "GOOD"));
        PropertyAssignment returned = assignmentRepository.saveAndFlush(
                newAssignment(returnedAsset, holder, issuer, area, "GOOD"));
        assignmentRepository.saveAndFlush(newAssignment(elsewhereAsset, holder, issuer, otherArea, "GOOD"));

        newReturn(returned, holder, issuer, "DAMAGED");

        assertEquals(List.of(heldAsset.getPropertyCode()),
                codes(assignmentService.searchAssignments(
                        type.getName(), area.getAreaId(), "HELD", 0, 25, null, null)),
                "only an asset still held in this area satisfies both filters");

        assertEquals(List.of(returnedAsset.getPropertyCode()),
                codes(assignmentService.searchAssignments(
                        type.getName(), area.getAreaId(), "RETURNED", 0, 25, null, null)),
                "the holding filter must find the assignment a return points at");

        assertEquals(List.of(heldAsset.getPropertyCode()),
                codes(assignmentService.searchAssignments(
                        heldAsset.getPropertyCode(), area.getAreaId(), "HELD", 0, 25, null, null)),
                "the search and the filters have to hold at once");

        assertTrue(codes(assignmentService.searchAssignments(
                        heldAsset.getPropertyCode(), otherArea.getAreaId(), null, 0, 25, null, null)).isEmpty(),
                "an area filter must exclude an otherwise matching asset");
    }

    @Test
    void assignmentTypedWildcardIsMatchedAsLiteralText() {
        PhysicalArea area = anyActiveArea();
        Property asset = newAsset(anyActiveType(), area);
        AppUser holder = newUser("HOLDER");
        holder.setLastName(asset.getPropertyCode() + "%");
        appUserRepository.saveAndFlush(holder);

        assignmentRepository.saveAndFlush(
                newAssignment(asset, holder, newUser("ISSUER"), area, "GOOD"));

        List<String> matches = assignmentCodes(asset.getPropertyCode() + "%");

        assertTrue(matches.contains(asset.getPropertyCode()),
                "a percent sign typed into the box must match itself");
        assertEquals(1, matches.size(),
                "'%' must not become a wildcard that matches every assignment in the log");
    }

    @Test
    void assignmentPagingWalksEveryRowOnceInTheRequestedOrder() {
        PhysicalArea area = anyActiveArea();
        PropertyType type = newType(newCategory());
        AppUser holder = newUser("HOLDER");
        AppUser issuer = newUser("ISSUER");

        List<String> seeded = new ArrayList<>();
        // Twelve rows so that the offered page size of 10 spans two pages.
        for (int i = 0; i < 12; i++) {
            Property asset = newAsset(type, area);
            assignmentRepository.saveAndFlush(newAssignment(asset, holder, issuer, area, "GOOD"));
            seeded.add(asset.getPropertyCode());
        }

        Page<PropertyAssignment> first = assignmentService.searchAssignments(
                type.getName(), null, null, 0, 10, "code", "asc");

        assertEquals(12, first.getTotalElements(), "the count query must cover the filtered set");
        assertEquals(2, first.getTotalPages());
        assertEquals(10, first.getContent().size(), "only one page of rows is fetched");

        List<String> walked = new ArrayList<>();
        for (int page = 0; page < first.getTotalPages(); page++) {
            walked.addAll(codes(assignmentService.searchAssignments(
                    type.getName(), null, null, page, 10, "code", "asc")));
        }

        assertTrue(walked.containsAll(seeded), "every matching row must appear on some page");
        assertEquals(new java.util.LinkedHashSet<>(walked).size(), walked.size(),
                "no row may appear on two pages");
        assertEquals(sortedCopy(codes(assignmentService.searchAssignments(
                        type.getName(), null, null, 0, 100, "code", "asc"))), sortedCopy(walked),
                "walking small pages must cover exactly the rows the single large page sees");
    }

    @Test
    void assignmentOrderIsStableWhenRowsShareASortValue() {
        PhysicalArea area = anyActiveArea();
        AppUser holder = newUser("HOLDER");
        AppUser issuer = newUser("ISSUER");

        for (int i = 0; i < 12; i++) {
            assignmentRepository.saveAndFlush(
                    newAssignment(newAsset(anyActiveType(), area), holder, issuer, area, "GOOD"));
        }

        // Every row shares a holder, so the primary sort ties every time and only
        // the tiebreaker can keep two pages consistent.
        List<Long> acrossSmallPages = new ArrayList<>();
        for (int page = 0; page < 2; page++) {
            assignmentService.searchAssignments(holder.getLastName(), null, null, page, 10, "holder", "asc")
                    .getContent().forEach(row -> acrossSmallPages.add(row.getAssignmentId()));
        }
        List<Long> inOneLargePage = new ArrayList<>();
        assignmentService.searchAssignments(holder.getLastName(), null, null, 0, 100, "holder", "asc")
                .getContent().forEach(row -> inOneLargePage.add(row.getAssignmentId()));

        assertEquals(12, inOneLargePage.size(), "the fixture should have produced rows");
        assertEquals(inOneLargePage, acrossSmallPages,
                "small pages must be read in the same order as the whole set");
    }

    @Test
    void assignmentUnknownSortAndPageSizeAreNormalisedBeforeTheQuery() {
        Page<PropertyAssignment> page = assignmentService.searchAssignments(
                null, null, null, -2, 5_000, "(select assignment_id)", "sideways");

        assertEquals(0, page.getNumber(), "a negative page must not become a negative offset");
        assertEquals(PagingSupport.DEFAULT_PAGE_SIZE, page.getSize(),
                "an unoffered page size must not become the query limit");
        assertEquals("assignmentId", page.getSort().iterator().next().getProperty(),
                "a hand-crafted sort key must fall back to the default order");

        assertThrows(IllegalArgumentException.class, () -> assignmentService.searchAssignments(
                null, null, "NOT_A_HOLDING", 0, 25, null, null));
    }

    @Test
    void returnLogSearchMatchesAssetHolderAndReceiverAndFiltersCondition() {
        PhysicalArea area = anyActiveArea();
        AppUser holder = newUser("HOLDER");
        AppUser receiver = newUser("RECEIVER");
        Property asset = newAsset(anyActiveType(), area);

        PropertyAssignment assignment = assignmentRepository.saveAndFlush(
                newAssignment(asset, holder, receiver, area, "GOOD"));
        PropertyReturn recorded = newReturn(assignment, holder, receiver, "DAMAGED");

        assertTrue(returnIds(asset.getPropertyCode(), null).contains(recorded.getReturnId()),
                "the returned asset's code is searchable");
        assertTrue(returnIds(holder.getLastName().toLowerCase(), null).contains(recorded.getReturnId()),
                "the holder who handed it back is searchable");
        assertTrue(returnIds(receiver.getFirstName().toLowerCase(), null).contains(recorded.getReturnId()),
                "the officer who received it is searchable");

        assertEquals(List.of(recorded.getReturnId()), returnIds(asset.getPropertyCode(), "DAMAGED"),
                "the condition an asset came back in is filterable");
        assertTrue(returnIds(asset.getPropertyCode(), "GOOD").isEmpty(),
                "a return recorded as DAMAGED must not appear under a GOOD filter");

        assertThrows(IllegalArgumentException.class, () -> returnService.searchReturns(
                null, "SYSDBA", 0, 25, null, null));
    }

    @Test
    void accountPageFiltersBySearchDepartmentAndStatus() {
        Department first = newDepartment();
        Department second = newDepartment();

        String sharedSurname = "SAME" + UUID.randomUUID().toString().replace("-", "");
        AppUser match = newUser("MATCH", first, sharedSurname);
        AppUser sibling = newUser("SIBLING", second, sharedSurname);
        AppUser dormant = newUser("DORMANT", first, sharedSurname);
        dormant.setActive(false);
        appUserRepository.saveAndFlush(dormant);

        Sort byCode = Sort.by(Sort.Direction.ASC, "employeeCode")
                .and(Sort.by(Sort.Direction.ASC, "userId"));
        Pageable pageable = PageRequest.of(0, 25, byCode);

        assertEquals(sortedCopy(List.of(match.getEmployeeCode(), dormant.getEmployeeCode())),
                load(pageable, sharedSurname, first.getDepartmentId(), null),
                "one department's page holds both its accounts and none of the other's");
        assertEquals(List.of(sibling.getEmployeeCode()),
                load(pageable, sharedSurname, second.getDepartmentId(), null),
                "an account in another department is not listed for this one's filter");
        assertEquals(List.of(match.getEmployeeCode()),
                load(pageable, sharedSurname, first.getDepartmentId(), "ACTIVE"),
                "ACTIVE must not include the deactivated account");
        assertEquals(List.of(dormant.getEmployeeCode()),
                load(pageable, sharedSurname, first.getDepartmentId(), "INACTIVE"),
                "INACTIVE must find exactly the deactivated accounts");

        Page<Long> ids = appUserRepository.findAccountIdPage(
                sharedSurname.toLowerCase(),
                PagingSupport.likePattern(sharedSurname.toLowerCase()),
                null, null, PageRequest.of(0, 1, byCode));
        assertEquals(3, ids.getTotalElements(), "the count query must cover the whole filtered set");
        assertEquals(1, ids.getContent().size(), "only one identifier of the page is fetched");

        assertEquals(List.of(), load(pageable, "nobody-" + UUID.randomUUID(), null, null),
                "an unrelated term must find no account at all");
    }

    @Test
    void requestHistoryPageIsNewestFirstAndArrivesWithItsItems() {
        AppUser requester = newUser("REQUESTER");
        PropertyType type = newType(newCategory());

        long before = requestService.getAllRequestsPage(0, 10).getTotalElements();

        List<Long> seeded = new ArrayList<>();
        // Twelve requests, so the smallest page size the list offers spans two
        // pages and a page can be told apart from the whole history.
        for (int i = 0; i < 12; i++) {
            seeded.add(requestRepository.saveAndFlush(newRequest(requester, type)).getRequestId());
        }

        Page<PropertyRequest> firstPage = requestService.getAllRequestsPage(0, 10);
        assertEquals(before + 12, firstPage.getTotalElements(),
                "the count query must cover every request, not only this page");
        assertEquals(10, firstPage.getContent().size(), "only the requested page is read");

        List<Long> whole = idsOf(requestService.getAllRequestsPage(0, 100));
        assertTrue(whole.containsAll(seeded), "every seeded request must be listed");
        assertEquals(reversed(seeded),
                whole.stream().filter(seeded::contains).toList(),
                "the history must be newest first");

        int walkPages = Math.min(firstPage.getTotalPages(), 2);
        List<Long> walked = walkedRequestIds(walkPages);
        assertEquals(walked.size(), new java.util.LinkedHashSet<>(walked).size(),
                "no request may appear on two pages");
        assertEquals(whole.subList(0, Math.min(walkPages * 10, whole.size())), walked,
                "walking small pages must reproduce the order of the whole history");

        // The template reads each request's items after the session has closed, so
        // the fetch plan has to have loaded them with the page.
        firstPage.getContent().forEach(request -> {
            assertFalse(request.getItems().isEmpty(),
                    "a paged request must arrive with its items already fetched");
            assertNotNull(request.getRequester().getFirstName());
        });
    }

    @Test
    void incidentRegisterPageIsNewestFirstAndArrivesWithItsRelations() {
        AppUser reporter = newUser("REPORTER");
        AppUser reviewer = newUser("REVIEWER");
        Property asset = newAsset(anyActiveType(), anyActiveArea());

        List<Long> seeded = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            seeded.add(incidentRepository.saveAndFlush(
                    newIncident(asset, reporter, reviewer)).getIncidentId());
        }

        Page<PropertyIncident> firstPage = incidentService.getAllIncidentsPage(0, 10);
        assertEquals(10, firstPage.getContent().size(), "only one page of incidents is read");

        List<Long> whole = incidentIds(incidentService.getAllIncidentsPage(0, 100));
        assertTrue(whole.containsAll(seeded), "every seeded incident must be listed");
        assertEquals(reversed(seeded), whole.stream().filter(seeded::contains).toList(),
                "the incident register must be newest first");

        int walkPages = Math.min(firstPage.getTotalPages(), 2);
        List<Long> walked = walkedIncidentIds(walkPages);
        assertEquals(walked.size(), new java.util.LinkedHashSet<>(walked).size(),
                "no incident may appear on two pages");
        assertEquals(whole.subList(0, Math.min(walkPages * 10, whole.size())), walked,
                "walking small pages must reproduce the order of the whole register");

        PropertyIncident incident = firstPage.getContent().get(0);
        // The register reads these fields while rendering, with the session already
        // closed, so the fetch plan has to have brought them with the rows.
        assertNotNull(incident.getProperty().getPropertyCode());
        assertNotNull(incident.getReportedBy().getFirstName());
        assertNotNull(incident.getProperty().getArea().getName());
    }

    /** Runs the account list the way the controller does: identifiers first, then the rows. */
    private List<String> load(Pageable pageable, String search, Long departmentId, String status) {
        String term = PagingSupport.normalizedSearch(search);
        Page<Long> page = appUserRepository.findAccountIdPage(
                term, term == null ? null : PagingSupport.likePattern(term),
                departmentId, status, pageable);

        if (!page.hasContent()) {
            return List.of();
        }
        return appUserRepository
                .findByUserIdIn(page.getContent(), Pageable.unpaged(pageable.getSort()))
                .stream()
                .map(AppUser::getEmployeeCode)
                .toList();
    }

    private List<Long> walkedRequestIds(int pages) {
        List<Long> walked = new ArrayList<>();
        for (int page = 0; page < pages; page++) {
            walked.addAll(idsOf(requestService.getAllRequestsPage(page, 10)));
        }
        return walked;
    }

    private List<Long> walkedIncidentIds(int pages) {
        List<Long> walked = new ArrayList<>();
        for (int page = 0; page < pages; page++) {
            walked.addAll(incidentIds(incidentService.getAllIncidentsPage(page, 10)));
        }
        return walked;
    }

    private List<Long> incidentIds(Page<PropertyIncident> page) {
        return page.getContent().stream().map(PropertyIncident::getIncidentId).toList();
    }

    private List<String> assignmentCodes(String search) {
        return codes(assignmentService.searchAssignments(search, null, null, 0, 100, null, null));
    }

    private List<String> codes(Page<PropertyAssignment> page) {
        List<String> codes = new ArrayList<>();
        page.getContent().forEach(assignment -> codes.add(assignment.getProperty().getPropertyCode()));
        return codes;
    }

    private List<Long> returnIds(String search, String condition) {
        return ids(returnService.searchReturns(search, condition, 0, 100, null, null));
    }

    private List<Long> ids(Page<PropertyReturn> page) {
        return page.getContent().stream().map(PropertyReturn::getReturnId).toList();
    }

    private List<Long> idsOf(Page<PropertyRequest> requests) {
        return requests.getContent().stream().map(PropertyRequest::getRequestId).toList();
    }

    private List<Long> reversed(List<Long> values) {
        List<Long> copy = new ArrayList<>(values);
        java.util.Collections.reverse(copy);
        return copy;
    }

    private List<String> sortedCopy(List<String> values) {
        List<String> copy = new ArrayList<>(values);
        copy.sort(String::compareTo);
        return copy;
    }

    private PropertyAssignment newAssignment(
            Property asset, AppUser holder, AppUser issuer, PhysicalArea area, String condition) {

        PropertyAssignment assignment = new PropertyAssignment();
        assignment.setProperty(asset);
        assignment.setEmployee(holder);
        assignment.setIssuedBy(issuer);
        assignment.setArea(area);
        assignment.setConditionAtIssue(condition);
        return assignment;
    }

    private PropertyReturn newReturn(
            PropertyAssignment assignment, AppUser returnedBy, AppUser receivedBy, String condition) {

        PropertyReturn recorded = new PropertyReturn();
        recorded.setAssignment(assignment);
        recorded.setReturnedBy(returnedBy);
        recorded.setReceivedBy(receivedBy);
        recorded.setCondition(condition);
        return returnRepository.saveAndFlush(recorded);
    }

    private PropertyRequest newRequest(AppUser requester, PropertyType type) {
        PropertyRequest request = new PropertyRequest();
        request.setRequester(requester);
        request.setStatus("PENDING");
        request.setNotes("Need one unit for field work");

        PropertyRequestItem item = new PropertyRequestItem();
        item.setPropertyType(type);
        item.setQuantity(1);
        request.addItem(item);

        return request;
    }

    private PropertyIncident newIncident(Property asset, AppUser reporter, AppUser reviewer) {
        PropertyIncident incident = new PropertyIncident();
        incident.setProperty(asset);
        incident.setReportedBy(reporter);
        incident.setReviewedBy(reviewer);
        incident.setIncidentType("DAMAGE");
        incident.setDescription("Screen cracked during transport");
        incident.setStatus("OPEN");
        return incident;
    }

    private AppUser newUser(String tag) {
        return newUser(tag, newDepartment(), uniqueSurname(tag));
    }

    private AppUser newUser(String tag, Department department, String lastName) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        AppUser user = new AppUser();
        user.setEmployeeCode(tag + "-" + suffix);
        user.setFirstName(tag + "First" + suffix);
        user.setLastName(lastName);
        user.setEmail(tag.toLowerCase() + suffix + "@example.com");
        user.setPasswordHash("not-a-real-credential");
        user.setDepartmentId(department.getDepartmentId());
        user.setPosition("Test Officer");
        user.setActive(true);
        user.setRoles(Set.of(anyRole()));
        return appUserRepository.saveAndFlush(user);
    }

    /** A surname this test alone owns, so a search can only ever find these rows. */
    private String uniqueSurname(String tag) {
        return tag + UUID.randomUUID().toString().replace("-", "");
    }

    private Department newDepartment() {
        Department department = new Department();
        department.setName("WF-DEPT-" + UUID.randomUUID().toString().substring(0, 8));
        department.setActive(true);
        return departmentRepository.saveAndFlush(department);
    }

    private Role anyRole() {
        List<Role> roles = roleRepository.findAll();
        assertFalse(roles.isEmpty(), "schema.sql must seed the application roles");
        return roles.get(0);
    }

    private Property newAsset(PropertyType type, PhysicalArea area) {
        Property property = new Property();
        property.setPropertyCode("WF-" + UUID.randomUUID());
        property.setPropertyType(type);
        property.setArea(area);
        property.setCondition("GOOD");
        property.setStatus("ASSIGNED");
        property.setPurchaseDate(LocalDate.of(2024, 1, 1));
        return propertyRepository.saveAndFlush(property);
    }

    private PropertyType newType(PropertyCategory category) {
        PropertyType type = new PropertyType();
        type.setCategory(category);
        type.setName("WFTYPE-" + UUID.randomUUID().toString().substring(0, 10));
        type.setUsageType("COMMON");
        return propertyTypeRepository.saveAndFlush(type);
    }

    private PropertyCategory newCategory() {
        PropertyCategory category = new PropertyCategory();
        category.setName("WFCAT-" + UUID.randomUUID().toString().substring(0, 10));
        category.setActive(true);
        return propertyCategoryRepository.saveAndFlush(category);
    }

    private PropertyType anyActiveType() {
        List<PropertyType> types = propertyTypeRepository.findAllByActiveTrueOrderByNameAsc();
        return types.isEmpty() ? newType(newCategory()) : types.get(0);
    }

    private PhysicalArea anyActiveArea() {
        List<PhysicalArea> areas = physicalAreaRepository.findAllByActiveTrueOrderByNameAsc();
        assertFalse(areas.isEmpty(), "schema.sql must seed at least one physical area");
        return areas.get(0);
    }

    private PhysicalArea anyOtherActiveArea() {
        List<PhysicalArea> areas = physicalAreaRepository.findAllByActiveTrueOrderByNameAsc();
        assertTrue(areas.size() > 1, "schema.sql must seed more than one physical area");
        return areas.get(1);
    }
}
