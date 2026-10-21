package com.bssa.assetmanagement.service;

import com.bssa.assetmanagement.entity.AppUser;
import com.bssa.assetmanagement.entity.PhysicalArea;
import com.bssa.assetmanagement.entity.Property;
import com.bssa.assetmanagement.entity.PropertyAssignment;
import com.bssa.assetmanagement.entity.PropertyCategory;
import com.bssa.assetmanagement.entity.PropertyRequest;
import com.bssa.assetmanagement.entity.PropertyRequestItem;
import com.bssa.assetmanagement.entity.PropertyStatusHistory;
import com.bssa.assetmanagement.entity.PropertyType;
import com.bssa.assetmanagement.entity.Role;
import com.bssa.assetmanagement.repository.AppUserRepository;
import com.bssa.assetmanagement.repository.PhysicalAreaRepository;
import com.bssa.assetmanagement.repository.PropertyAssignmentRepository;
import com.bssa.assetmanagement.repository.PropertyRepository;
import com.bssa.assetmanagement.repository.PropertyRequestRepository;
import com.bssa.assetmanagement.repository.PropertyStatusHistoryRepository;
import com.bssa.assetmanagement.repository.PropertyTypeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PropertyRequestServiceTest {

    private final PropertyRequestRepository requestRepository = mock(PropertyRequestRepository.class);
    private final PropertyTypeRepository propertyTypeRepository = mock(PropertyTypeRepository.class);
    private final AppUserRepository appUserRepository = mock(AppUserRepository.class);
    private final PropertyRepository propertyRepository = mock(PropertyRepository.class);
    private final PhysicalAreaRepository physicalAreaRepository = mock(PhysicalAreaRepository.class);
    private final PropertyAssignmentRepository assignmentRepository = mock(PropertyAssignmentRepository.class);
    private final PropertyStatusHistoryRepository statusHistoryRepository = mock(PropertyStatusHistoryRepository.class);

    private final PropertyRequestService service = new PropertyRequestService(
            requestRepository,
            propertyTypeRepository,
            appUserRepository,
            propertyRepository,
            physicalAreaRepository,
            assignmentRepository,
            statusHistoryRepository
    );

    private AppUser requesterStaff;
    private AppUser requesterManager;
    private AppUser reviewerManager;
    private PropertyCategory category;
    private PropertyType activePropertyType;
    private PropertyType otherPropertyType;
    private PropertyType inactivePropertyType;
    private PhysicalArea activeArea;
    private Property availableProperty;

    @BeforeEach
    void setUp() {
        reset(requestRepository, propertyTypeRepository, appUserRepository,
                propertyRepository, physicalAreaRepository, assignmentRepository, statusHistoryRepository);

        Role staffRole = new Role();
        staffRole.setRoleId(1L);
        staffRole.setName("OFFICE_STAFF");

        Role managerRole = new Role();
        managerRole.setRoleId(2L);
        managerRole.setName("INVENTORY_MANAGER");

        requesterStaff = new AppUser();
        requesterStaff.setUserId(10L);
        requesterStaff.setEmail("staff@example.com");
        requesterStaff.setFirstName("Staff");
        requesterStaff.setLastName("User");
        requesterStaff.setActive(true);
        requesterStaff.setRoles(Set.of(staffRole));

        requesterManager = new AppUser();
        requesterManager.setUserId(20L);
        requesterManager.setEmail("manager1@example.com");
        requesterManager.setFirstName("Manager");
        requesterManager.setLastName("One");
        requesterManager.setActive(true);
        requesterManager.setRoles(Set.of(managerRole));

        reviewerManager = new AppUser();
        reviewerManager.setUserId(30L);
        reviewerManager.setEmail("manager2@example.com");
        reviewerManager.setFirstName("Manager");
        reviewerManager.setLastName("Two");
        reviewerManager.setActive(true);
        reviewerManager.setRoles(Set.of(managerRole));

        category = new PropertyCategory();
        category.setCategoryId(1L);
        category.setName("IT Equipment");

        activePropertyType = new PropertyType();
        activePropertyType.setPropertyTypeId(100L);
        activePropertyType.setName("ThinkPad Laptop");
        activePropertyType.setCategory(category);
        activePropertyType.setActive(true);

        otherPropertyType = new PropertyType();
        otherPropertyType.setPropertyTypeId(102L);
        otherPropertyType.setName("Dell Monitor");
        otherPropertyType.setCategory(category);
        otherPropertyType.setActive(true);

        inactivePropertyType = new PropertyType();
        inactivePropertyType.setPropertyTypeId(101L);
        inactivePropertyType.setName("Legacy Monitor");
        inactivePropertyType.setCategory(category);
        inactivePropertyType.setActive(false);

        activeArea = new PhysicalArea();
        activeArea.setAreaId(1L);
        activeArea.setName("Main Headquarters");
        activeArea.setActive(true);

        availableProperty = new Property();
        availableProperty.setPropertyId(500L);
        availableProperty.setPropertyCode("BSAA-IT-500");
        availableProperty.setPropertyType(activePropertyType);
        availableProperty.setArea(activeArea);
        availableProperty.setStatus("AVAILABLE");
        availableProperty.setCondition("GOOD");
        availableProperty.setPurchaseDate(LocalDate.now());

        when(appUserRepository.findByEmail("staff@example.com")).thenReturn(Optional.of(requesterStaff));
        when(appUserRepository.findByEmail("manager1@example.com")).thenReturn(Optional.of(requesterManager));
        when(appUserRepository.findByEmail("manager2@example.com")).thenReturn(Optional.of(reviewerManager));

        when(propertyTypeRepository.findById(100L)).thenReturn(Optional.of(activePropertyType));
        when(propertyTypeRepository.findById(101L)).thenReturn(Optional.of(inactivePropertyType));
        when(propertyTypeRepository.findById(102L)).thenReturn(Optional.of(otherPropertyType));

        when(physicalAreaRepository.findById(1L)).thenReturn(Optional.of(activeArea));
        when(propertyRepository.findById(500L)).thenReturn(Optional.of(availableProperty));
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
        assertEquals(1, created.getItems().size());
        verify(requestRepository).save(any(PropertyRequest.class));
    }

    @Test
    void reviewRequest_authorizedManagerCanApproveAnotherUsersPendingRequest() {
        PropertyRequest request = new PropertyRequest();
        request.setRequestId(10L);
        request.setRequester(requesterStaff);
        request.setStatus("PENDING");

        when(requestRepository.findById(10L)).thenReturn(Optional.of(request));
        when(requestRepository.save(any(PropertyRequest.class))).thenAnswer(i -> i.getArgument(0));

        PropertyRequest approved = service.reviewRequest(10L, "manager2@example.com", "APPROVED", null);

        assertEquals("APPROVED", approved.getStatus());
        assertEquals(reviewerManager, approved.getReviewedBy());
        assertNotNull(approved.getReviewedAt());
        assertNull(approved.getRejectionReason());
    }

    @Test
    void reviewRequest_authorizedManagerCanRejectAnotherUsersPendingRequest() {
        PropertyRequest request = new PropertyRequest();
        request.setRequestId(10L);
        request.setRequester(requesterStaff);
        request.setStatus("PENDING");

        when(requestRepository.findById(10L)).thenReturn(Optional.of(request));
        when(requestRepository.save(any(PropertyRequest.class))).thenAnswer(i -> i.getArgument(0));

        PropertyRequest rejected = service.reviewRequest(
                10L,
                "manager2@example.com",
                "REJECTED",
                "Budget allocated for next quarter only"
        );

        assertEquals("REJECTED", rejected.getStatus());
        assertEquals(reviewerManager, rejected.getReviewedBy());
        assertEquals("Budget allocated for next quarter only", rejected.getRejectionReason());
    }

    @Test
    void reviewRequest_managerCannotApproveOrRejectOwnRequest() {
        PropertyRequest request = new PropertyRequest();
        request.setRequestId(11L);
        request.setRequester(requesterManager);
        request.setStatus("PENDING");

        when(requestRepository.findById(11L)).thenReturn(Optional.of(request));

        // Self-approval attempt
        IllegalStateException exApprove = assertThrows(IllegalStateException.class, () ->
                service.reviewRequest(11L, "manager1@example.com", "APPROVED", null)
        );
        assertTrue(exApprove.getMessage().contains("not permitted to approve or reject their own requests"));

        // Self-rejection attempt
        IllegalStateException exReject = assertThrows(IllegalStateException.class, () ->
                service.reviewRequest(11L, "manager1@example.com", "REJECTED", "Self rejection")
        );
        assertTrue(exReject.getMessage().contains("not permitted to approve or reject their own requests"));
    }

    @Test
    void reviewRequest_staffCannotApproveOrRejectRequests() {
        PropertyRequest request = new PropertyRequest();
        request.setRequestId(12L);
        request.setRequester(requesterManager);
        request.setStatus("PENDING");

        when(requestRepository.findById(12L)).thenReturn(Optional.of(request));

        AccessDeniedException ex = assertThrows(AccessDeniedException.class, () ->
                service.reviewRequest(12L, "staff@example.com", "APPROVED", null)
        );
        assertTrue(ex.getMessage().contains("Unauthorized to review asset requests"));
    }

    @Test
    void reviewRequest_alreadyDecidedRequestsCannotBeDecidedAgain() {
        PropertyRequest request = new PropertyRequest();
        request.setRequestId(13L);
        request.setRequester(requesterStaff);
        request.setStatus("APPROVED");

        when(requestRepository.findById(13L)).thenReturn(Optional.of(request));

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                service.reviewRequest(13L, "manager2@example.com", "REJECTED", "Change of mind")
        );
        assertTrue(ex.getMessage().contains("Only requests in a pending state can be decided"));
    }

    @Test
    void fulfillRequest_pendingRejectedOrCancelledRequestsCannotBeFulfilled() {
        PropertyRequest reqPending = new PropertyRequest();
        reqPending.setRequestId(21L);
        reqPending.setRequester(requesterStaff);
        reqPending.setStatus("PENDING");

        when(requestRepository.findWithDetailsByRequestId(21L)).thenReturn(Optional.of(reqPending));

        IllegalStateException exPending = assertThrows(IllegalStateException.class, () ->
                service.fulfillRequest(21L, 500L, 1L, "GOOD", "Handover", "manager2@example.com")
        );
        assertTrue(exPending.getMessage().contains("Only approved requests can be fulfilled"));

        PropertyRequest reqRejected = new PropertyRequest();
        reqRejected.setRequestId(22L);
        reqRejected.setRequester(requesterStaff);
        reqRejected.setStatus("REJECTED");
        when(requestRepository.findWithDetailsByRequestId(22L)).thenReturn(Optional.of(reqRejected));

        assertThrows(IllegalStateException.class, () ->
                service.fulfillRequest(22L, 500L, 1L, "GOOD", "Handover", "manager2@example.com")
        );

        PropertyRequest reqCancelled = new PropertyRequest();
        reqCancelled.setRequestId(23L);
        reqCancelled.setRequester(requesterStaff);
        reqCancelled.setStatus("CANCELLED");
        when(requestRepository.findWithDetailsByRequestId(23L)).thenReturn(Optional.of(reqCancelled));

        assertThrows(IllegalStateException.class, () ->
                service.fulfillRequest(23L, 500L, 1L, "GOOD", "Handover", "manager2@example.com")
        );
    }

    @Test
    void fulfillRequest_approvedRequestCanBeFulfilledSuccessfully() {
        PropertyRequest request = new PropertyRequest();
        request.setRequestId(30L);
        request.setRequester(requesterStaff);
        request.setStatus("APPROVED");

        PropertyRequestItem item = new PropertyRequestItem();
        item.setPropertyType(activePropertyType);
        item.setQuantity(1);
        request.addItem(item);

        when(requestRepository.findWithDetailsByRequestId(30L)).thenReturn(Optional.of(request));
        when(assignmentRepository.hasActiveAssignment(500L)).thenReturn(false);
        when(assignmentRepository.save(any(PropertyAssignment.class))).thenAnswer(i -> {
            PropertyAssignment pa = i.getArgument(0);
            pa.setAssignmentId(999L);
            return pa;
        });

        PropertyAssignment result = service.fulfillRequest(
                30L,
                500L,
                1L,
                "GOOD",
                "Laptop handed over in person",
                "manager2@example.com"
        );

        assertNotNull(result);
        assertEquals("FULFILLED", request.getStatus());
        assertEquals("ASSIGNED", availableProperty.getStatus());
        assertEquals(requesterStaff, result.getEmployee());
        assertEquals(reviewerManager, result.getIssuedBy());
        assertEquals(availableProperty, result.getProperty());

        verify(statusHistoryRepository).save(any(PropertyStatusHistory.class));
        verify(assignmentRepository).save(any(PropertyAssignment.class));
        verify(requestRepository).save(request);
    }

    @Test
    void fulfillRequest_unavailableAssetCannotBeAllocated() {
        PropertyRequest request = new PropertyRequest();
        request.setRequestId(31L);
        request.setRequester(requesterStaff);
        request.setStatus("APPROVED");

        PropertyRequestItem item = new PropertyRequestItem();
        item.setPropertyType(activePropertyType);
        item.setQuantity(1);
        request.addItem(item);

        when(requestRepository.findWithDetailsByRequestId(31L)).thenReturn(Optional.of(request));

        // Unavailable status
        availableProperty.setStatus("UNDER_REPAIR");
        IllegalArgumentException exStatus = assertThrows(IllegalArgumentException.class, () ->
                service.fulfillRequest(31L, 500L, 1L, "GOOD", null, "manager2@example.com")
        );
        assertTrue(exStatus.getMessage().contains("is not available"));

        // Available status but broken condition
        availableProperty.setStatus("AVAILABLE");
        availableProperty.setCondition("BROKEN");
        IllegalArgumentException exCondition = assertThrows(IllegalArgumentException.class, () ->
                service.fulfillRequest(31L, 500L, 1L, "GOOD", null, "manager2@example.com")
        );
        assertTrue(exCondition.getMessage().contains("is not in good condition"));
    }

    @Test
    void fulfillRequest_oneAssetCannotBeAllocatedToTwoActiveAssignments() {
        PropertyRequest request = new PropertyRequest();
        request.setRequestId(32L);
        request.setRequester(requesterStaff);
        request.setStatus("APPROVED");

        PropertyRequestItem item = new PropertyRequestItem();
        item.setPropertyType(activePropertyType);
        item.setQuantity(1);
        request.addItem(item);

        when(requestRepository.findWithDetailsByRequestId(32L)).thenReturn(Optional.of(request));
        when(assignmentRepository.hasActiveAssignment(500L)).thenReturn(true);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                service.fulfillRequest(32L, 500L, 1L, "GOOD", null, "manager2@example.com")
        );
        assertTrue(ex.getMessage().contains("already has an active assignment"));
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
    void cancelRequest_shouldAllowOwnerToCancelPendingRequest() {
        PropertyRequest request = new PropertyRequest();
        request.setRequestId(5L);
        request.setRequester(requesterStaff);
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
        request.setRequester(requesterStaff);
        request.setStatus("PENDING");

        when(requestRepository.findById(5L)).thenReturn(Optional.of(request));

        assertThrows(AccessDeniedException.class, () ->
                service.cancelRequest(5L, "manager1@example.com")
        );
    }

    @Test
    void cancelRequest_shouldRejectNonPendingRequest() {
        PropertyRequest request = new PropertyRequest();
        request.setRequestId(5L);
        request.setRequester(requesterStaff);
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
        request.setRequester(requesterStaff);
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
        request.setRequester(requesterStaff);
        request.setStatus("PENDING");

        when(requestRepository.findById(5L)).thenReturn(Optional.of(request));

        // Non owner forbidden
        assertThrows(AccessDeniedException.class, () ->
                service.updateRequest(5L, "manager1@example.com", 100L, 2, "Notes", null)
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
        request.setRequester(requesterStaff);
        request.setStatus("PENDING");

        when(requestRepository.findWithDetailsByRequestId(8L)).thenReturn(Optional.of(request));

        // Requester can view
        PropertyRequest viewByOwner = service.getRequestForUser(8L, "staff@example.com", false);
        assertEquals(request, viewByOwner);

        // Reviewer can view
        PropertyRequest viewByReviewer = service.getRequestForUser(8L, "manager2@example.com", true);
        assertEquals(request, viewByReviewer);

        // Unauthorized user without review role is blocked
        assertThrows(AccessDeniedException.class, () ->
                service.getRequestForUser(8L, "other@example.com", false)
        );
    }

    @Test
    void reviewRequest_shouldRequireReasonWhenRejecting() {
        PropertyRequest request = new PropertyRequest();
        request.setRequestId(9L);
        request.setRequester(requesterStaff);
        request.setStatus("PENDING");

        when(requestRepository.findById(9L)).thenReturn(Optional.of(request));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                service.reviewRequest(9L, "manager2@example.com", "REJECTED", "   ")
        );
        assertTrue(ex.getMessage().contains("rejection reason must be provided"));
    }

    @Test
    void queueQueries_shouldReturnPendingRequestsAndCount() {
        PropertyRequest p1 = new PropertyRequest();
        p1.setRequestId(1L);
        p1.setStatus("PENDING");

        when(requestRepository.findAllByStatusOrderByRequestIdDesc("PENDING")).thenReturn(List.of(p1));
        when(requestRepository.countByStatus("PENDING")).thenReturn(1L);

        assertEquals(1, service.getPendingRequests().size());
        assertEquals(1L, service.countPendingRequests());
    }
}
