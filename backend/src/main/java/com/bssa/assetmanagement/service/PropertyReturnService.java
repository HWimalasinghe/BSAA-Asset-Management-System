
package com.bssa.assetmanagement.service;

import com.bssa.assetmanagement.entity.AppUser;
import com.bssa.assetmanagement.entity.Property;
import com.bssa.assetmanagement.entity.PropertyAssignment;
import com.bssa.assetmanagement.entity.PropertyIncident;
import com.bssa.assetmanagement.entity.PropertyReturn;
import com.bssa.assetmanagement.entity.PropertyStatusHistory;
import com.bssa.assetmanagement.repository.AppUserRepository;
import com.bssa.assetmanagement.repository.PropertyAssignmentRepository;
import com.bssa.assetmanagement.repository.PropertyIncidentRepository;
import com.bssa.assetmanagement.repository.PropertyRepository;
import com.bssa.assetmanagement.repository.PropertyReturnRepository;
import com.bssa.assetmanagement.repository.PropertyStatusHistoryRepository;
import jakarta.transaction.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class PropertyReturnService {

    private final PropertyAssignmentRepository assignmentRepository;
    private final PropertyReturnRepository returnRepository;
    private final PropertyRepository propertyRepository;
    private final AppUserRepository appUserRepository;
    private final PropertyStatusHistoryRepository statusHistoryRepository;
    private final PropertyIncidentRepository incidentRepository;
    private final NotificationService notificationService;

    public PropertyReturnService(
            PropertyAssignmentRepository assignmentRepository,
            PropertyReturnRepository returnRepository,
            PropertyRepository propertyRepository,
            AppUserRepository appUserRepository,
            PropertyStatusHistoryRepository statusHistoryRepository,
            PropertyIncidentRepository incidentRepository) {
        this(assignmentRepository, returnRepository, propertyRepository, appUserRepository,
                statusHistoryRepository, incidentRepository, null);
    }

    @Autowired
    public PropertyReturnService(
            PropertyAssignmentRepository assignmentRepository,
            PropertyReturnRepository returnRepository,
            PropertyRepository propertyRepository,
            AppUserRepository appUserRepository,
            PropertyStatusHistoryRepository statusHistoryRepository,
            PropertyIncidentRepository incidentRepository,
            NotificationService notificationService) {
        this.assignmentRepository = assignmentRepository;
        this.returnRepository = returnRepository;
        this.propertyRepository = propertyRepository;
        this.appUserRepository = appUserRepository;
        this.statusHistoryRepository = statusHistoryRepository;
        this.incidentRepository = incidentRepository;
        this.notificationService = notificationService;
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

        // Prevent duplicate returns — the unique constraint on assignment_id
        // is enforced at the DB level, but we give a cleaner error here.
        if (returnRepository.existsByAssignment_AssignmentId(assignmentId)) {
            throw new IllegalArgumentException(
                    "This assignment has already been returned.");
        }

        Property property = assignment.getProperty();
        String oldStatus = property.getStatus();

        // Only an actively assigned property may be returned.
        if (!"ASSIGNED".equals(property.getStatus())) {
            throw new IllegalArgumentException(
                    "Only assigned properties can be returned.");
        }

        if (!"GOOD".equals(condition)
                && !"DAMAGED".equals(condition)
                && !"BROKEN".equals(condition)) {
            throw new IllegalArgumentException(
                    "Invalid return condition. Accepted values: GOOD, DAMAGED, BROKEN.");
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

        // The employee who held the assignment is recorded as the person returning the asset.
        propertyReturn.setReturnedBy(assignment.getEmployee());

        // The logged-in inventory staff member receives and processes the return.
        propertyReturn.setReceivedBy(receiver);

        propertyReturn.setCondition(condition);
        propertyReturn.setNotes(
                notes == null || notes.isBlank() ? null : notes.trim());

        // Update the property's current condition and status in a single transaction.
        property.setCondition(condition);
        String newStatus;

        switch (condition) {
            case "GOOD" -> {
                newStatus = "AVAILABLE";
                property.setStatus(newStatus);
            }
            case "DAMAGED" -> {
                newStatus = "DAMAGED";
                property.setStatus(newStatus);
            }
            case "BROKEN" -> {
                newStatus = "UNDER_REPAIR";
                property.setStatus(newStatus);
            }
            default -> throw new IllegalArgumentException("Unexpected return condition: " + condition);
        }

        // Record the status transition in the audit history.
        PropertyStatusHistory history = new PropertyStatusHistory();
        history.setProperty(property);
        history.setOldStatus(oldStatus);
        history.setNewStatus(newStatus);
        history.setChangedBy(receiver);
        history.setReason("Property returned by "
                + assignment.getEmployee().getFirstName()
                + " " + assignment.getEmployee().getLastName()
                + " — condition at return: " + condition
                + (notes != null && !notes.isBlank() ? ". Notes: " + notes.trim() : ""));

        statusHistoryRepository.save(history);
        propertyRepository.save(property);

        // If the asset was returned in a damaged or broken condition, automatically open
        // a damage/incident report so inventory managers can review and resolve it.
        // This keeps the incident trail consistent without requiring manual follow-up.
        if ("DAMAGED".equals(condition) || "BROKEN".equals(condition)) {
            String incidentType = "BROKEN".equals(condition) ? "BROKEN" : "DAMAGE";
            String incidentDescription = "Asset returned in " + condition + " condition by "
                    + assignment.getEmployee().getFirstName() + " "
                    + assignment.getEmployee().getLastName()
                    + " (Assignment #" + assignmentId + ")."
                    + (notes != null && !notes.isBlank() ? " Return notes: " + notes.trim() : "");

            PropertyIncident incident = new PropertyIncident();
            incident.setProperty(property);
            incident.setReportedBy(receiver);
            incident.setIncidentType(incidentType);
            incident.setDescription(incidentDescription);
            incident.setStatus("OPEN");
            PropertyIncident savedIncident = incidentRepository.save(incident);

            if (notificationService != null) {
                notificationService.notifyInventoryManagers(
                        "Damaged Asset Returned: " + property.getPropertyCode(),
                        "Property " + property.getPropertyCode() + " was returned in " + condition
                                + " condition by " + assignment.getEmployee().getFirstName() + " "
                                + assignment.getEmployee().getLastName() + ". An incident report has been opened.",
                        "INCIDENT_REPORTED",
                        "INCIDENT",
                        savedIncident.getIncidentId(),
                        null
                );
            }
        }

        PropertyReturn savedReturn = returnRepository.save(propertyReturn);

        if (notificationService != null) {
            notificationService.createNotification(
                    assignment.getEmployee(),
                    "Asset " + property.getPropertyCode() + " Return Recorded",
                    "Your return for asset " + property.getPropertyCode()
                            + " has been recorded with condition " + condition + ".",
                    "ASSET_RETURNED",
                    "RETURN",
                    savedReturn.getReturnId()
            );
        }

        return savedReturn;
    }

    /**
     * Returns all return records, ordered most-recent first.
     * Used by managers and administrators.
     */
    @jakarta.transaction.Transactional(jakarta.transaction.Transactional.TxType.SUPPORTS)
    public java.util.List<PropertyReturn> findAllReturns() {
        return returnRepository.findAllByOrderByReturnIdDesc();
    }
}
