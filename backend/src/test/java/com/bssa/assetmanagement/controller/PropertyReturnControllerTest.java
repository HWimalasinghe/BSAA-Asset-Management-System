package com.bssa.assetmanagement.controller;

import com.bssa.assetmanagement.entity.PhysicalArea;
import com.bssa.assetmanagement.entity.Property;
import com.bssa.assetmanagement.entity.PropertyAssignment;
import com.bssa.assetmanagement.entity.PropertyReturn;
import com.bssa.assetmanagement.entity.AppUser;
import com.bssa.assetmanagement.entity.Role;
import com.bssa.assetmanagement.repository.PropertyAssignmentRepository;
import com.bssa.assetmanagement.repository.PropertyReturnRepository;
import com.bssa.assetmanagement.service.PropertyReturnService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PropertyReturnControllerTest {

    private final PropertyAssignmentRepository assignmentRepository = mock(PropertyAssignmentRepository.class);
    private final PropertyReturnRepository returnRepository = mock(PropertyReturnRepository.class);
    private final PropertyReturnService returnService = mock(PropertyReturnService.class);

    private final PropertyReturnController controller = new PropertyReturnController(
            assignmentRepository,
            returnRepository,
            returnService
    );

    private UsernamePasswordAuthenticationToken managerAuth;
    private UsernamePasswordAuthenticationToken staffAuth;
    private Property property;
    private PropertyAssignment assignment;

    @BeforeEach
    void setUp() {
        reset(assignmentRepository, returnRepository, returnService);

        managerAuth = new UsernamePasswordAuthenticationToken(
                "manager@example.com",
                "pwd",
                List.of(new SimpleGrantedAuthority("ROLE_INVENTORY_MANAGER"))
        );

        staffAuth = new UsernamePasswordAuthenticationToken(
                "staff@example.com",
                "pwd",
                List.of(new SimpleGrantedAuthority("ROLE_OFFICE_STAFF"))
        );

        Role staffRole = new Role();
        staffRole.setName("OFFICE_STAFF");

        AppUser employee = new AppUser();
        employee.setUserId(2L);
        employee.setEmail("employee@example.com");
        employee.setFirstName("John");
        employee.setLastName("Doe");
        employee.setRoles(Set.of(staffRole));
        employee.setActive(true);

        PhysicalArea area = new PhysicalArea();
        area.setAreaId(1L);
        area.setName("Main Store");

        property = new Property();
        property.setPropertyId(10L);
        property.setPropertyCode("P-001");
        property.setStatus("ASSIGNED");

        assignment = new PropertyAssignment();
        assignment.setAssignmentId(5L);
        assignment.setProperty(property);
        assignment.setEmployee(employee);
        assignment.setArea(area);
        assignment.setConditionAtIssue("GOOD");
    }

    @Test
    void listReturns_populatesReturnsModel() {
        Model model = new ConcurrentModel();
        when(returnService.searchReturns(any(), any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(new PropertyReturn()), PageRequest.of(0, 25), 1));

        String view = controller.listReturns(
                null, null, null, null, null, null, model, managerAuth);

        assertEquals("returns/list", view);
        assertEquals(1, ((List<?>) model.getAttribute("returns")).size());
        assertEquals(true, model.getAttribute("hasReturnPrivilege"));
        // The pager is drawn from the page the query reported, not from the rows.
        assertEquals(1L, model.getAttribute("totalElements"));
        assertEquals(0, model.getAttribute("pageNumber"));
        assertEquals("returned", model.getAttribute("sortKey"));
        assertEquals(25, model.getAttribute("pageSize"));
    }

    @Test
    void listReturns_staffUserHasNoReturnPrivilege() {
        Model model = new ConcurrentModel();
        when(returnService.searchReturns(any(), any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 25), 0));

        controller.listReturns(null, null, null, null, null, null, model, staffAuth);

        assertEquals(false, model.getAttribute("hasReturnPrivilege"));
    }

    @Test
    void listReturns_requestValuesAreForwardedAsGiven() {
        Model model = new ConcurrentModel();
        when(returnService.searchReturns(
                "laptop", "DAMAGED", 2, 10, "code", "desc"))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(2, 10), 0));

        controller.listReturns("laptop", "DAMAGED", 2, 10, "code", "desc", model, managerAuth);

        String filterQuery = (String) model.getAttribute("filterQuery");
        assertTrue(filterQuery.contains("search=laptop"), filterQuery);
        assertTrue(filterQuery.contains("condition=DAMAGED"), filterQuery);
        assertTrue(filterQuery.contains("sort=code"), filterQuery);
        assertTrue(filterQuery.contains("size=10"), filterQuery);
        assertEquals(Boolean.TRUE, model.getAttribute("filtersActive"));
    }

    @Test
    void listReturns_unsupportedSortAndPageSizeFallBackToTheDefaults() {
        Model model = new ConcurrentModel();
        when(returnService.searchReturns(any(), any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 25), 0));

        controller.listReturns(null, null, -3, 999, "passwordHash", "sideways", model, managerAuth);

        assertEquals("returned", model.getAttribute("sortKey"));
        assertEquals("asc", model.getAttribute("sortDirection"));
        assertEquals(25, model.getAttribute("pageSize"));
    }

    @Test
    void listReturns_unrecognisedConditionIsReportedWithoutRepeatingTheValue() {
        Model model = new ConcurrentModel();
        when(returnService.searchReturns(any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalArgumentException("Unrecognised condition filter: SYSDBA"));

        controller.listReturns(null, "SYSDBA", null, null, null, null, model, managerAuth);

        assertEquals("That filter is not one of the available options.",
                model.getAttribute("errorMessage"));
        assertTrue(((List<?>) model.getAttribute("returns")).isEmpty());
        assertEquals(0, model.getAttribute("totalPages"));
        // The rejected value must not survive in the form or in any link.
        assertNull(model.getAttribute("conditionFilter"));
        assertFalse(((String) model.getAttribute("filterQuery")).contains("SYSDBA"));
    }

    @Test
    void showReturnForm_manager_showsEligibleAssignments() {
        Model model = new ConcurrentModel();
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        when(assignmentRepository.findAllByOrderByAssignmentIdDesc()).thenReturn(List.of(assignment));
        when(returnRepository.existsByAssignment_AssignmentId(5L)).thenReturn(false);

        String view = controller.showReturnForm(model, managerAuth, redirectAttributes);

        assertEquals("returns/form", view);
        List<?> assignments = (List<?>) model.getAttribute("assignments");
        assertNotNull(assignments);
        assertEquals(1, assignments.size());
    }

    @Test
    void showReturnForm_staff_redirectsWithError() {
        Model model = new ConcurrentModel();
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        String view = controller.showReturnForm(model, staffAuth, redirectAttributes);

        assertEquals("redirect:/returns", view);
        assertEquals("You are not authorized to process returns.",
                redirectAttributes.getFlashAttributes().get("errorMessage"));
        verifyNoInteractions(assignmentRepository);
    }

    @Test
    void createReturn_manager_success() {
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        PropertyReturn ret = new PropertyReturn();
        when(returnService.returnProperty(5L, "GOOD", "All fine", "manager@example.com"))
                .thenReturn(ret);

        String view = controller.createReturn(5L, "GOOD", "All fine", managerAuth, redirectAttributes);

        assertEquals("redirect:/returns", view);
        assertNotNull(redirectAttributes.getFlashAttributes().get("successMessage"));
        verify(returnService).returnProperty(5L, "GOOD", "All fine", "manager@example.com");
    }

    @Test
    void createReturn_staff_redirectsWithError_withoutCallingService() {
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        String view = controller.createReturn(5L, "GOOD", null, staffAuth, redirectAttributes);

        assertEquals("redirect:/returns", view);
        assertEquals("You are not authorized to process returns.",
                redirectAttributes.getFlashAttributes().get("errorMessage"));
        verifyNoInteractions(returnService);
    }

    @Test
    void createReturn_serviceThrowsException_redirectsWithError() {
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        when(returnService.returnProperty(any(), any(), any(), any()))
                .thenThrow(new IllegalArgumentException("This assignment has already been returned."));

        String view = controller.createReturn(5L, "GOOD", null, managerAuth, redirectAttributes);

        assertEquals("redirect:/returns/new", view);
        assertEquals("This assignment has already been returned.",
                redirectAttributes.getFlashAttributes().get("errorMessage"));
    }

    @Test
    void showReturnForm_alreadyReturnedAssignmentsAreFiltered() {
        Model model = new ConcurrentModel();
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        when(assignmentRepository.findAllByOrderByAssignmentIdDesc()).thenReturn(List.of(assignment));
        // Simulate assignment already returned
        when(returnRepository.existsByAssignment_AssignmentId(5L)).thenReturn(true);

        controller.showReturnForm(model, managerAuth, redirectAttributes);

        List<?> assignments = (List<?>) model.getAttribute("assignments");
        assertNotNull(assignments);
        assertEquals(0, assignments.size());
    }
}
