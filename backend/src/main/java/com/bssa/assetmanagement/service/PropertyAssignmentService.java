
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
import org.springframework.stereotype.Service;
import com.bssa.assetmanagement.entity.PropertyStatusHistory;
import com.bssa.assetmanagement.repository.PropertyStatusHistoryRepository;

@Service
public class PropertyAssignmentService {

    private final PropertyRepository propertyRepository;
    private final AppUserRepository appUserRepository;
    private final PhysicalAreaRepository physicalAreaRepository;
    private final PropertyAssignmentRepository assignmentRepository;
    private final PropertyStatusHistoryRepository statusHistoryRepository;

    public PropertyAssignmentService(
            PropertyRepository propertyRepository,
            AppUserRepository appUserRepository,
            PhysicalAreaRepository physicalAreaRepository,
            PropertyAssignmentRepository assignmentRepository,
            PropertyStatusHistoryRepository statusHistoryRepository) {
        this.propertyRepository = propertyRepository;
        this.appUserRepository = appUserRepository;
        this.physicalAreaRepository = physicalAreaRepository;
        this.assignmentRepository = assignmentRepository;
        this.statusHistoryRepository = statusHistoryRepository;
    }

    @Transactional
    public PropertyAssignment assignProperty(
            Long propertyId,
            Long employeeId,
            Long areaId,
            String conditionAtIssue,
            String notes,
            String issuerEmail) {

        Property property = propertyRepository.findById(propertyId)
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

        assignmentRepository.save(assignment);

        return assignment;
    }
}
