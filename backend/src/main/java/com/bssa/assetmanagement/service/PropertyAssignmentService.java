
package com.bssa.assetmanagement.service;

import com.bssa.assetmanagement.entity.AppUser;
import com.bssa.assetmanagement.entity.PhysicalArea;
import com.bssa.assetmanagement.entity.Property;
import com.bssa.assetmanagement.entity.PropertyAssignment;
import com.bssa.assetmanagement.repository.AppUserRepository;
import com.bssa.assetmanagement.repository.PhysicalAreaRepository;
import com.bssa.assetmanagement.repository.PropertyAssignmentRepository;
import com.bssa.assetmanagement.repository.PropertyRepository;
import jakarta.transaction.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.bssa.assetmanagement.entity.PropertyStatusHistory;
import com.bssa.assetmanagement.repository.PropertyStatusHistoryRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Map;

@Service
public class PropertyAssignmentService {

    /**
     * Columns the assignment log may be ordered by, as the sort keys the page
     * uses mapped to the properties they order by. Only these reach the query,
     * so the address bar cannot sort by an unmapped column.
     */
    public static final Map<String, String> ASSIGNMENT_LOG_SORT_OPTIONS = Map.of(
            "issued", "assignmentId",
            "code", "property.propertyCode",
            "holder", "employee.lastName",
            "area", "area.name",
            "condition", "conditionAtIssue");

    /** Unique key appended to every order so equal rows keep their place across pages. */
    public static final String ASSIGNMENT_LOG_TIE_BREAKER = "assignmentId";
    public static final String DEFAULT_ASSIGNMENT_LOG_SORT = "issued";

    /** Whether the asset is still with its holder, which is how the log is narrowed. */
    public static final List<String> HOLDING_OPTIONS = List.of("HELD", "RETURNED");


    private final PropertyRepository propertyRepository;
    private final AppUserRepository appUserRepository;
    private final PhysicalAreaRepository physicalAreaRepository;
    private final PropertyAssignmentRepository assignmentRepository;
    private final PropertyStatusHistoryRepository statusHistoryRepository;
    private final NotificationService notificationService;

    public PropertyAssignmentService(
            PropertyRepository propertyRepository,
            AppUserRepository appUserRepository,
            PhysicalAreaRepository physicalAreaRepository,
            PropertyAssignmentRepository assignmentRepository,
            PropertyStatusHistoryRepository statusHistoryRepository) {
        this(propertyRepository, appUserRepository, physicalAreaRepository, assignmentRepository,
                statusHistoryRepository, null);
    }

    @Autowired
    public PropertyAssignmentService(
            PropertyRepository propertyRepository,
            AppUserRepository appUserRepository,
            PhysicalAreaRepository physicalAreaRepository,
            PropertyAssignmentRepository assignmentRepository,
            PropertyStatusHistoryRepository statusHistoryRepository,
            NotificationService notificationService) {
        this.propertyRepository = propertyRepository;
        this.appUserRepository = appUserRepository;
        this.physicalAreaRepository = physicalAreaRepository;
        this.assignmentRepository = assignmentRepository;
        this.statusHistoryRepository = statusHistoryRepository;
        this.notificationService = notificationService;
    }

