package com.bssa.assetmanagement.controller;

import com.bssa.assetmanagement.entity.AppUser;
import com.bssa.assetmanagement.entity.PropertyRequest;
import com.bssa.assetmanagement.entity.PropertyType;
import com.bssa.assetmanagement.repository.AppUserRepository;
import com.bssa.assetmanagement.repository.PropertyTypeRepository;
import com.bssa.assetmanagement.service.PropertyRequestService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class PropertyRequestControllerTest {

    private final PropertyRequestService requestService = mock(PropertyRequestService.class);
    private final PropertyTypeRepository propertyTypeRepository = mock(PropertyTypeRepository.class);
    private final AppUserRepository appUserRepository = mock(AppUserRepository.class);

    private final PropertyRequestController controller = new PropertyRequestController(
            requestService,
            propertyTypeRepository,
            appUserRepository
    );

    private UsernamePasswordAuthenticationToken staffAuth;
    private UsernamePasswordAuthenticationToken adminAuth;
    private AppUser activeUser;

    @BeforeEach
    void setUp() {
        reset(requestService, propertyTypeRepository, appUserRepository);

        staffAuth = new UsernamePasswordAuthenticationToken(
                "staff@example.com",
                "pwd",
                List.of(new SimpleGrantedAuthority("ROLE_OFFICE_STAFF"))
        );

        adminAuth = new UsernamePasswordAuthenticationToken(
                "admin@example.com",
                "pwd",
                List.of(new SimpleGrantedAuthority("ROLE_INVENTORY_ADMIN"))
        );

        activeUser = new AppUser();
        activeUser.setEmail("staff@example.com");
        activeUser.setActive(true);

        when(appUserRepository.findByEmail("staff@example.com")).thenReturn(Optional.of(activeUser));
        when(appUserRepository.findByEmail("admin@example.com")).thenReturn(Optional.of(activeUser));
    }

    @Test
    void listRequests_forStaff_shouldPopulateMyRequestsOnly() {
        Model model = new ConcurrentModel();
        when(requestService.getMyRequests("staff@example.com")).thenReturn(List.of(new PropertyRequest()));

        String view = controller.listRequests(model, staffAuth);

        assertEquals("requests/list", view);
        assertEquals(1, ((List<?>) model.getAttribute("myRequests")).size());
        assertEquals(false, model.getAttribute("hasReviewPrivilege"));
        assertNull(model.getAttribute("allRequests"));
    }

    @Test
    void listRequests_forAdmin_shouldPopulateAllRequests() {
        Model model = new ConcurrentModel();
        when(requestService.getMyRequests("admin@example.com")).thenReturn(List.of(new PropertyRequest()));
        when(requestService.getAllRequests()).thenReturn(List.of(new PropertyRequest(), new PropertyRequest()));

        String view = controller.listRequests(model, adminAuth);

        assertEquals("requests/list", view);
        assertEquals(true, model.getAttribute("hasReviewPrivilege"));
        assertEquals(2, ((List<?>) model.getAttribute("allRequests")).size());
    }

    @Test
    void showCreateForm_shouldAddActivePropertyTypes() {
        Model model = new ConcurrentModel();
        when(propertyTypeRepository.findAllByActiveTrueOrderByNameAsc()).thenReturn(List.of(new PropertyType()));

        String view = controller.showCreateForm(model, staffAuth);

        assertEquals("requests/form", view);
        assertNotNull(model.getAttribute("propertyTypes"));
    }

    @Test
    void createRequest_shouldRedirectOnSuccess() {
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        String view = controller.createRequest(
                100L,
                2,
                "Need replacement mouse",
                "Ergonomic preferred",
                staffAuth,
                redirectAttributes
        );

        assertEquals("redirect:/requests", view);
        assertNotNull(redirectAttributes.getFlashAttributes().get("successMessage"));
        verify(requestService).createRequest("staff@example.com", 100L, 2, "Need replacement mouse", "Ergonomic preferred");
    }

    @Test
    void createRequest_shouldRedirectToFormOnError() {
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        doThrow(new IllegalArgumentException("Quantity must be greater than zero."))
                .when(requestService).createRequest(any(), any(), any(), any(), any());

        String view = controller.createRequest(
                100L,
                0,
                "Need mouse",
                null,
                staffAuth,
                redirectAttributes
        );

        assertEquals("redirect:/requests/new", view);
        assertEquals("Quantity must be greater than zero.", redirectAttributes.getFlashAttributes().get("errorMessage"));
    }

    @Test
    void viewRequest_shouldDisplayWhenAuthorized() {
        Model model = new ConcurrentModel();
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        PropertyRequest request = new PropertyRequest();
        request.setRequestId(12L);
        request.setRequester(activeUser);

        when(requestService.getRequestForUser(eq(12L), eq("staff@example.com"), eq(false)))
                .thenReturn(request);

        String view = controller.viewRequest(12L, model, staffAuth, redirectAttributes);

        assertEquals("requests/view", view);
        assertEquals(request, model.getAttribute("request"));
        assertEquals(true, model.getAttribute("isOwner"));
    }

    @Test
    void viewRequest_shouldRedirectWhenAccessDenied() {
        Model model = new ConcurrentModel();
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        when(requestService.getRequestForUser(any(), any(), anyBoolean()))
                .thenThrow(new AccessDeniedException("You are not permitted to view this request."));

        String view = controller.viewRequest(99L, model, staffAuth, redirectAttributes);

        assertEquals("redirect:/requests", view);
        assertEquals("You are not permitted to view this request.", redirectAttributes.getFlashAttributes().get("errorMessage"));
    }

    @Test
    void cancelRequest_shouldRedirectWithSuccess() {
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        String view = controller.cancelRequest(12L, staffAuth, redirectAttributes);

        assertEquals("redirect:/requests", view);
        assertNotNull(redirectAttributes.getFlashAttributes().get("successMessage"));
        verify(requestService).cancelRequest(12L, "staff@example.com");
    }

    @Test
    void editForm_shouldReturnFormWhenPending() {
        Model model = new ConcurrentModel();
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        PropertyRequest request = new PropertyRequest();
        request.setRequestId(15L);
        request.setStatus("PENDING");
        request.setRequester(activeUser);

        when(requestService.getRequestForUser(eq(15L), eq("staff@example.com"), eq(false)))
                .thenReturn(request);

        String view = controller.showEditForm(15L, model, staffAuth, redirectAttributes);

        assertEquals("requests/form", view);
        assertEquals(true, model.getAttribute("isEdit"));
    }

    @Test
    void editForm_shouldRedirectWhenNotPending() {
        Model model = new ConcurrentModel();
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        PropertyRequest request = new PropertyRequest();
        request.setRequestId(15L);
        request.setStatus("APPROVED");
        request.setRequester(activeUser);

        when(requestService.getRequestForUser(eq(15L), eq("staff@example.com"), eq(false)))
                .thenReturn(request);

        String view = controller.showEditForm(15L, model, staffAuth, redirectAttributes);

        assertEquals("redirect:/requests/15", view);
        assertEquals("Only pending requests can be edited.", redirectAttributes.getFlashAttributes().get("errorMessage"));
    }

    @Test
    void reviewRequest_shouldAuthorizeAdminAndRedirect() {
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        String view = controller.reviewRequest(20L, "APPROVED", null, adminAuth, redirectAttributes);

        assertEquals("redirect:/requests/20", view);
        assertNotNull(redirectAttributes.getFlashAttributes().get("successMessage"));
        verify(requestService).reviewRequest(20L, "admin@example.com", "APPROVED", null);
    }

    @Test
    void reviewRequest_shouldRejectOfficeStaff() {
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        String view = controller.reviewRequest(20L, "APPROVED", null, staffAuth, redirectAttributes);

        assertEquals("redirect:/requests", view);
        assertEquals("You are not authorized to review asset requests.", redirectAttributes.getFlashAttributes().get("errorMessage"));
        verify(requestService, never()).reviewRequest(any(), any(), any(), any());
    }

    @Test
    void inactiveCaller_shouldRedirectToLogout() {
        activeUser.setActive(false);
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        String view = controller.createRequest(100L, 1, "Notes", null, staffAuth, redirectAttributes);

        assertEquals("redirect:/login?logout", view);
    }
}
