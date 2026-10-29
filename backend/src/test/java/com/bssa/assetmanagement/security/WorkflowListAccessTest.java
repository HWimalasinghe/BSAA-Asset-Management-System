package com.bssa.assetmanagement.security;

import com.bssa.assetmanagement.entity.AppUser;
import com.bssa.assetmanagement.entity.Notification;
import com.bssa.assetmanagement.entity.PhysicalArea;
import com.bssa.assetmanagement.entity.Property;
import com.bssa.assetmanagement.entity.PropertyAssignment;
import com.bssa.assetmanagement.entity.PropertyIncident;
import com.bssa.assetmanagement.entity.PropertyRequest;
import com.bssa.assetmanagement.entity.PropertyType;
import com.bssa.assetmanagement.entity.Role;
import com.bssa.assetmanagement.repository.AppUserRepository;
import com.bssa.assetmanagement.repository.DepartmentRepository;
import com.bssa.assetmanagement.repository.PhysicalAreaRepository;
import com.bssa.assetmanagement.service.NotificationService;
import com.bssa.assetmanagement.service.PropertyAssignmentService;
import com.bssa.assetmanagement.service.PropertyIncidentService;
import com.bssa.assetmanagement.service.PropertyRequestService;
import com.bssa.assetmanagement.service.PropertyReturnService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
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
 * Drives the paged workflow lists - the assignment log, the return log, the
 * account list, the request and incident registers and the inbox - through the
 * real Spring Security chain and the real templates.
 *
 * The unit tests show what a controller does with its parameters. These show
 * what a browser or a hand-written address bar can actually get back: a role
 * without rights to a log is refused before its rows are read, a page that is
 * only visible to reviewers never reveals its totals to anybody else, and no
 * unknown sort key, page size, filter value or markup in a search term produces
 * anything other than the safe rendering.
 */
@SpringBootTest
class WorkflowListAccessTest {

    /** Every hyperlink the rendered page offers, with the HTML escaping undone. */
    private static final Pattern HREF = Pattern.compile("href=\"([^\"]*)\"");

    @Autowired
    private WebApplicationContext context;

    @MockitoBean
    private PropertyAssignmentService assignmentService;

    @MockitoBean
    private PropertyReturnService returnService;

    @MockitoBean
    private PropertyRequestService requestService;

    @MockitoBean
    private PropertyIncidentService incidentService;

    @MockitoBean
    private NotificationService notificationService;

    @MockitoBean
    private AppUserRepository appUserRepository;

    @MockitoBean
    private DepartmentRepository departmentRepository;

    @MockitoBean
    private PhysicalAreaRepository physicalAreaRepository;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();

        when(physicalAreaRepository.findAllByActiveTrueOrderByNameAsc()).thenReturn(List.of());
        when(departmentRepository.findAllByOrderByNameAsc()).thenReturn(List.of());

        // Every controller checks the account behind the session before it reads
        // anything. Nobody in these tests is stored in the database, so an
        // unknown account is treated as active by that check.
        when(appUserRepository.findByEmail(any())).thenReturn(Optional.empty());

        // The account list is driven by the two-query pattern, so both halves of
        // it are stubbed: the page of identifiers and the accounts behind them.
        when(appUserRepository.findAccountIdPage(any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(7L), PageRequest.of(0, 25), 1));
        when(appUserRepository.findByUserIdIn(any(), any()))
                .thenReturn(List.of(account(7L, "INVENTORY_ADMIN")));

        when(requestService.getMyRequests(any())).thenReturn(List.of());
        when(requestService.getPendingRequests()).thenReturn(List.of());
        when(incidentService.getMyIncidents(any())).thenReturn(List.of());
        when(incidentService.getOpenIncidents()).thenReturn(List.of());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/assignments", "/returns"})
    @WithMockUser(username = "staff@example.com", roles = {"OFFICE_STAFF"})
    void officeStaffIsRefusedBothLogs(String url) throws Exception {
        mockMvc.perform(get(url))
                .andExpect(status().isForbidden())
                .andExpect(content().string(not(containsString("LAP-0001"))));

        verifyNoInteractions(assignmentService, returnService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/assignments", "/returns", "/users", "/notifications"})
    void anonymousCallersAreSentToLogin(String url) throws Exception {
        mockMvc.perform(get(url))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));

        verifyNoInteractions(assignmentService, returnService, notificationService);
        verify(appUserRepository, never()).findAccountIdPage(any(), any(), any(), any(), any());
    }