    @Transactional
    public PropertyAssignment assignProperty(
            Long propertyId,
            Long employeeId,
            Long areaId,
            String conditionAtIssue,
            String notes,
            String issuerEmail) {

        // Read under a write lock: everything below decides from this row, and
        // a second issue of the same asset must not be able to read it as still
        // available while this transaction is working on it.
        Property property = propertyRepository.findByPropertyIdForUpdate(propertyId)
                .orElseThrow(() ->
                        new IllegalArgumentException("Property not found."));

        if (!"AVAILABLE".equals(property.getStatus()) || !"GOOD".equals(property.getCondition())) {
            throw new IllegalArgumentException(
                    "Only available properties in good condition can be assigned.");
        }

        if (assignmentRepository.hasActiveAssignment(propertyId)) {
            throw new IllegalArgumentException(
                    "Property already has an active assignment and cannot be assigned again.");
        }

        if (!"GOOD".equals(conditionAtIssue)
                && !"DAMAGED".equals(conditionAtIssue)
                && !"BROKEN".equals(conditionAtIssue)) {
            throw new IllegalArgumentException(
                    "Invalid condition at issue.");
        }

        AppUser employee = appUserRepository.findById(employeeId)
                .orElseThrow(() ->
                        new IllegalArgumentException("Employee not found."));

        if (!employee.isActive()) {
            throw new IllegalArgumentException(
                    "Cannot assign a property to an inactive employee.");
        }

        AppUser issuer = appUserRepository.findByEmail(issuerEmail)
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "Logged-in user was not found."));

        if (!issuer.isActive()) {
            throw new IllegalArgumentException(
                    "Inactive users cannot issue properties.");
        }

        PhysicalArea area = physicalAreaRepository.findById(areaId)
                .orElseThrow(() ->
                        new IllegalArgumentException("Physical area not found."));

        if (!area.isActive()) {
            throw new IllegalArgumentException(
                    "Please select an active physical area.");
        }

        PropertyAssignment assignment = new PropertyAssignment();
        assignment.setProperty(property);
        assignment.setEmployee(employee);
        assignment.setIssuedBy(issuer);
        assignment.setArea(area);
        assignment.setConditionAtIssue(conditionAtIssue);
        assignment.setNotes(
                notes == null || notes.isBlank() ? null : notes.trim());

        String oldStatus = property.getStatus();

        property.setStatus("ASSIGNED");

        PropertyStatusHistory history = new PropertyStatusHistory();
        history.setProperty(property);
        history.setOldStatus(oldStatus);
        history.setNewStatus("ASSIGNED");
        history.setChangedBy(issuer);
        history.setReason("Property assigned to " + employee.getFirstName()
                + " " + employee.getLastName());

        statusHistoryRepository.save(history);

        PropertyAssignment saved = assignmentRepository.save(assignment);

        if (notificationService != null) {
            notificationService.createNotification(
                    employee,
                    "Asset " + property.getPropertyCode() + " Assigned",
                    "Asset " + property.getPropertyCode() + " (" + property.getPropertyType().getName()
                            + ") has been assigned to you at area " + area.getName() + ".",
                    "ASSET_ASSIGNED",
                    "ASSIGNMENT",
                    saved.getAssignmentId()
            );

            long remainingAvailable = propertyRepository.countByPropertyType_PropertyTypeIdAndStatusAndCondition(
                    property.getPropertyType().getPropertyTypeId(), "AVAILABLE", "GOOD");
            notificationService.checkLowStockAndNotify(
                    property.getPropertyType().getPropertyTypeId(),
                    property.getPropertyType().getName(),
                    remainingAvailable
            );
        }

        return saved;
    }

    /**
     * One page of the assignment log, searched and filtered, in a stable order.
     *
     * The role rules that already keep this page away from staff who may not
     * read it are applied before the controller calls this; the search adds no
     * columns beyond the ones the log lists, so a paged search cannot reach data
     * the page would not have shown anyway.
     */
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public Page<PropertyAssignment> searchAssignments(
            String search,
            Long areaId,
            String holding,
            Integer page,
            Integer size,
            String sortKey,
            String sortDirection) {

        Pageable pageable = PagingSupport.pageRequest(
                page, size, sortKey, sortDirection,
                ASSIGNMENT_LOG_SORT_OPTIONS, DEFAULT_ASSIGNMENT_LOG_SORT, ASSIGNMENT_LOG_TIE_BREAKER);

        String term = PagingSupport.normalizedSearch(search);
        String holdingFilter = acceptedHolding(holding);

        if (holdingFilter == null && holding != null && !holding.isBlank()) {
            throw new IllegalArgumentException("Unrecognised holding filter: " + holding);
        }

        return assignmentRepository.findAssignmentPage(
                term,
                term == null ? null : PagingSupport.likePattern(term),
                areaId == null || areaId < 0 ? null : areaId,
                holdingFilter,
                pageable);
    }

    /**
     * The holding filter as the log understands it, or null when it is blank or
     * unknown, so a rejected value is never echoed back into a link.
     */
    public static String acceptedHolding(String holding) {
        String value = holding == null || holding.isBlank()
                ? null
                : holding.trim().toUpperCase();
        return value != null && HOLDING_OPTIONS.contains(value) ? value : null;
    }
}
