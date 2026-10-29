package com.bssa.assetmanagement.controller;

import com.bssa.assetmanagement.entity.AppUser;
import com.bssa.assetmanagement.entity.PhysicalArea;
import com.bssa.assetmanagement.entity.Property;
import com.bssa.assetmanagement.entity.PropertyIncident;
import com.bssa.assetmanagement.entity.Role;
import com.bssa.assetmanagement.repository.AppUserRepository;
import com.bssa.assetmanagement.repository.PhysicalAreaRepository;
import com.bssa.assetmanagement.repository.PropertyRepository;
import com.bssa.assetmanagement.service.PropertyIncidentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class PropertyIncidentControllerTest {

    private final PropertyIncidentService incidentService = mock(PropertyIncidentService.class);
    private final PropertyRepository propertyRepository = mock(PropertyRepository.class);
    private final PhysicalAreaRepository physicalAreaRepository = mock(PhysicalAreaRepository.class);
    private final AppUserRepository appUserRepository = mock(AppUserRepository.class);

    private final PropertyIncidentController controller = new PropertyIncidentController(
            incidentService,
            propertyRepository,
            physicalAreaRepository,
            appUserRepository
    );

    private UsernamePasswordAuthenticationToken staffAuth;
    private UsernamePasswordAuthenticationToken managerAuth;
    private AppUser staffUser;
    private AppUser managerUser;
    private Property property;

    @BeforeEach
    void setUp() {
        reset(incidentService, propertyRepository, physicalAreaRepository, appUserRepository);

        staffAuth = new UsernamePasswordAuthenticationToken(
                "staff@example.com",
                "pwd",
                List.of(new SimpleGrantedAuthority("ROLE_OFFICE_STAFF"))
        );

        managerAuth = new UsernamePasswordAuthenticationToken(
                "manager@example.com",
                "pwd",
                List.of(new SimpleGrantedAuthority("ROLE_INVENTORY_MANAGER"))
        );

        Role staffRole = new Role();
        staffRole.setName("OFFICE_STAFF");

        Role managerRole = new Role();
        managerRole.setName("INVENTORY_MANAGER");

        staffUser = new AppUser();
        staffUser.setUserId(1L);
        staffUser.setEmail("staff@example.com");
        staffUser.setRoles(Set.of(staffRole));
        staffUser.setActive(true);

        managerUser = new AppUser();
        managerUser.setUserId(2L);
        managerUser.setEmail("manager@example.com");
        managerUser.setRoles(Set.of(managerRole));
        managerUser.setActive(true);

        property = new Property();
        property.setPropertyId(10L);
        property.setPropertyCode("PR-001");
        property.setDescription("Laptop");

        when(appUserRepository.findByEmail("staff@example.com")).thenReturn(Optional.of(staffUser));
        when(appUserRepository.findByEmail("manager@example.com")).thenReturn(Optional.of(managerUser));
    }

    @Test
    void listIncidents_staffUser_shouldPopulateMyIncidentsOnly() {
        Model model = new ConcurrentModel();
        PropertyIncident inc = new PropertyIncident();
        when(incidentService.getMyIncidents("staff@example.com")).thenReturn(List.of(inc));

        String view = controller.listIncidents(null, null, model, staffAuth);

        assertEquals("incidents/list", view);
        assertEquals(false, model.getAttribute("hasReviewPrivilege"));
        assertEquals(1, ((List<?>) model.getAttribute("myIncidents")).size());
        assertNull(model.getAttribute("openIncidents"));
        // The organization-wide register is not queried, so its row count never
        // reaches a page a staff member can open.
        verify(incidentService, never()).getAllIncidentsPage(any(), any());
        assertNull(model.getAttribute("totalElements"));
    }

    @Test
    void listIncidents_managerUser_shouldPopulateOpenAndAllIncidents() {
        Model model = new ConcurrentModel();
        when(incidentService.getMyIncidents("manager@example.com")).thenReturn(List.of());
        when(incidentService.getOpenIncidents()).thenReturn(List.of(new PropertyIncident()));
        when(incidentService.getAllIncidentsPage(null, 25)).thenReturn(new PageImpl<>(
                List.of(new PropertyIncident()), PageRequest.of(0, 25), 1));

        String view = controller.listIncidents(null, null, model, managerAuth);

        assertEquals("incidents/list", view);
        assertEquals(true, model.getAttribute("hasReviewPrivilege"));
        assertNotNull(model.getAttribute("openIncidents"));
        assertNotNull(model.getAttribute("allIncidents"));
        assertEquals(1L, model.getAttribute("totalElements"));
        assertEquals("size=25", model.getAttribute("filterQuery"));
    }

    @Test
    void listIncidents_requestedPageIsForwardedAndSizeIsValidated() {
        Model model = new ConcurrentModel();
        when(incidentService.getMyIncidents("manager@example.com")).thenReturn(List.of());
        when(incidentService.getOpenIncidents()).thenReturn(List.of());
        // The fixture total has to be at least the requested window, because
        // PageImpl otherwise rewrites it to fit the rows it was given.
        when(incidentService.getAllIncidentsPage(2, 10)).thenReturn(new PageImpl<>(
                List.of(new PropertyIncident()), PageRequest.of(2, 10), 60));
        when(incidentService.getAllIncidentsPage(0, 25)).thenReturn(new PageImpl<>(
                List.of(), PageRequest.of(0, 25), 0));

        controller.listIncidents(2, 10, model, managerAuth);
        assertEquals(2, model.getAttribute("pageNumber"));
        assertEquals(21, model.getAttribute("firstRow"));
        assertEquals(30, model.getAttribute("lastRow"));

        controller.listIncidents(0, 7, model, managerAuth);
        verify(incidentService).getAllIncidentsPage(0, 25);
    }

    @Test
    void newIncidentForm_shouldLoadProperties() {
        Model model = new ConcurrentModel();
        when(propertyRepository.findAll()).thenReturn(List.of(property));

        String view = controller.showReportForm(null, model, staffAuth);

        assertEquals("incidents/form", view);
        assertNotNull(model.getAttribute("properties"));
    }

    @Test
    void reportIncident_success_shouldRedirectWithSuccessMessage() {
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        PropertyIncident inc = new PropertyIncident();
        inc.setIncidentId(55L);
        inc.setProperty(property);
        when(incidentService.reportIncident(eq(10L), eq("DAMAGE"), eq("Cracked screen"), eq("staff@example.com")))
                .thenReturn(inc);

        String view = controller.reportIncident(10L, "DAMAGE", "Cracked screen", staffAuth, redirectAttributes);

        assertEquals("redirect:/incidents/55", view);
        assertNotNull(redirectAttributes.getFlashAttributes().get("successMessage"));
    }

    @Test
    void reportIncident_failure_shouldRedirectBackWithErrorMessage() {
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        when(incidentService.reportIncident(any(), any(), any(), any()))
                .thenThrow(new IllegalArgumentException("Description is required"));

        String view = controller.reportIncident(10L, "DAMAGE", "", staffAuth, redirectAttributes);

        assertEquals("redirect:/incidents/new?propertyId=10", view);
        assertEquals("Description is required", redirectAttributes.getFlashAttributes().get("errorMessage"));
    }

    @Test
    void viewIncident_success_shouldPopulateViewModel() {
        Model model = new ConcurrentModel();
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        PropertyIncident inc = new PropertyIncident();
        inc.setIncidentId(55L);
        inc.setStatus("OPEN");
        inc.setProperty(property);
        inc.setReportedBy(staffUser);
        when(incidentService.getIncidentForUser(55L, "staff@example.com", false)).thenReturn(inc);
        when(physicalAreaRepository.findAllByActiveTrueOrderByNameAsc()).thenReturn(List.of());

        String view = controller.viewIncident(55L, model, staffAuth, redirectAttributes);

        assertEquals("incidents/view", view);
        assertEquals(inc, model.getAttribute("incident"));
        assertEquals(false, model.getAttribute("hasReviewPrivilege"));
    }

    @Test
    void resolveIncident_authorizedManager_shouldRedirectToViewWithSuccess() {
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        String view = controller.resolveIncident(
                55L,
                "RESOLVED",
                "Sent to workshop",
                "REPAIR",
                managerAuth,
                redirectAttributes
        );

        assertEquals("redirect:/incidents/55", view);
        verify(incidentService).resolveIncident(55L, "RESOLVED", "Sent to workshop", "REPAIR", "manager@example.com");
        assertNotNull(redirectAttributes.getFlashAttributes().get("successMessage"));
    }

    @Test
    void resolveIncident_unauthorizedStaff_redirectsWithError() {
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        String view = controller.resolveIncident(
                55L,
                "RESOLVED",
                "Staff attempting resolution",
                "REPAIR",
                staffAuth,
                redirectAttributes
        );

        assertEquals("redirect:/incidents", view);
        assertEquals("You are not authorized to review or resolve incidents.",
                redirectAttributes.getFlashAttributes().get("errorMessage"));
        verifyNoInteractions(incidentService);
    }

    @Test
    void returnToService_authorizedManager_shouldRedirectWithSuccess() {
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        when(incidentService.returnToService(10L, null, "Repairs verified", "manager@example.com"))
                .thenReturn(property);

        String view = controller.returnToService(
                10L,
                null,
                "Repairs verified",
                managerAuth,
                redirectAttributes
        );

        assertEquals("redirect:/properties", view);
        verify(incidentService).returnToService(10L, null, "Repairs verified", "manager@example.com");
        assertNotNull(redirectAttributes.getFlashAttributes().get("successMessage"));
    }

    @Test
    void returnToService_unauthorizedStaff_redirectsWithError() {
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        String view = controller.returnToService(
                10L,
                null,
                "Staff return attempt",
                staffAuth,
                redirectAttributes
        );

        assertEquals("redirect:/incidents", view);
        assertEquals("You are not authorized to return assets to service.",
                redirectAttributes.getFlashAttributes().get("errorMessage"));
        verifyNoInteractions(incidentService);
    }
}
