package com.bssa.assetmanagement.service;

import com.bssa.assetmanagement.entity.AppUser;
import com.bssa.assetmanagement.entity.PropertyCategory;
import com.bssa.assetmanagement.entity.PropertyRequest;
import com.bssa.assetmanagement.entity.PropertyRequestItem;
import com.bssa.assetmanagement.entity.PropertyType;
import com.bssa.assetmanagement.repository.AppUserRepository;
import com.bssa.assetmanagement.repository.PropertyRequestRepository;
import com.bssa.assetmanagement.repository.PropertyTypeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PropertyRequestServiceTest {

    private final PropertyRequestRepository requestRepository = mock(PropertyRequestRepository.class);
    private final PropertyTypeRepository propertyTypeRepository = mock(PropertyTypeRepository.class);
    private final AppUserRepository appUserRepository = mock(AppUserRepository.class);

    private final PropertyRequestService service = new PropertyRequestService(
            requestRepository,
            propertyTypeRepository,
            appUserRepository
    );

    private AppUser requester;
    private AppUser otherUser;
    private AppUser reviewer;
    private PropertyCategory category;
    private PropertyType activePropertyType;
    private PropertyType inactivePropertyType;

    @BeforeEach
    void setUp() {
        reset(requestRepository, propertyTypeRepository, appUserRepository);

        requester = new AppUser();
        requester.setUserId(10L);
        requester.setEmail("staff@example.com");
        requester.setFirstName("Staff");
        requester.setLastName("User");
        requester.setActive(true);

        otherUser = new AppUser();
        otherUser.setUserId(20L);
        otherUser.setEmail("other@example.com");
        otherUser.setFirstName("Other");
        otherUser.setLastName("User");
        otherUser.setActive(true);

        reviewer = new AppUser();
        reviewer.setUserId(30L);
        reviewer.setEmail("admin@example.com");
        reviewer.setFirstName("Admin");
        reviewer.setLastName("User");
        reviewer.setActive(true);

        category = new PropertyCategory();
        category.setCategoryId(1L);
        category.setName("IT Equipment");

        activePropertyType = new PropertyType();
        activePropertyType.setPropertyTypeId(100L);
        activePropertyType.setName("ThinkPad Laptop");
        activePropertyType.setCategory(category);
        activePropertyType.setActive(true);

        inactivePropertyType = new PropertyType();
        inactivePropertyType.setPropertyTypeId(101L);
        inactivePropertyType.setName("Legacy Monitor");
        inactivePropertyType.setCategory(category);
        inactivePropertyType.setActive(false);

        when(appUserRepository.findByEmail("staff@example.com")).thenReturn(Optional.of(requester));
        when(appUserRepository.findByEmail("other@example.com")).thenReturn(Optional.of(otherUser));
        when(appUserRepository.findByEmail("admin@example.com")).thenReturn(Optional.of(reviewer));

        when(propertyTypeRepository.findById(100L)).thenReturn(Optional.of(activePropertyType));
        when(propertyTypeRepository.findById(101L)).thenReturn(Optional.of(inactivePropertyType));
    }

    @Test
    void createRequest_shouldSucceedWithValidInput() {
        when(requestRepository.save(any(PropertyRequest.class))).thenAnswer(invocation -> {
            PropertyRequest req = invocation.getArgument(0);
            req.setRequestId(1L);
            req.setRequestedAt(LocalDateTime.now());
            return req;
        });

        PropertyRequest created = service.createRequest(
                "staff@example.com",
                100L,
                2,
                "Need laptops for new staff",
                "16GB RAM preferred"
        );

        assertNotNull(created);
        assertEquals(1L, created.getRequestId());
        assertEquals("PENDING", created.getStatus());
        assertEquals("staff@example.com", created.getRequester().getEmail());
        assertEquals("Need laptops for new staff", created.getNotes());
        assertEquals(1, created.getItems().size());

        PropertyRequestItem item = created.getItems().get(0);
        assertEquals(activePropertyType, item.getPropertyType());
        assertEquals(2, item.getQuantity());
        assertEquals("16GB RAM preferred", item.getNotes());

        verify(requestRepository).save(any(PropertyRequest.class));
    }

    @Test
    void createRequest_shouldFailWhenQuantityIsZeroOrNegative() {
        IllegalArgumentException exZero = assertThrows(IllegalArgumentException.class, () ->
                service.createRequest("staff@example.com", 100L, 0, "Reason", null)
        );
        assertTrue(exZero.getMessage().contains("Quantity must be greater than zero"));

        IllegalArgumentException exNeg = assertThrows(IllegalArgumentException.class, () ->
                service.createRequest("staff@example.com", 100L, -5, "Reason", null)
        );
        assertTrue(exNeg.getMessage().contains("Quantity must be greater than zero"));
    }

    @Test
    void createRequest_shouldFailWhenQuantityExceedsLimit() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                service.createRequest("staff@example.com", 100L, 51, "Reason", null)
        );
        assertTrue(ex.getMessage().contains("cannot exceed " + PropertyRequestService.MAX_REQUEST_QUANTITY));
    }

    @Test
    void createRequest_shouldFailWhenPropertyTypeNotFoundOrInactive() {
        when(propertyTypeRepository.findById(999L)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () ->
                service.createRequest("staff@example.com", 999L, 1, "Reason", null)
        );

        assertThrows(IllegalArgumentException.class, () ->
                service.createRequest("staff@example.com", 101L, 1, "Reason", null)
        );
    }

    @Test
    void createRequest_shouldFailWhenRequesterInactive() {
        requester.setActive(false);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                service.createRequest("staff@example.com", 100L, 1, "Reason", null)
        );
        assertTrue(ex.getMessage().contains("Inactive users cannot"));
    }

    @Test
    void cancelRequest_shouldAllowOwnerToCancelPendingRequest() {
        PropertyRequest request = new PropertyRequest();
        request.setRequestId(5L);
        request.setRequester(requester);
        request.setStatus("PENDING");

        when(requestRepository.findById(5L)).thenReturn(Optional.of(request));
        when(requestRepository.save(any(PropertyRequest.class))).thenAnswer(i -> i.getArgument(0));

        PropertyRequest cancelled = service.cancelRequest(5L, "staff@example.com");
        assertEquals("CANCELLED", cancelled.getStatus());
    }

    @Test
    void cancelRequest_shouldForbidNonOwner() {
        PropertyRequest request = new PropertyRequest();
        request.setRequestId(5L);
        request.setRequester(requester);
        request.setStatus("PENDING");

        when(requestRepository.findById(5L)).thenReturn(Optional.of(request));

        assertThrows(AccessDeniedException.class, () ->
                service.cancelRequest(5L, "other@example.com")
        );
    }

    @Test
    void cancelRequest_shouldRejectNonPendingRequest() {
        PropertyRequest request = new PropertyRequest();
        request.setRequestId(5L);
        request.setRequester(requester);
        request.setStatus("APPROVED");

        when(requestRepository.findById(5L)).thenReturn(Optional.of(request));

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                service.cancelRequest(5L, "staff@example.com")
        );
        assertTrue(ex.getMessage().contains("Only pending requests can be cancelled"));
    }

    @Test
    void updateRequest_shouldAllowOwnerToUpdatePendingRequest() {
        PropertyRequest request = new PropertyRequest();
        request.setRequestId(5L);
        request.setRequester(requester);
        request.setStatus("PENDING");

        PropertyRequestItem item = new PropertyRequestItem();
        item.setPropertyType(activePropertyType);
        item.setQuantity(1);
        request.addItem(item);

        when(requestRepository.findById(5L)).thenReturn(Optional.of(request));
        when(requestRepository.save(any(PropertyRequest.class))).thenAnswer(i -> i.getArgument(0));

        PropertyRequest updated = service.updateRequest(
                5L,
                "staff@example.com",
                100L,
                5,
                "Updated purpose",
                "Updated item notes"
        );

        assertEquals("Updated purpose", updated.getNotes());
        assertEquals(5, updated.getItems().get(0).getQuantity());
        assertEquals("Updated item notes", updated.getItems().get(0).getNotes());
    }

    @Test
    void updateRequest_shouldForbidNonOwnerAndNonPending() {
        PropertyRequest request = new PropertyRequest();
        request.setRequestId(5L);
        request.setRequester(requester);
        request.setStatus("PENDING");

        when(requestRepository.findById(5L)).thenReturn(Optional.of(request));

        // Non owner forbidden
        assertThrows(AccessDeniedException.class, () ->
                service.updateRequest(5L, "other@example.com", 100L, 2, "Notes", null)
        );

        // Non pending state cannot be edited
        request.setStatus("REJECTED");
        assertThrows(IllegalStateException.class, () ->
                service.updateRequest(5L, "staff@example.com", 100L, 2, "Notes", null)
        );
    }

    @Test
    void getRequestForUser_shouldAllowOwnerAndReviewerButBlockUnauthorized() {
        PropertyRequest request = new PropertyRequest();
        request.setRequestId(8L);
        request.setRequester(requester);
        request.setStatus("PENDING");

        when(requestRepository.findWithDetailsByRequestId(8L)).thenReturn(Optional.of(request));

        // Requester can view
        PropertyRequest viewByOwner = service.getRequestForUser(8L, "staff@example.com", false);
        assertEquals(request, viewByOwner);

        // Reviewer can view
        PropertyRequest viewByReviewer = service.getRequestForUser(8L, "admin@example.com", true);
        assertEquals(request, viewByReviewer);

        // Unauthorized user without review role is blocked
        assertThrows(AccessDeniedException.class, () ->
                service.getRequestForUser(8L, "other@example.com", false)
        );
    }

    @Test
    void reviewRequest_shouldApprovePendingRequest() {
        PropertyRequest request = new PropertyRequest();
        request.setRequestId(9L);
        request.setRequester(requester);
        request.setStatus("PENDING");

        when(requestRepository.findById(9L)).thenReturn(Optional.of(request));
        when(requestRepository.save(any(PropertyRequest.class))).thenAnswer(i -> i.getArgument(0));

        PropertyRequest approved = service.reviewRequest(9L, "admin@example.com", "APPROVED", null);

        assertEquals("APPROVED", approved.getStatus());
        assertEquals(reviewer, approved.getReviewedBy());
        assertNotNull(approved.getReviewedAt());
        assertNull(approved.getRejectionReason());
    }

    @Test
    void reviewRequest_shouldRejectPendingRequestWithReason() {
        PropertyRequest request = new PropertyRequest();
        request.setRequestId(9L);
        request.setRequester(requester);
        request.setStatus("PENDING");

        when(requestRepository.findById(9L)).thenReturn(Optional.of(request));
        when(requestRepository.save(any(PropertyRequest.class))).thenAnswer(i -> i.getArgument(0));

        PropertyRequest rejected = service.reviewRequest(
                9L,
                "admin@example.com",
                "REJECTED",
                "Insufficient budget this quarter"
        );

        assertEquals("REJECTED", rejected.getStatus());
        assertEquals(reviewer, rejected.getReviewedBy());
        assertEquals("Insufficient budget this quarter", rejected.getRejectionReason());
    }

    @Test
    void reviewRequest_shouldRequireReasonWhenRejecting() {
        PropertyRequest request = new PropertyRequest();
        request.setRequestId(9L);
        request.setRequester(requester);
        request.setStatus("PENDING");

        when(requestRepository.findById(9L)).thenReturn(Optional.of(request));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                service.reviewRequest(9L, "admin@example.com", "REJECTED", "   ")
        );
        assertTrue(ex.getMessage().contains("rejection reason must be provided"));
    }
}