    @Test
    @WithMockUser(username = "manager@example.com", roles = {"INVENTORY_MANAGER"})
    void theAssignmentLogKeepsItsFiltersInEverySortAndPageLink() throws Exception {
        when(assignmentService.searchAssignments(eq("laptop"), eq(3L), eq("HELD"), eq(1), eq(10),
                eq("code"), eq("desc")))
                .thenReturn(new PageImpl<>(List.of(assignment("LAP-0001")), PageRequest.of(1, 10), 60));

        MvcResult result = mockMvc.perform(get("/assignments")
                        .param("search", "laptop")
                        .param("areaId", "3")
                        .param("holding", "HELD")
                        .param("sort", "code")
                        .param("dir", "desc")
                        .param("size", "10")
                        .param("page", "1"))
                .andExpect(status().isOk())
                .andReturn();

        String html = result.getResponse().getContentAsString();

        assertTrue(html.contains("value=\"laptop\""), "the term must stay in its box");
        assertTrue(html.contains("LAP-0001"), "the row itself must be listed");
        assertTrue(html.contains("holding=HELD"), "paging must keep the active filter");
        assertTrue(html.contains("areaId=3"), html);
        assertTrue(html.contains("size=10"), "paging must keep the chosen page size");
        assertTrue(html.contains("search=laptop"), html);
        assertTrue(html.contains("dir=asc"), "clicking the sorted column again reverses it");
        assertTrue(html.contains("page=0"), "the previous-page link is offered");
        assertTrue(html.contains("page=2"), "the next-page link is offered");
        assertTrue(html.contains("Showing <strong>11</strong>"), html);
        assertTrue(html.contains("of <strong>60</strong>"), "the total comes from the count query");
        assertTrue(html.contains("value=\"HELD\" selected"), "the held-or-returned choice stays selected");

        // The sorting only works if a link states the order once. Sorting used to
        // be broken on every list because the shared query already carried a sort
        // and the column link added a second one, and a browser keeps the first.
        assertNoLinkRepeatsAParameter(html);
    }

    @Test
    @WithMockUser(username = "manager@example.com", roles = {"INVENTORY_MANAGER"})
    void anUnknownAssignmentFilterIsRefusedWithoutRepeatingIt() throws Exception {
        when(assignmentService.searchAssignments(any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalArgumentException("Unrecognised holding filter: SYSDBA"));

        mockMvc.perform(get("/assignments").param("holding", "SYSDBA"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("not one of the available options")))
                .andExpect(content().string(not(containsString("SYSDBA"))))
                // Nothing is listed, so no pager and no "showing" line either.
                .andExpect(content().string(not(containsString("Showing "))))
                .andExpect(content().string(not(containsString("LAP-0001"))));
    }

    @Test
    @WithMockUser(username = "manager@example.com", roles = {"INVENTORY_MANAGER"})
    void aHandCraftedAssignmentSortKeyNeverReachesTheQuery() throws Exception {
        when(assignmentService.searchAssignments(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 25), 0));

        mockMvc.perform(get("/assignments")
                        .param("sort", "passwordHash")
                        .param("dir", "sideways")
                        .param("size", "9999"))
                .andExpect(status().isOk());

        verify(assignmentService).searchAssignments(isNull(), isNull(), isNull(), isNull(),
                eq(25), eq("issued"), eq("asc"));
    }

