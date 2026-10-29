package com.bssa.assetmanagement.service;

import com.bssa.assetmanagement.entity.AppUser;
import com.bssa.assetmanagement.entity.PhysicalArea;
import com.bssa.assetmanagement.entity.Property;
import com.bssa.assetmanagement.entity.PropertyAssignment;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class PropertyRequestService {

    public static final int MAX_REQUEST_QUANTITY = 50;

    private final PropertyRequestRepository requestRepository;
    private final PropertyTypeRepository propertyTypeRepository;
    private final AppUserRepository appUserRepository;
    private final PropertyRepository propertyRepository;
    private final PhysicalAreaRepository physicalAreaRepository;
    private final PropertyAssignmentRepository assignmentRepository;
    private final PropertyStatusHistoryRepository statusHistoryRepository;
    private final NotificationService notificationService;

    public PropertyRequestService(
            PropertyRequestRepository requestRepository,
            PropertyTypeRepository propertyTypeRepository,
            AppUserRepository appUserRepository,
            PropertyRepository propertyRepository,
            PhysicalAreaRepository physicalAreaRepository,
            PropertyAssignmentRepository assignmentRepository,
            PropertyStatusHistoryRepository statusHistoryRepository) {
        this(requestRepository, propertyTypeRepository, appUserRepository, propertyRepository,
                physicalAreaRepository, assignmentRepository, statusHistoryRepository, null);
    }

    @Autowired
    public PropertyRequestService(
            PropertyRequestRepository requestRepository,
            PropertyTypeRepository propertyTypeRepository,
            AppUserRepository appUserRepository,
            PropertyRepository propertyRepository,
            PhysicalAreaRepository physicalAreaRepository,
            PropertyAssignmentRepository assignmentRepository,
            PropertyStatusHistoryRepository statusHistoryRepository,
            NotificationService notificationService) {
        this.requestRepository = requestRepository;
        this.propertyTypeRepository = propertyTypeRepository;
        this.appUserRepository = appUserRepository;
        this.propertyRepository = propertyRepository;
        this.physicalAreaRepository = physicalAreaRepository;
        this.assignmentRepository = assignmentRepository;
        this.statusHistoryRepository = statusHistoryRepository;
        this.notificationService = notificationService;
    }

    @Transactional
    public PropertyRequest createRequest(
            String requesterEmail,
            Long propertyTypeId,
            Integer quantity,
            String notes,
            String itemNotes) {

        AppUser requester = getActiveUser(requesterEmail);

        if (propertyTypeId == null) {
            throw new IllegalArgumentException("Please select a property type.");
        }

        PropertyType propertyType = propertyTypeRepository.findById(propertyTypeId)
                .orElseThrow(() -> new IllegalArgumentException("Property type not found."));

        if (!propertyType.isActive()) {
            throw new IllegalArgumentException("Please select an active property type.");
        }

        if (quantity == null || quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be greater than zero.");
        }

        if (quantity > MAX_REQUEST_QUANTITY) {
            throw new IllegalArgumentException("Quantity cannot exceed " + MAX_REQUEST_QUANTITY + " per request.");
        }

        if (notes != null && notes.trim().length() > 500) {
            throw new IllegalArgumentException("Purpose/Notes cannot exceed 500 characters.");
        }

        if (itemNotes != null && itemNotes.trim().length() > 255) {
            throw new IllegalArgumentException("Item notes cannot exceed 255 characters.");
        }

        PropertyRequest request = new PropertyRequest();
        request.setRequester(requester);
        request.setStatus("PENDING");
        request.setNotes(notes == null || notes.isBlank() ? null : notes.trim());

        PropertyRequestItem item = new PropertyRequestItem();
        item.setPropertyType(propertyType);
        item.setQuantity(quantity);
        item.setNotes(itemNotes == null || itemNotes.isBlank() ? null : itemNotes.trim());

        request.addItem(item);

        PropertyRequest saved = requestRepository.save(request);

        if (notificationService != null) {
            notificationService.notifyInventoryManagers(
                    "New Asset Request REQ-" + saved.getRequestId(),
                    "New asset request REQ-" + saved.getRequestId() + " submitted by "
                            + requester.getFirstName() + " " + requester.getLastName() + " requires review.",
                    "REQUEST_CREATED",
                    "REQUEST",
                    saved.getRequestId(),
                    requester
            );
        }

        return saved;
    }

    @Transactional
    public PropertyRequest cancelRequest(Long requestId, String userEmail) {
        PropertyRequest request = requestRepository.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("Request not found."));

        if (!request.getRequester().getEmail().equalsIgnoreCase(userEmail)) {
            throw new AccessDeniedException("You are not permitted to cancel another user's request.");
        }

        if (!"PENDING".equals(request.getStatus())) {
            throw new IllegalStateException("Only pending requests can be cancelled.");
        }

        request.setStatus("CANCELLED");
        PropertyRequest saved = requestRepository.save(request);

        if (notificationService != null) {
            notificationService.notifyInventoryManagers(
                    "Request REQ-" + saved.getRequestId() + " Cancelled",
                    "Asset request REQ-" + saved.getRequestId() + " has been cancelled by "
                            + request.getRequester().getFirstName() + " " + request.getRequester().getLastName() + ".",
                    "REQUEST_CANCELLED",
                    "REQUEST",
                    saved.getRequestId(),
                    null
            );
        }

        return saved;
    }

    @Transactional
    public PropertyRequest updateRequest(
            Long requestId,
            String requesterEmail,
            Long propertyTypeId,
            Integer quantity,
            String notes,
            String itemNotes) {

        AppUser requester = getActiveUser(requesterEmail);

        PropertyRequest request = requestRepository.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("Request not found."));

        if (!request.getRequester().getEmail().equalsIgnoreCase(requester.getEmail())) {
            throw new AccessDeniedException("You are not permitted to edit another user's request.");
        }

        if (!"PENDING".equals(request.getStatus())) {
            throw new IllegalStateException("Only pending requests can be edited.");
        }

        if (propertyTypeId == null) {
            throw new IllegalArgumentException("Please select a property type.");
        }

        PropertyType propertyType = propertyTypeRepository.findById(propertyTypeId)
                .orElseThrow(() -> new IllegalArgumentException("Property type not found."));

        if (!propertyType.isActive()) {
            throw new IllegalArgumentException("Please select an active property type.");
        }

        if (quantity == null || quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be greater than zero.");
        }

        if (quantity > MAX_REQUEST_QUANTITY) {
            throw new IllegalArgumentException("Quantity cannot exceed " + MAX_REQUEST_QUANTITY + " per request.");
        }

        if (notes != null && notes.trim().length() > 500) {
            throw new IllegalArgumentException("Purpose/Notes cannot exceed 500 characters.");
        }

        if (itemNotes != null && itemNotes.trim().length() > 255) {
            throw new IllegalArgumentException("Item notes cannot exceed 255 characters.");
        }

        request.setNotes(notes == null || notes.isBlank() ? null : notes.trim());

        if (request.getItems().isEmpty()) {
            PropertyRequestItem item = new PropertyRequestItem();
            item.setPropertyType(propertyType);
            item.setQuantity(quantity);
            item.setNotes(itemNotes == null || itemNotes.isBlank() ? null : itemNotes.trim());
            request.addItem(item);
        } else {
            PropertyRequestItem item = request.getItems().get(0);
            item.setPropertyType(propertyType);
            item.setQuantity(quantity);
            item.setNotes(itemNotes == null || itemNotes.isBlank() ? null : itemNotes.trim());
        }

        return requestRepository.save(request);
    }

    @Transactional
    public PropertyRequest reviewRequest(
            Long requestId,
            String reviewerEmail,
            String newStatus,
            String rejectionReason) {

        AppUser reviewer = getActiveUser(reviewerEmail);

        if (!isAuthorizedApprover(reviewer)) {
            throw new AccessDeniedException(
                    "Unauthorized to review asset requests. Only authorized Inventory Managers or Administrators can make request decisions.");
        }

        PropertyRequest request = requestRepository.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("Request not found."));

        if (!"PENDING".equals(request.getStatus())) {
            throw new IllegalStateException(
                    "Only requests in a pending state can be decided. Current status: " + request.getStatus());
        }

        // CRITICAL BUSINESS RULE: Self-approval prevention
        if (request.getRequester().getEmail().equalsIgnoreCase(reviewer.getEmail())
                || (request.getRequester().getUserId() != null && request.getRequester().getUserId().equals(reviewer.getUserId()))) {
            throw new IllegalStateException(
                    "Inventory Managers are not permitted to approve or reject their own requests. Another authorized Inventory Manager must make the decision.");
        }

        if (!"APPROVED".equals(newStatus) && !"REJECTED".equals(newStatus)) {
            throw new IllegalArgumentException("Review status must be either APPROVED or REJECTED.");
        }

        if ("REJECTED".equals(newStatus) && (rejectionReason == null || rejectionReason.isBlank())) {
            throw new IllegalArgumentException("A rejection reason must be provided when rejecting a request.");
        }

        request.setStatus(newStatus);
        request.setReviewedBy(reviewer);
        request.setReviewedAt(LocalDateTime.now());
        if ("REJECTED".equals(newStatus)) {
            request.setRejectionReason(rejectionReason.trim());
        } else {
            request.setRejectionReason(null);
        }

        PropertyRequest saved = requestRepository.save(request);

        if (notificationService != null) {
            if ("APPROVED".equals(newStatus)) {
                notificationService.createNotification(
                        saved.getRequester(),
                        "Request REQ-" + saved.getRequestId() + " Approved",
                        "Your request REQ-" + saved.getRequestId() + " has been approved.",
                        "REQUEST_APPROVED",
                        "REQUEST",
                        saved.getRequestId()
                );
            } else if ("REJECTED".equals(newStatus)) {
                notificationService.createNotification(
                        saved.getRequester(),
                        "Request REQ-" + saved.getRequestId() + " Rejected",
                        "Your request REQ-" + saved.getRequestId() + " was rejected. Reason: " + saved.getRejectionReason(),
                        "REQUEST_REJECTED",
                        "REQUEST",
                        saved.getRequestId()
                );
            }
        }

        return saved;
    }

    @Transactional
    public PropertyAssignment fulfillRequest(
            Long requestId,
            Long propertyId,
            Long areaId,
            String conditionAtIssue,
            String notes,
            String issuerEmail) {

        if (propertyId == null) {
            throw new IllegalArgumentException("Please select a property to allocate.");
        }

        return fulfillRequestMultiple(requestId, List.of(propertyId), areaId, conditionAtIssue, notes, issuerEmail).get(0);
    }

    @Transactional
    public List<PropertyAssignment> fulfillRequestMultiple(
            Long requestId,
            List<Long> propertyIds,
            Long areaId,
            String conditionAtIssue,
            String notes,
            String issuerEmail) {

        AppUser issuer = getActiveUser(issuerEmail);

        if (!isAuthorizedApprover(issuer)) {
            throw new AccessDeniedException(
                    "Unauthorized to fulfill asset requests. Only authorized Inventory Managers or Administrators can fulfill requests.");
        }

        PropertyRequest request = requestRepository.findWithDetailsByRequestId(requestId)
                .or(() -> requestRepository.findById(requestId))
                .orElseThrow(() -> new IllegalArgumentException("Request not found."));

        if (!"APPROVED".equals(request.getStatus())) {
            throw new IllegalStateException(
                    "Only approved requests can be fulfilled. Current status is " + request.getStatus() + ".");
        }

        if (propertyIds == null || propertyIds.isEmpty()) {
            throw new IllegalArgumentException("Please select at least one property to allocate.");
        }

        Set<Long> uniquePropertyIds = new HashSet<>(propertyIds);
        if (uniquePropertyIds.size() != propertyIds.size()) {
            throw new IllegalArgumentException("Duplicate properties selected for fulfillment.");
        }

        if (areaId == null) {
            throw new IllegalArgumentException("Please select a physical area.");
        }

        PhysicalArea area = physicalAreaRepository.findById(areaId)
                .orElseThrow(() -> new IllegalArgumentException("Physical area not found."));

        if (!area.isActive()) {
            throw new IllegalArgumentException("Please select an active physical area.");
        }

        String condition = (conditionAtIssue == null || conditionAtIssue.isBlank()) ? "GOOD" : conditionAtIssue;
        if (!"GOOD".equals(condition) && !"DAMAGED".equals(condition) && !"BROKEN".equals(condition)) {
            throw new IllegalArgumentException("Invalid condition at issue.");
        }

        PropertyType requestedType = !request.getItems().isEmpty() ? request.getItems().get(0).getPropertyType() : null;

        List<PropertyAssignment> assignments = new ArrayList<>();

        for (Long propertyId : propertyIds) {
            Property property = propertyRepository.findById(propertyId)
                    .orElseThrow(() -> new IllegalArgumentException("Property not found with ID: " + propertyId));

            if (requestedType != null && !property.getPropertyType().getPropertyTypeId().equals(requestedType.getPropertyTypeId())) {
                throw new IllegalArgumentException(
                        "Property " + property.getPropertyCode() + " does not match the requested type: " + requestedType.getName());
            }

            if (!"AVAILABLE".equals(property.getStatus())) {
                throw new IllegalArgumentException(
                        "Property " + property.getPropertyCode() + " is not available (status: " + property.getStatus() + ").");
            }

            if (!"GOOD".equals(property.getCondition())) {
                throw new IllegalArgumentException(
                        "Property " + property.getPropertyCode() + " is not in good condition (condition: " + property.getCondition() + ").");
            }

            if (assignmentRepository.hasActiveAssignment(property.getPropertyId())) {
                throw new IllegalArgumentException(
                        "Property " + property.getPropertyCode() + " already has an active assignment and cannot be allocated.");
            }

            PropertyAssignment assignment = new PropertyAssignment();
            assignment.setProperty(property);
            assignment.setEmployee(request.getRequester());
            assignment.setIssuedBy(issuer);
            assignment.setArea(area);
            assignment.setConditionAtIssue(condition);
            assignment.setNotes("Fulfilled for request REQ-" + request.getRequestId()
                    + (notes != null && !notes.isBlank() ? " - " + notes.trim() : ""));

            String oldStatus = property.getStatus();
            property.setStatus("ASSIGNED");
            propertyRepository.save(property);

            PropertyStatusHistory history = new PropertyStatusHistory();
            history.setProperty(property);
            history.setOldStatus(oldStatus);
            history.setNewStatus("ASSIGNED");
            history.setChangedBy(issuer);
            history.setReason("Fulfilled request REQ-" + request.getRequestId() + " for "
                    + request.getRequester().getFirstName() + " " + request.getRequester().getLastName());
            statusHistoryRepository.save(history);

            assignments.add(assignmentRepository.save(assignment));
        }

        request.setStatus("FULFILLED");
        requestRepository.save(request);

        if (notificationService != null) {
            notificationService.createNotification(
                    request.getRequester(),
                    "Request REQ-" + request.getRequestId() + " Fulfilled",
                    "Your request REQ-" + request.getRequestId() + " has been fulfilled with "
                            + assignments.size() + " allocated item(s).",
                    "REQUEST_FULFILLED",
                    "REQUEST",
                    request.getRequestId()
            );

            if (requestedType != null) {
                long remainingAvailable = propertyRepository.countByPropertyType_PropertyTypeIdAndStatusAndCondition(
                        requestedType.getPropertyTypeId(), "AVAILABLE", "GOOD");
                notificationService.checkLowStockAndNotify(
                        requestedType.getPropertyTypeId(), requestedType.getName(), remainingAvailable);
            }
        }

        return assignments;
    }

    @Transactional(readOnly = true)
    public PropertyRequest getRequestForUser(Long requestId, String userEmail, boolean hasReviewPrivilege) {
        PropertyRequest request = requestRepository.findWithDetailsByRequestId(requestId)
                .or(() -> requestRepository.findById(requestId))
                .orElseThrow(() -> new IllegalArgumentException("Request not found."));

        boolean isOwner = request.getRequester().getEmail().equalsIgnoreCase(userEmail);

        if (!isOwner && !hasReviewPrivilege) {
            throw new AccessDeniedException("You are not permitted to view this request.");
        }

        return request;
    }

    @Transactional(readOnly = true)
    public List<PropertyRequest> getMyRequests(String userEmail) {
        AppUser user = getActiveUser(userEmail);
        return requestRepository.findAllByRequesterOrderByRequestIdDesc(user);
    }

    /**
     * One page of every request, newest first, for the panel that reviews the
     * whole history.
     *
     * Identifiers are paginated and the rows for this page are then loaded with
     * their items, so a reviewer opening the fifth page of an old queue reads
     * twenty-five requests rather than the entire history.
     */
    @Transactional(readOnly = true)
    public Page<PropertyRequest> getAllRequestsPage(Integer page, Integer size) {
        Pageable pageRequest = PageRequest.of(
                PagingSupport.safePage(page),
                PagingSupport.safeSize(size),
                Sort.by(Sort.Direction.DESC, "requestId"));

        Page<Long> ids = requestRepository.findRequestIdPage(pageRequest);

        if (!ids.hasContent()) {
            return new PageImpl<>(List.of(), pageRequest, ids.getTotalElements());
        }

        // The rows arrive in whatever order the database chose, so they are put
        // back into the order this page was built with.
        Map<Long, PropertyRequest> byId = requestRepository
                .findWithDetailsByRequestIdIn(ids.getContent()).stream()
                .collect(Collectors.toMap(PropertyRequest::getRequestId, request -> request));

        List<PropertyRequest> rows = ids.getContent().stream()
                .map(byId::get)
                .filter(Objects::nonNull)
                .toList();

        return new PageImpl<>(rows, pageRequest, ids.getTotalElements());
    }

    @Transactional(readOnly = true)
    public List<PropertyRequest> getPendingRequests() {
        return requestRepository.findAllByStatusOrderByRequestIdDesc("PENDING");
    }

    @Transactional(readOnly = true)
    public long countPendingRequests() {
        return requestRepository.countByStatus("PENDING");
    }

    public boolean isAuthorizedApprover(AppUser user) {
        if (user == null || !user.isActive()) {
            return false;
        }
        return user.getRoles().stream()
                .map(Role::getName)
                .anyMatch(r -> r.equals("INVENTORY_MANAGER")
                        || r.equals("INVENTORY_ADMIN")
                        || r.equals("SYSTEM_ADMIN")
                        || r.equals("ROLE_INVENTORY_MANAGER")
                        || r.equals("ROLE_INVENTORY_ADMIN")
                        || r.equals("ROLE_SYSTEM_ADMIN"));
    }

    private AppUser getActiveUser(String email) {
        if (email == null) {
            throw new IllegalArgumentException("User email is required.");
        }

        AppUser user = appUserRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("User was not found."));

        if (!user.isActive()) {
            throw new IllegalArgumentException("Inactive users cannot perform request operations.");
        }

        return user;
    }
}
