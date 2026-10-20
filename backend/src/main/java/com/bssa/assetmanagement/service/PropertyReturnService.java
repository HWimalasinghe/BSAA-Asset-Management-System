
package com.bssa.assetmanagement.service;

import com.bssa.assetmanagement.entity.AppUser;
import com.bssa.assetmanagement.entity.Property;
import com.bssa.assetmanagement.entity.PropertyAssignment;
import com.bssa.assetmanagement.entity.PropertyReturn;
import com.bssa.assetmanagement.repository.AppUserRepository;
import com.bssa.assetmanagement.repository.PropertyAssignmentRepository;
import com.bssa.assetmanagement.repository.PropertyRepository;
import com.bssa.assetmanagement.repository.PropertyReturnRepository;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;
import com.bssa.assetmanagement.entity.PropertyStatusHistory;
import com.bssa.assetmanagement.repository.PropertyStatusHistoryRepository;

@Service
public class PropertyReturnService {

    private final PropertyAssignmentRepository assignmentRepository;
    private final PropertyReturnRepository returnRepository;
    private final PropertyRepository propertyRepository;
    private final AppUserRepository appUserRepository;
    private final PropertyStatusHistoryRepository statusHistoryRepository;

    public PropertyReturnService(
            PropertyAssignmentRepository assignmentRepository,
            PropertyReturnRepository returnRepository,
            PropertyRepository propertyRepository,
            AppUserRepository appUserRepository,
            PropertyStatusHistoryRepository statusHistoryRepository) {
        this.assignmentRepository = assignmentRepository;
        this.returnRepository = returnRepository;
        this.propertyRepository = propertyRepository;
        this.appUserRepository = appUserRepository;
        this.statusHistoryRepository = statusHistoryRepository;
    }

    @Transactional
    public PropertyReturn returnProperty(
            Long assignmentId,
            String condition,
            String notes,
            String receiverEmail) {

        PropertyAssignment assignment = assignmentRepository.findById(assignmentId)
                .orElseThrow(() ->
                        new IllegalArgumentException("Assignment not found."));

        if (returnRepository.existsByAssignment_AssignmentId(assignmentId)) {
            throw new IllegalArgumentException(
                    "This assignment has already been returned.");
        }

        Property property = assignment.getProperty();
        String oldStatus = property.getStatus();

        if (!"ASSIGNED".equals(property.getStatus())) {
            throw new IllegalArgumentException(
                    "Only assigned properties can be returned.");
        }

        if (!"GOOD".equals(condition)
                && !"DAMAGED".equals(condition)
                && !"BROKEN".equals(condition)) {
            throw new IllegalArgumentException(
                    "Invalid return condition.");
        }

        AppUser receiver = appUserRepository.findByEmail(receiverEmail)
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "Logged-in user was not found."));

        if (!receiver.isActive()) {
            throw new IllegalArgumentException(
                    "Inactive users cannot receive returned properties.");
        }

        PropertyReturn propertyReturn = new PropertyReturn();
        propertyReturn.setAssignment(assignment);

        // The employee assigned the property is recorded as returning it.
        propertyReturn.setReturnedBy(assignment.getEmployee());

        // The logged-in staff member receives the property.
        propertyReturn.setReceivedBy(receiver);

        propertyReturn.setCondition(condition);
        propertyReturn.setNotes(
                notes == null || notes.isBlank() ? null : notes.trim());

        // Update the property's current condition and status.
        property.setCondition(condition);

        switch (condition) {
            case "GOOD" ->
                    property.setStatus("AVAILABLE");
            case "DAMAGED" ->
                    property.setStatus("DAMAGED");
            case "BROKEN" ->
                    property.setStatus("UNDER_REPAIR");
        }
        PropertyStatusHistory history = new PropertyStatusHistory();
        history.setProperty(property);
        history.setOldStatus(oldStatus);
        history.setNewStatus(property.getStatus());
        history.setChangedBy(receiver);
        history.setReason("Property returned by "
                + assignment.getEmployee().getFirstName()
                + " " + assignment.getEmployee().getLastName());

        statusHistoryRepository.save(history);

        propertyRepository.save(property);

        return returnRepository.save(propertyReturn);
    }
}