    @Test
    @WithMockUser(username = "manager@example.com", roles = {"INVENTORY_MANAGER"})
    void aPageTypedPastTheEndShowsNoRowsAndNoBackwardsRange() throws Exception {
        when(assignmentService.searchAssignments(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(99, 10), 12));

        MvcResult result = mockMvc.perform(get("/assignments")
                        .param("search", "laptop")
                        .param("page", "99")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andReturn();

        String html = result.getResponse().getContentAsString();
        // The twelfth row cannot start at row 991, so the page says nothing about
        // rows and simply reports that this search has no matches.
        assertFalse(html.contains("Showing "), html);
        assertTrue(html.contains("No assignments match this search or these filters."), html);
    }

    @Test
    @WithMockUser(username = "manager@example.com", roles = {"INVENTORY_MANAGER"})
    void aMarkupBearingSearchTermIsRenderedAsTextInBothLogs() throws Exception {
        when(assignmentService.searchAssignments(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 25), 0));
        when(returnService.searchReturns(any(), any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 25), 0));

        for (String url : List.of("/assignments", "/returns")) {
            MvcResult result = mockMvc.perform(get(url).param("search", "<script>alert(1)</script>"))
                    .andExpect(status().isOk())
                    .andReturn();

            String html = result.getResponse().getContentAsString();
            assertTrue(html.contains("&lt;script&gt;alert(1)&lt;/script&gt;"),
                    url + " must escape the term where it is echoed back");
            assertTrue(html.contains("search=%3Cscript%3Ealert%281%29%3C%2Fscript%3E"),
                    url + " must carry the term URL-encoded in its links, not as markup");
            assertFalse(html.contains("<script>alert(1)</script>"),
                    url + " must never send the term back as live markup");
        }
    }

    @Test
    @WithMockUser(username = "admin@example.com", roles = {"SYSTEM_ADMIN"})
    void theAccountListPagesThroughIdentifiersAndKeepsTheOrderInTheLinks() throws Exception {
        MvcResult result = mockMvc.perform(get("/users")
                        .param("accountStatus", "ACTIVE")
                        .param("sort", "name")
                        .param("dir", "desc")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andReturn();

        String html = result.getResponse().getContentAsString();

        assertTrue(html.contains("1 account(s) matching"), html);
        assertTrue(html.contains("EMP-0007"), "the row behind the identifier is loaded");
        assertTrue(html.contains("accountStatus=ACTIVE"), "paging must keep the status filter");
        assertTrue(html.contains("size=10"), html);
        assertTrue(html.contains("sort=name"), "the pager must keep the order being browsed");
        assertTrue(html.contains("sort=code"), "the other columns must offer their own order");
        assertTrue(html.contains("/users/edit/7"),
                "the accounts the caller may manage are worked out from this page's rows");
        assertNoLinkRepeatsAParameter(html);
    }

    @Test
    @WithMockUser(username = "admin@example.com", roles = {"SYSTEM_ADMIN"})
    void anUnknownAccountStatusIsRefusedWithoutListingAnything() throws Exception {
        mockMvc.perform(get("/users").param("accountStatus", "SYSDBA"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("not one of the available options")))
                .andExpect(content().string(not(containsString("SYSDBA"))))
                .andExpect(content().string(not(containsString("EMP-0007"))));

        verify(appUserRepository, never()).findByUserIdIn(any(), any());
    }

    @Test
    @WithMockUser(username = "staff@example.com", roles = {"OFFICE_STAFF"})
    void aStaffMemberSeesNoRequestHistoryAndNoRowCounts() throws Exception {
        when(requestService.getMyRequests("staff@example.com"))
                .thenReturn(List.of(request(1L)));

        MvcResult result = mockMvc.perform(get("/requests"))
                .andExpect(status().isOk())
                .andReturn();

        String html = result.getResponse().getContentAsString();
        assertTrue(html.contains("My Requests"), "the caller's own queue is still shown");
        assertTrue(html.contains("REQ-1"), "the caller's own requests are listed");
        assertFalse(html.contains("Showing <strong>"),
                "the organization-wide pager must not appear for a caller who may not see that panel");
        assertFalse(html.contains("All Organizational Requests"), html);

        verify(requestService, never()).getAllRequestsPage(any(), any());
    }

    @Test
    @WithMockUser(username = "admin@example.com", roles = {"INVENTORY_ADMIN"})
    void theRequestHistoryIsPagedForAReviewerAndSizeIsValidated() throws Exception {
        // PageImpl rewrites a total smaller than the requested window, so these
        // fixture totals are chosen to survive the page sizes they are paired
        // with.
        when(requestService.getAllRequestsPage(isNull(), eq(25)))
                .thenReturn(new PageImpl<>(List.of(request(1L)), PageRequest.of(0, 25), 60));
        when(requestService.getAllRequestsPage(eq(1), eq(10)))
                .thenReturn(new PageImpl<>(List.of(request(1L)), PageRequest.of(1, 10), 30));

        // A page size the list does not offer is replaced by the default.
        MvcResult result = mockMvc.perform(get("/requests").param("size", "7"))
                .andExpect(status().isOk())
                .andReturn();

        String html = result.getResponse().getContentAsString();
        assertTrue(html.contains("All Organizational Requests"), html);
        assertTrue(html.contains("Showing <strong>1</strong>"), html);
        assertTrue(html.contains("of <strong>60</strong>"), html);
        assertTrue(html.contains("size=25"), html);
        verify(requestService).getAllRequestsPage(isNull(), eq(25));

        mockMvc.perform(get("/requests").param("size", "10").param("page", "1"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("page=0")));
        verify(requestService).getAllRequestsPage(eq(1), eq(10));
    }

    @Test
    @WithMockUser(username = "admin@example.com", roles = {"INVENTORY_ADMIN"})
    void theIncidentRegisterIsPagedForAReviewerOnly() throws Exception {
        when(incidentService.getAllIncidentsPage(any(), any()))
                .thenReturn(new PageImpl<>(List.of(incident()), PageRequest.of(0, 25), 40));

        mockMvc.perform(get("/incidents"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("All Organizational Incidents")))
                .andExpect(content().string(containsString("Showing <strong>1</strong>")))
                .andExpect(content().string(containsString("of <strong>40</strong>")))
                .andExpect(content().string(containsString("page=1")));

        verify(incidentService).getAllIncidentsPage(isNull(), eq(25));
    }

    @Test
    @WithMockUser(username = "staff@example.com", roles = {"OFFICE_STAFF"})
    void anIncidentReporterSeesOwnReportsButNotTheRegister() throws Exception {
        when(incidentService.getMyIncidents("staff@example.com"))
                .thenReturn(List.of(incident()));

        MvcResult result = mockMvc.perform(get("/incidents"))
                .andExpect(status().isOk())
                .andReturn();

        String html = result.getResponse().getContentAsString();
        assertTrue(html.contains("INC-9"), "the caller's own reports are listed");
        assertFalse(html.contains("All Organizational Incidents"),
                "the organization-wide register stays hidden");
        assertFalse(html.contains("of <strong>40</strong>"),
                "the register's row count must not reach a caller who may not see it");

        verify(incidentService, never()).getAllIncidentsPage(any(), any());
    }

    @Test
    @WithMockUser(username = "staff@example.com", roles = {"OFFICE_STAFF"})
    void theInboxPagesOnlyTheReadersOwnNotificationsAndTreatsAnUnknownTabAsAll() throws Exception {
        when(notificationService.getNotificationsForUserPage(
                eq("staff@example.com"), eq(false), isNull(), eq(10)))
                .thenReturn(new PageImpl<>(
                        List.of(notification("<b>bold</b> title")), PageRequest.of(0, 10), 25));

        MvcResult result = mockMvc.perform(get("/notifications")
                        .param("filter", "everything-in-the-world")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andReturn();

        String html = result.getResponse().getContentAsString();
        assertTrue(html.contains("&lt;b&gt;bold&lt;/b&gt; title"),
                "a stored title must be escaped where it is shown");
        assertTrue(html.contains("filter=all"), "an unknown tab value falls back to 'all'");
        assertTrue(html.contains("size=10"), "paging keeps the chosen page size");
        assertTrue(html.contains("page=1"), html);
        assertTrue(html.contains("of <strong>25</strong>"), html);

        verify(notificationService).getNotificationsForUserPage(
                "staff@example.com", false, null, 10);
    }

    @Test
    @WithMockUser(username = "staff@example.com", roles = {"OFFICE_STAFF"})
    void theUnreadOnlyTabIsTheOnlyOtherFilterTheInboxAccepts() throws Exception {
        when(notificationService.getNotificationsForUserPage(
                eq("staff@example.com"), eq(true), isNull(), eq(25)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 25), 0));

        mockMvc.perform(get("/notifications").param("filter", "UNREAD"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("value=\"unread\"")))
                .andExpect(content().string(containsString("You have no unread notifications.")));

        verify(notificationService).getNotificationsForUserPage(
                "staff@example.com", true, null, 25);
    }

    /**
     * Fails when any link on the page asks for the same parameter twice.
     *
     * Sorting, filtering and paging are each written into the address by a
     * different part of the page, so a duplicated name is silently answered with
     * the first value and the reader's click appears to do nothing.
     */
    private static void assertNoLinkRepeatsAParameter(String html) {
        List<String> links = new ArrayList<>();
        Matcher matcher = HREF.matcher(html);
        while (matcher.find()) {
            String href = matcher.group(1).replace("&amp;", "&");
            if (href.contains("?")) {
                links.add(href);
            }
        }

        assertFalse(links.isEmpty(), "the page was expected to offer filtered links");
        for (String href : links) {
            Set<String> names = new HashSet<>();
            for (String pair : href.substring(href.indexOf('?') + 1).split("&")) {
                if (pair.contains("=")) {
                    assertTrue(names.add(pair.substring(0, pair.indexOf('='))),
                            "repeated parameter in " + href);
                }
            }
        }
    }

    private AppUser account(Long userId, String... roleNames) {
        Set<Role> roles = new LinkedHashSet<>();
        for (String name : roleNames) {
            Role role = new Role();
            role.setName(name);
            roles.add(role);
        }

        AppUser user = new AppUser();
        user.setUserId(userId);
        user.setEmployeeCode("EMP-0007");
        user.setFirstName("Amandla");
        user.setLastName("Nkemelu");
        user.setEmail("holder@example.com");
        user.setPasswordHash("not-a-real-hash");
        user.setDepartmentId(1L);
        user.setActive(true);
        user.setRoles(roles);
        return user;
    }

    private PropertyAssignment assignment(String code) {
        PropertyType type = new PropertyType();
        type.setPropertyTypeId(2L);
        type.setName("ThinkPad T14");

        Property property = new Property();
        property.setPropertyId(1L);
        property.setPropertyCode(code);
        property.setPropertyType(type);

        PhysicalArea area = new PhysicalArea();
        area.setAreaId(3L);
        area.setName("Head Office - Floor 2");

        PropertyAssignment assignment = new PropertyAssignment();
        assignment.setAssignmentId(5L);
        assignment.setProperty(property);
        assignment.setEmployee(account(7L));
        assignment.setIssuedBy(account(8L, "INVENTORY_MANAGER"));
        assignment.setArea(area);
        assignment.setConditionAtIssue("GOOD");
        // issued_at is filled in by the database, so a row built in a test has to
        // say what the column would have said.
        ReflectionTestUtils.setField(assignment, "issuedAt", LocalDateTime.now());
        return assignment;
    }

    private PropertyRequest request(Long requestId) {
        PropertyRequest request = new PropertyRequest();
        request.setRequestId(requestId);
        request.setStatus("PENDING");
        request.setRequester(account(7L));
        return request;
    }

    private PropertyIncident incident() {
        PropertyType type = new PropertyType();
        type.setPropertyTypeId(2L);
        type.setName("ThinkPad T14");

        Property property = new Property();
        property.setPropertyId(1L);
        property.setPropertyCode("LAP-0001");
        property.setPropertyType(type);

        PropertyIncident incident = new PropertyIncident();
        incident.setIncidentId(9L);
        incident.setProperty(property);
        incident.setReportedBy(account(7L));
        incident.setIncidentType("DAMAGE");
        incident.setStatus("OPEN");
        return incident;
    }

    private Notification notification(String title) {
        Notification notification = new Notification(
                account(7L), title, "A laptop was issued.", "ISSUE", null, null);
        notification.setNotificationId(3L);
        return notification;
    }
}
