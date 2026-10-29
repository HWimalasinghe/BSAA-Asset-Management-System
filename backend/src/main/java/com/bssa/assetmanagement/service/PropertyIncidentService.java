package com.bssa.assetmanagement.service;

import com.bssa.assetmanagement.entity.AppUser;
import com.bssa.assetmanagement.entity.PhysicalArea;
import com.bssa.assetmanagement.entity.Property;
import com.bssa.assetmanagement.entity.PropertyIncident;
import com.bssa.assetmanagement.entity.PropertyStatusHistory;
import com.bssa.assetmanagement.entity.Role;
import com.bssa.assetmanagement.repository.AppUserRepository;
import com.bssa.assetmanagement.repository.PhysicalAreaRepository;
import com.bssa.assetmanagement.repository.PropertyIncidentRepository;
import com.bssa.assetmanagement.repository.PropertyRepository;
import com.bssa.assetmanagement.repository.PropertyStatusHistoryRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

@Service
public class PropertyIncidentService {

    public static final Set<String> ALLOWED_INCIDENT_TYPES = Set.of("DAMAGE", "BROKEN", "LOST");
    public static final Set<String> ALLOWED_INCIDENT_STATUSES = Set.of("OPEN", "APPROVED", "REJECTED", "RESOLVED");

    private final PropertyIncidentRepository incidentRepository;
    private final PropertyRepository propertyRepository;
    private final AppUserRepository appUserRepository;
    private final PropertyStatusHistoryRepository statusHistoryRepository;
    private final PhysicalAreaRepository physicalAreaRepository;
    private final NotificationService notificationService;

    public PropertyIncidentService(
            PropertyIncidentRepository incidentRepository,
            PropertyRepository propertyRepository,
            AppUserRepository appUserRepository,
            PropertyStatusHistoryRepository statusHistoryRepository,
            PhysicalAreaRepository physicalAreaRepository) {
        this(incidentRepository, propertyRepository, appUserRepository, statusHistoryRepository,
                physicalAreaRepository, null);
    }

    @Autowired
    public PropertyIncidentService(
            PropertyIncidentRepository incidentRepository,
            PropertyRepository propertyRepository,
            AppUserRepository appUserRepository,
            PropertyStatusHistoryRepository statusHistoryRepository,
            PhysicalAreaRepository physicalAreaRepository,
            NotificationService notificationService) {
        this.incidentRepository = incidentRepository;
        this.propertyRepository = propertyRepository;
        this.appUserRepository = appUserRepository;
        this.statusHistoryRepository = statusHistoryRepository;
        this.physicalAreaRepository = physicalAreaRepository;
        this.notificationService = notificationService;
    }

