package com.bssa.assetmanagement.controller;

import com.bssa.assetmanagement.entity.AppUser;
import com.bssa.assetmanagement.entity.PhysicalArea;
import com.bssa.assetmanagement.entity.Property;
import com.bssa.assetmanagement.entity.PropertyAssignment;
import com.bssa.assetmanagement.entity.PropertyRequest;
import com.bssa.assetmanagement.entity.PropertyRequestItem;
import com.bssa.assetmanagement.entity.PropertyType;
import com.bssa.assetmanagement.repository.AppUserRepository;
import com.bssa.assetmanagement.repository.PhysicalAreaRepository;
import com.bssa.assetmanagement.repository.PropertyRepository;
import com.bssa.assetmanagement.repository.PropertyTypeRepository;
import com.bssa.assetmanagement.service.PropertyRequestService;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class PropertyRequestControllerTest {

    private final PropertyRequestService requestService = mock(PropertyRequestService.class);
    private final PropertyTypeRepository propertyTypeRepository = mock(PropertyTypeRepository.class);
    private final AppUserRepository appUserRepository = mock(AppUserRepository.class);
    private final PropertyRepository propertyRepository = mock(PropertyRepository.class);
    private final PhysicalAreaRepository physicalAreaRepository = mock(PhysicalAreaRepository.class);

    private final PropertyRequestController controller = new PropertyRequestController(
            requestService,
            propertyTypeRepository,
            appUserRepository,
            propertyRepository,
            physicalAreaRepository
    );

    private UsernamePasswordAuthenticationToken staffAuth;
    private UsernamePasswordAuthenticationToken adminAuth;
    private AppUser activeUser;

    @BeforeEach
    void setUp() {
        reset(requestService, propertyTypeRepository, appUserRepository,
                propertyRepository, physicalAreaRepository);

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

        String view = controller.listRequests(null, null, model, staffAuth);

        assertEquals("requests/list", view);
        assertEquals(1, ((List<?>) model.getAttribute("myRequests")).size());
        assertEquals(false, model.getAttribute("hasReviewPrivilege"));
        assertNull(model.getAttribute("allRequests"));
        // The organization-wide history is not read at all for a caller who is
        // not allowed to see it.
        verify(requestService, never()).getAllRequestsPage(any(), any());
    }

    @Test
    void listRequests_forAdmin_shouldPopulatePendingAndAllRequests() {
        Model model = new ConcurrentModel();
        when(requestService.getMyRequests("admin@example.com")).thenReturn(List.of(new PropertyRequest()));
        when(requestService.getPendingRequests()).thenReturn(List.of(new PropertyRequest()));
        when(requestService.countPendingRequests()).thenReturn(1L);
        when(requestService.getAllRequestsPage(null, 25)).thenReturn(new PageImpl<>(
                List.of(new PropertyRequest(), new PropertyRequest()), PageRequest.of(0, 25), 2));

        String view = controller.listRequests(null, null, model, adminAuth);

        assertEquals("requests/list", view);
        assertEquals(true, model.getAttribute("hasReviewPrivilege"));
        assertEquals(1, ((List<?>) model.getAttribute("pendingRequests")).size());
        assertEquals(1L, model.getAttribute("pendingCount"));
        assertEquals(2, ((List<?>) model.getAttribute("allRequests")).size());
        assertEquals(2L, model.getAttribute("totalElements"));
        assertEquals("size=25", model.getAttribute("filterQuery"));
    }

    @Test
    void listRequests_requestedPageIsForwardedAndSizeIsValidated() {
        Model model = new ConcurrentModel();
        when(requestService.getMyRequests("admin@example.com")).thenReturn(List.of());
        when(requestService.getPendingRequests()).thenReturn(List.of());
        // The fixture total has to be at least the requested window, because
        // PageImpl otherwise rewrites it to fit the rows it was given.
        when(requestService.getAllRequestsPage(4, 100)).thenReturn(new PageImpl<>(
                List.of(new PropertyRequest()), PageRequest.of(4, 100), 600));
        when(requestService.getAllRequestsPage(0, 25)).thenReturn(new PageImpl<>(
                List.of(), PageRequest.of(0, 25), 0));

        controller.listRequests(4, 100, model, adminAuth);
        assertEquals(4, model.getAttribute("pageNumber"));
        assertEquals(401, model.getAttribute("firstRow"));
        assertEquals(500, model.getAttribute("lastRow"));

        // A page size the list does not offer is replaced by the default rather
        // than being handed to the database.
        controller.listRequests(0, 999, model, adminAuth);
        verify(requestService).getAllRequestsPage(0, 25);
        assertEquals(25, model.getAttribute("pageSize"));
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
        assertEquals(true, model.getAttribute("isSelfRequest"));
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
    void showFulfillForm_shouldDisplayWhenApprovedAndManager() {
        Model model = new ConcurrentModel();
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        PropertyType propertyType = new PropertyType();
        propertyType.setPropertyTypeId(50L);

        PropertyRequestItem item = new PropertyRequestItem();
        item.setPropertyType(propertyType);

        PropertyRequest request = new PropertyRequest();
        request.setRequestId(30L);
        request.setStatus("APPROVED");
        request.setRequester(activeUser);
        request.addItem(item);

        when(requestService.getRequestForUser(eq(30L), eq("admin@example.com"), eq(true)))
                .thenReturn(request);
        when(propertyRepository.findByPropertyType_PropertyTypeIdAndStatusAndConditionOrderByPropertyCodeAsc(
                eq(50L), eq("AVAILABLE"), eq("GOOD"))).thenReturn(List.of(new Property()));
        when(physicalAreaRepository.findAllByActiveTrueOrderByNameAsc()).thenReturn(List.of(new PhysicalArea()));

        String view = controller.showFulfillForm(30L, model, adminAuth, redirectAttributes);

        assertEquals("requests/fulfill", view);
        assertEquals(request, model.getAttribute("request"));
        assertEquals(1, ((List<?>) model.getAttribute("availableProperties")).size());
        assertEquals(1, ((List<?>) model.getAttribute("areas")).size());
    }

    @Test
    void showFulfillForm_shouldRedirectWhenNotApproved() {
        Model model = new ConcurrentModel();
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        PropertyRequest request = new PropertyRequest();
        request.setRequestId(30L);
        request.setStatus("PENDING");
        request.setRequester(activeUser);

        when(requestService.getRequestForUser(eq(30L), eq("admin@example.com"), eq(true)))
                .thenReturn(request);

        String view = controller.showFulfillForm(30L, model, adminAuth, redirectAttributes);

        assertEquals("redirect:/requests/30", view);
        assertTrue(redirectAttributes.getFlashAttributes().get("errorMessage").toString().contains("Only approved requests"));
    }

    @Test
    void fulfillRequest_shouldFulfillAndRedirectOnSuccess() {
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        when(requestService.fulfillRequest(eq(30L), eq(500L), eq(1L), eq("GOOD"), eq("Notes"), eq("admin@example.com")))
                .thenReturn(new PropertyAssignment());

        String view = controller.fulfillRequest(30L, 500L, 1L, "GOOD", "Notes", adminAuth, redirectAttributes);

        assertEquals("redirect:/requests/30", view);
        assertTrue(redirectAttributes.getFlashAttributes().get("successMessage").toString().contains("fulfilled successfully"));
    }

    @Test
    void fulfillRequest_shouldRejectOfficeStaff() {
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        String view = controller.fulfillRequest(30L, 500L, 1L, "GOOD", "Notes", staffAuth, redirectAttributes);

        assertEquals("redirect:/requests", view);
        assertEquals("You are not authorized to fulfill asset requests.", redirectAttributes.getFlashAttributes().get("errorMessage"));
        verify(requestService, never()).fulfillRequest(any(), any(), any(), any(), any(), any());
    }
}