    @Transactional
    public PropertyIncident reportIncident(
            Long propertyId,
            String incidentType,
            String description,
            String reporterEmail) {

        AppUser reporter = getActiveUser(reporterEmail);

        if (propertyId == null) {
            throw new IllegalArgumentException("Please select an identifiable asset.");
        }

        Property property = propertyRepository.findById(propertyId)
                .orElseThrow(() -> new IllegalArgumentException("Asset not found with ID: " + propertyId));

        if ("RETIRED".equals(property.getStatus())) {
            throw new IllegalArgumentException("Cannot report incidents on retired assets.");
        }

        if (incidentType == null || !ALLOWED_INCIDENT_TYPES.contains(incidentType.toUpperCase())) {
            throw new IllegalArgumentException("Invalid incident type. Must be DAMAGE, BROKEN, or LOST.");
        }
        String normalizedType = incidentType.toUpperCase();

        if (description == null || description.trim().isEmpty()) {
            throw new IllegalArgumentException("Incident description is required.");
        }
        String trimmedDesc = description.trim();
        if (trimmedDesc.length() > 1000) {
            throw new IllegalArgumentException("Description cannot exceed 1000 characters.");
        }

        // Prevent duplicate open incidents for the same property
        if (incidentRepository.existsByProperty_PropertyIdAndStatus(propertyId, "OPEN")) {
            throw new IllegalStateException(
                    "An open incident report is already currently active for property "
                            + property.getPropertyCode() + ".");
        }

        PropertyIncident incident = new PropertyIncident();
        incident.setProperty(property);
        incident.setReportedBy(reporter);
        incident.setIncidentType(normalizedType);
        incident.setDescription(trimmedDesc);
        incident.setStatus("OPEN");

        // Immediately update the property status and condition so it cannot be assigned
        String oldStatus = property.getStatus();
        String newStatus;

        if ("LOST".equals(normalizedType)) {
            newStatus = "LOST";
            property.setStatus("LOST");
        } else if ("DAMAGE".equals(normalizedType)) {
            property.setCondition("DAMAGED");
            if ("ASSIGNED".equals(oldStatus)) {
                newStatus = "UNDER_INSPECTION";
            } else {
                newStatus = "DAMAGED";
            }
            property.setStatus(newStatus);
        } else { // "BROKEN"
            property.setCondition("BROKEN");
            if ("ASSIGNED".equals(oldStatus)) {
                newStatus = "UNDER_INSPECTION";
            } else {
                newStatus = "UNDER_REPAIR";
            }
            property.setStatus(newStatus);
        }

        propertyRepository.save(property);

        PropertyStatusHistory history = new PropertyStatusHistory();
        history.setProperty(property);
        history.setOldStatus(oldStatus);
        history.setNewStatus(newStatus);
        history.setChangedBy(reporter);
        history.setReason("Incident reported (" + normalizedType + "): "
                + (trimmedDesc.length() > 200 ? trimmedDesc.substring(0, 197) + "..." : trimmedDesc));
        statusHistoryRepository.save(history);

        PropertyIncident saved = incidentRepository.save(incident);

        if (notificationService != null) {
            notificationService.notifyInventoryManagers(
                    "New " + normalizedType + " Incident Reported",
                    "A " + normalizedType + " incident was reported for property " + property.getPropertyCode()
                            + " by " + reporter.getFirstName() + " " + reporter.getLastName() + ".",
                    "INCIDENT_REPORTED",
                    "INCIDENT",
                    saved.getIncidentId(),
                    null
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

    @Transactional
    public PropertyIncident resolveIncident(
            Long incidentId,
            String newStatus,
            String decision,
            String actionTaken,
            String reviewerEmail) {

        AppUser reviewer = getActiveUser(reviewerEmail);

        if (!isAuthorizedManager(reviewer)) {
            throw new AccessDeniedException(
                    "Unauthorized to review or resolve incidents. Only authorized Inventory Managers or Administrators can resolve incidents.");
        }

        PropertyIncident incident = incidentRepository.findWithDetailsByIncidentId(incidentId)
                .or(() -> incidentRepository.findById(incidentId))
                .orElseThrow(() -> new IllegalArgumentException("Incident not found with ID: " + incidentId));

        if (!"OPEN".equals(incident.getStatus())) {
            throw new IllegalStateException(
                    "Only open incidents can be reviewed or resolved. Incident is currently in status: "
                            + incident.getStatus() + ".");
        }

        if (newStatus == null || (!"APPROVED".equals(newStatus) && !"REJECTED".equals(newStatus) && !"RESOLVED".equals(newStatus))) {
            throw new IllegalArgumentException("Review status must be APPROVED, REJECTED, or RESOLVED.");
        }

        if (decision == null || decision.trim().isEmpty()) {
            throw new IllegalArgumentException("Decision notes must be provided when reviewing an incident.");
        }
        String trimmedDecision = decision.trim();
        if (trimmedDecision.length() > 500) {
            throw new IllegalArgumentException("Decision notes cannot exceed 500 characters.");
        }

        String trimmedAction = actionTaken != null && !actionTaken.isBlank() ? actionTaken.trim() : null;
        if (trimmedAction != null && trimmedAction.length() > 500) {
            throw new IllegalArgumentException("Action taken cannot exceed 500 characters.");
        }

        incident.setStatus(newStatus);
        incident.setReviewedBy(reviewer);
        incident.setReviewedAt(LocalDateTime.now());
        incident.setDecision(trimmedDecision);
        incident.setActionTaken(trimmedAction);

        Property property = incident.getProperty();
        String oldStatus = property.getStatus();

        if ("REJECTED".equals(newStatus)) {
            // If the report was rejected (e.g. false report or asset checked out fine)
            if ("UNDER_INSPECTION".equals(oldStatus) || "DAMAGED".equals(oldStatus)) {
                if ("GOOD".equals(property.getCondition())) {
                    String restoredStatus = "AVAILABLE";
                    property.setStatus(restoredStatus);
                    propertyRepository.save(property);

                    PropertyStatusHistory history = new PropertyStatusHistory();
                    history.setProperty(property);
                    history.setOldStatus(oldStatus);
                    history.setNewStatus(restoredStatus);
                    history.setChangedBy(reviewer);
                    history.setReason("Incident dismissed/rejected: " + trimmedDecision);
                    statusHistoryRepository.save(history);
                }
            }
        } else {
            // APPROVED or RESOLVED
            // Update property state according to action taken if specified
            if (trimmedAction != null) {
                String actionUpper = trimmedAction.toUpperCase();
                String targetStatus = null;

                if (actionUpper.contains("REPAIR") || actionUpper.equals("UNDER_REPAIR")) {
                    targetStatus = "UNDER_REPAIR";
                } else if (actionUpper.contains("RETIRE") || actionUpper.equals("RETIRED")) {
                    targetStatus = "RETIRED";
                } else if (actionUpper.contains("LOST")) {
                    targetStatus = "LOST";
                } else if (actionUpper.contains("DAMAGED")) {
                    targetStatus = "DAMAGED";
                }

                if (targetStatus != null && !targetStatus.equals(oldStatus)) {
                    property.setStatus(targetStatus);
                    propertyRepository.save(property);

                    PropertyStatusHistory history = new PropertyStatusHistory();
                    history.setProperty(property);
                    history.setOldStatus(oldStatus);
                    history.setNewStatus(targetStatus);
                    history.setChangedBy(reviewer);
                    history.setReason("Incident " + newStatus.toLowerCase() + " - Action: " + trimmedAction);
                    statusHistoryRepository.save(history);
                }
            }
            // CRITICAL BUSINESS RULE:
            // An asset does NOT automatically return to AVAILABLE merely because the incident is closed/resolved.
            // It remains in its designated condition/status until an explicit return-to-service sign-off occurs.
        }

        PropertyIncident saved = incidentRepository.save(incident);

        if (notificationService != null) {
            notificationService.createNotification(
                    incident.getReportedBy(),
                    "Incident for " + property.getPropertyCode() + " " + newStatus,
                    "The " + incident.getIncidentType() + " incident report for property "
                            + property.getPropertyCode() + " has been " + newStatus.toLowerCase()
                            + ". Decision: " + incident.getDecision(),
                    "INCIDENT_" + newStatus,
                    "INCIDENT",
                    saved.getIncidentId()
            );
        }

        return saved;
    }

    @Transactional
    public Property returnToService(
            Long propertyId,
            Long areaId,
            String notes,
            String managerEmail) {

        AppUser manager = getActiveUser(managerEmail);

        if (!isAuthorizedManager(manager)) {
            throw new AccessDeniedException(
                    "Unauthorized to return assets to service. Only authorized Inventory Managers or Administrators can perform this action.");
        }

        Property property = propertyRepository.findById(propertyId)
                .orElseThrow(() -> new IllegalArgumentException("Asset not found with ID: " + propertyId));

        if ("RETIRED".equals(property.getStatus())) {
            throw new IllegalArgumentException("Retired assets cannot be returned to service.");
        }

        if ("ASSIGNED".equals(property.getStatus())) {
            throw new IllegalArgumentException("Assigned assets must be returned through the return workflow first.");
        }

        if ("AVAILABLE".equals(property.getStatus()) && "GOOD".equals(property.getCondition())) {
            throw new IllegalArgumentException("Asset is already available and in good condition.");
        }

        if (areaId != null) {
            PhysicalArea area = physicalAreaRepository.findById(areaId)
                    .orElseThrow(() -> new IllegalArgumentException("Physical area not found."));
            if (!area.isActive()) {
                throw new IllegalArgumentException("Please select an active physical area.");
            }
            property.setArea(area);
        }

        String oldStatus = property.getStatus();
        property.setCondition("GOOD");
        property.setStatus("AVAILABLE");

        PropertyStatusHistory history = new PropertyStatusHistory();
        history.setProperty(property);
        history.setOldStatus(oldStatus);
        history.setNewStatus("AVAILABLE");
        history.setChangedBy(manager);
        history.setReason("Asset inspected and returned to service: "
                + (notes != null && !notes.isBlank() ? notes.trim() : "Repairs complete, verified in good condition."));
        statusHistoryRepository.save(history);

        return propertyRepository.save(property);
    }

    @Transactional(readOnly = true)
    public PropertyIncident getIncidentForUser(Long incidentId, String userEmail, boolean hasReviewPrivilege) {
        PropertyIncident incident = incidentRepository.findWithDetailsByIncidentId(incidentId)
                .or(() -> incidentRepository.findById(incidentId))
                .orElseThrow(() -> new IllegalArgumentException("Incident not found with ID: " + incidentId));

        boolean isReporter = incident.getReportedBy().getEmail().equalsIgnoreCase(userEmail);

        if (!isReporter && !hasReviewPrivilege) {
            throw new AccessDeniedException("You are not permitted to view this incident report.");
        }

        return incident;
    }

    @Transactional(readOnly = true)
    public List<PropertyIncident> getMyIncidents(String userEmail) {
        AppUser user = getActiveUser(userEmail);
        return incidentRepository.findAllByReportedByOrderByIncidentIdDesc(user);
    }

    /**
     * One page of the incident history, newest first, for the panel that reviews
     * every incident ever reported.
     *
     * The order is the one the panel already used, with the identifier giving it
     * stability: incidents reported in the same instant keep their places
     * between pages.
     */
    @Transactional(readOnly = true)
    public Page<PropertyIncident> getAllIncidentsPage(Integer page, Integer size) {
        return incidentRepository.findIncidentPage(PageRequest.of(
                PagingSupport.safePage(page),
                PagingSupport.safeSize(size),
                Sort.by(Sort.Direction.DESC, "incidentId")));
    }

    @Transactional(readOnly = true)
    public List<PropertyIncident> getOpenIncidents() {
        return incidentRepository.findAllByStatusOrderByIncidentIdDesc("OPEN");
    }

    @Transactional(readOnly = true)
    public long countOpenIncidents() {
        return incidentRepository.countByStatus("OPEN");
    }

    public boolean isAuthorizedManager(AppUser user) {
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
            throw new IllegalArgumentException("Inactive users cannot perform incident operations.");
        }

        return user;
    }
}
