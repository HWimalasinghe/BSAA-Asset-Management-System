package com.bssa.assetmanagement.service;

import com.bssa.assetmanagement.entity.AppUser;
import com.bssa.assetmanagement.entity.PhysicalArea;
import com.bssa.assetmanagement.entity.Property;
import com.bssa.assetmanagement.entity.PropertyStatusHistory;
import com.bssa.assetmanagement.entity.PropertyType;
import com.bssa.assetmanagement.repository.AppUserRepository;
import com.bssa.assetmanagement.repository.PhysicalAreaRepository;
import com.bssa.assetmanagement.repository.PropertyRepository;
import com.bssa.assetmanagement.repository.PropertyStatusHistoryRepository;
import com.bssa.assetmanagement.repository.PropertyTypeRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class PropertyService {

    public static final Set<String> ALLOWED_CONDITIONS = Set.of("GOOD", "DAMAGED", "BROKEN");

    public static final Set<String> ALLOWED_STATUSES = Set.of(
            "AVAILABLE",
            "ASSIGNED",
            "UNDER_INSPECTION",
            "DAMAGED",
            "UNDER_REPAIR",
            "LOST",
            "RETIRED"
    );

    /**
     * Sort keys offered by the asset register, keyed by the names used in the
     * page's header links. Only these reach the query; the primary key is
     * appended as a tiebreaker by {@link PagingSupport} so that rows sharing a
     * value keep the same relative order from page to page.
     */
    public static final Map<String, String> ASSET_REGISTER_SORT_OPTIONS = Map.of(
            "code", "propertyCode",
            "type", "propertyType.name",
            "category", "propertyType.category.name",
            "area", "area.name",
            "serial", "serialNumber",
            "status", "status",
            "condition", "condition",
            "purchaseDate", "purchaseDate",
            "registered", "propertyId"
    );

    public static final String ASSET_REGISTER_TIE_BREAKER = "propertyId";

    /** Newest registration first, which is what the register showed before paging. */
    public static final String DEFAULT_ASSET_REGISTER_SORT = "registered";

    /** Longest search phrase accepted; a longer one is a mistake, not a use case. */
    public static final int MAX_SEARCH_LENGTH = PagingSupport.MAX_SEARCH_LENGTH;

    private final PropertyRepository propertyRepository;
    private final PropertyTypeRepository propertyTypeRepository;
    private final PhysicalAreaRepository physicalAreaRepository;
    private final PropertyStatusHistoryRepository statusHistoryRepository;
    private final AppUserRepository appUserRepository;

    public PropertyService(
            PropertyRepository propertyRepository,
            PropertyTypeRepository propertyTypeRepository,
            PhysicalAreaRepository physicalAreaRepository,
            PropertyStatusHistoryRepository statusHistoryRepository,
            AppUserRepository appUserRepository) {
        this.propertyRepository = propertyRepository;
        this.propertyTypeRepository = propertyTypeRepository;
        this.physicalAreaRepository = physicalAreaRepository;
        this.statusHistoryRepository = statusHistoryRepository;
        this.appUserRepository = appUserRepository;
    }

    @Transactional
    public Property createProperty(
            String propertyCode,
            Long propertyTypeId,
            Long areaId,
            String serialNumber,
            String description,
            LocalDate purchaseDate,
            String condition,
            String creatorEmail) {

        if (propertyCode == null || propertyCode.trim().isEmpty()) {
            throw new IllegalArgumentException("Property code is required.");
        }

        String trimmedCode = propertyCode.trim();
        if (trimmedCode.length() > 50) {
            throw new IllegalArgumentException("Property code cannot exceed 50 characters.");
        }

        if (propertyRepository.existsByPropertyCodeIgnoreCase(trimmedCode)) {
            throw new IllegalArgumentException("A property with this code already exists.");
        }

        if (condition == null || !ALLOWED_CONDITIONS.contains(condition)) {
            throw new IllegalArgumentException("Invalid property condition.");
        }

        if (purchaseDate == null) {
            throw new IllegalArgumentException("Registration date is required.");
        }

        if (propertyTypeId == null) {
            throw new IllegalArgumentException("Please select a property type.");
        }

        PropertyType propertyType = propertyTypeRepository.findById(propertyTypeId)
                .orElseThrow(() -> new IllegalArgumentException("Property type not found."));

        if (!propertyType.isActive()) {
            throw new IllegalArgumentException("Please select an active property type.");
        }

        if (areaId == null) {
            throw new IllegalArgumentException("Please select a physical area.");
        }

        PhysicalArea area = physicalAreaRepository.findById(areaId)
                .orElseThrow(() -> new IllegalArgumentException("Physical area not found."));

        if (!area.isActive()) {
            throw new IllegalArgumentException("Please select an active physical area.");
        }

        AppUser creator = getActiveUser(creatorEmail);

        // Determine initial status based on physical condition
        String initialStatus;
        if ("GOOD".equals(condition)) {
            initialStatus = "AVAILABLE";
        } else if ("DAMAGED".equals(condition)) {
            initialStatus = "DAMAGED";
        } else {
            initialStatus = "UNDER_REPAIR";
        }

        Property property = new Property();
        property.setPropertyCode(trimmedCode);
        property.setPropertyType(propertyType);
        property.setArea(area);
        property.setSerialNumber(serialNumber == null || serialNumber.isBlank() ? null : serialNumber.trim());
        property.setDescription(description == null || description.isBlank() ? null : description.trim());
        property.setPurchaseDate(purchaseDate);
        property.setCondition(condition);
        property.setStatus(initialStatus);

        Property savedProperty = propertyRepository.save(property);

        // Record initial status history
        PropertyStatusHistory history = new PropertyStatusHistory();
        history.setProperty(savedProperty);
        history.setOldStatus(null);
        history.setNewStatus(initialStatus);
        history.setChangedBy(creator);
        history.setReason("Initial asset registration");
        statusHistoryRepository.save(history);

        return savedProperty;
    }

    @Transactional
    public Property updateProperty(
            Long id,
            String propertyCode,
            Long propertyTypeId,
            Long areaId,
            String serialNumber,
            String description,
            LocalDate purchaseDate,
            String condition,
            String updaterEmail) {

        Property property = propertyRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Property not found."));

        if (propertyCode == null || propertyCode.trim().isEmpty()) {
            throw new IllegalArgumentException("Property code is required.");
        }

        String trimmedCode = propertyCode.trim();
        if (trimmedCode.length() > 50) {
            throw new IllegalArgumentException("Property code cannot exceed 50 characters.");
        }

        if (propertyRepository.existsByPropertyCodeIgnoreCaseAndPropertyIdNot(trimmedCode, id)) {
            throw new IllegalArgumentException("Another property already uses this code.");
        }

        if (condition == null || !ALLOWED_CONDITIONS.contains(condition)) {
            throw new IllegalArgumentException("Invalid property condition.");
        }

        if (purchaseDate == null) {
            throw new IllegalArgumentException("Registration date is required.");
        }

        if (propertyTypeId == null) {
            throw new IllegalArgumentException("Please select a property type.");
        }

        PropertyType propertyType = propertyTypeRepository.findById(propertyTypeId)
                .orElseThrow(() -> new IllegalArgumentException("Property type not found."));

        if (!propertyType.isActive() && !propertyType.getPropertyTypeId().equals(property.getPropertyType().getPropertyTypeId())) {
            throw new IllegalArgumentException("Please select an active property type.");
        }

        if (areaId == null) {
            throw new IllegalArgumentException("Please select a physical area.");
        }

        PhysicalArea area = physicalAreaRepository.findById(areaId)
                .orElseThrow(() -> new IllegalArgumentException("Physical area not found."));

        if (!area.isActive() && !area.getAreaId().equals(property.getArea().getAreaId())) {
            throw new IllegalArgumentException("Please select an active physical area.");
        }

        AppUser updater = getActiveUser(updaterEmail);

        property.setPropertyCode(trimmedCode);
        property.setPropertyType(propertyType);
        property.setArea(area);
        property.setSerialNumber(serialNumber == null || serialNumber.isBlank() ? null : serialNumber.trim());
        property.setDescription(description == null || description.isBlank() ? null : description.trim());
        property.setPurchaseDate(purchaseDate);

        String previousCondition = property.getCondition();
        property.setCondition(condition);

        // Safeguard: Prevent unavailable assets from being marked assignable through an ordinary update.
        // If an asset was AVAILABLE and its condition deteriorated to DAMAGED or BROKEN, transition status accordingly.
        if ("AVAILABLE".equals(property.getStatus()) && !"GOOD".equals(condition)) {
            String oldStatus = property.getStatus();
            String newStatus = "DAMAGED".equals(condition) ? "DAMAGED" : "UNDER_REPAIR";
            property.setStatus(newStatus);

            PropertyStatusHistory history = new PropertyStatusHistory();
            history.setProperty(property);
            history.setOldStatus(oldStatus);
            history.setNewStatus(newStatus);
            history.setChangedBy(updater);
            history.setReason("Asset condition changed from " + previousCondition + " to " + condition);
            statusHistoryRepository.save(history);
        }

        return propertyRepository.save(property);
    }

    @Transactional
    public Property retireProperty(Long id, String reason, String updaterEmail) {
        Property property = propertyRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Property not found."));

        if ("RETIRED".equals(property.getStatus())) {
            throw new IllegalArgumentException("Asset is already retired.");
        }

        if ("ASSIGNED".equals(property.getStatus())) {
            throw new IllegalArgumentException("Assigned assets cannot be retired. They must be returned first.");
        }

        AppUser updater = getActiveUser(updaterEmail);

        String oldStatus = property.getStatus();
        property.setStatus("RETIRED");

        PropertyStatusHistory history = new PropertyStatusHistory();
        history.setProperty(property);
        history.setOldStatus(oldStatus);
        history.setNewStatus("RETIRED");
        history.setChangedBy(updater);
        history.setReason(reason == null || reason.isBlank() ? "Asset retired" : reason.trim());
        statusHistoryRepository.save(history);

        return propertyRepository.save(property);
    }

    @Transactional
    public Property changeStatus(Long id, String newStatus, String reason, String updaterEmail) {
        Property property = propertyRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Property not found."));

        if (newStatus == null || !ALLOWED_STATUSES.contains(newStatus)) {
            throw new IllegalArgumentException("Invalid asset status: " + newStatus);
        }

        String currentStatus = property.getStatus();
        if (currentStatus.equals(newStatus)) {
            return property;
        }

        // Validate lifecycle transitions
        validateStatusTransition(property, newStatus);

        AppUser updater = getActiveUser(updaterEmail);

        property.setStatus(newStatus);

        PropertyStatusHistory history = new PropertyStatusHistory();
        history.setProperty(property);
        history.setOldStatus(currentStatus);
        history.setNewStatus(newStatus);
        history.setChangedBy(updater);
        history.setReason(reason == null || reason.isBlank() ? "Status changed to " + newStatus : reason.trim());
        statusHistoryRepository.save(history);

        return propertyRepository.save(property);
    }

    public List<PropertyStatusHistory> getStatusHistory(Long propertyId) {
        return statusHistoryRepository.findAllByProperty_PropertyIdOrderByHistoryIdDesc(propertyId);
    }

    /**
     * One page of the asset register, narrowed by free-text search and any
     * combination of filters. Search text, filter values, page number, page
     * size and sort order are all validated here so that nothing typed into
     * the address bar reaches the query unchecked.
     *
     * @throws IllegalArgumentException if a filter value is not recognised or
     *                                  the date range is reversed
     */
    @Transactional(readOnly = true)
    public Page<Property> searchProperties(
            String search,
            String status,
            String condition,
            Long categoryId,
            Long propertyTypeId,
            Long areaId,
            LocalDate purchaseFrom,
            LocalDate purchaseTo,
            Integer page,
            Integer size,
            String sortKey,
            String sortDirection) {

        Pageable pageable = PagingSupport.pageRequest(
                page, size, sortKey, sortDirection,
                ASSET_REGISTER_SORT_OPTIONS, DEFAULT_ASSET_REGISTER_SORT, ASSET_REGISTER_TIE_BREAKER);

        return queryRegister(search, status, condition, categoryId, propertyTypeId,
                areaId, purchaseFrom, purchaseTo, pageable);
    }

    /**
     * Every row matching the same search and filters, in the same order as the
     * list. Used by the CSV export, which must contain exactly what the
     * filtered list shows rather than one page of it.
     */
    @Transactional(readOnly = true)
    public List<Property> findMatchingProperties(
            String search,
            String status,
            String condition,
            Long categoryId,
            Long propertyTypeId,
            Long areaId,
            LocalDate purchaseFrom,
            LocalDate purchaseTo,
            String sortKey,
            String sortDirection) {

        Pageable pageable = Pageable.unpaged(PagingSupport.sort(
                sortKey, sortDirection,
                ASSET_REGISTER_SORT_OPTIONS, DEFAULT_ASSET_REGISTER_SORT, ASSET_REGISTER_TIE_BREAKER));

        return queryRegister(search, status, condition, categoryId, propertyTypeId,
                areaId, purchaseFrom, purchaseTo, pageable).getContent();
    }

    private Page<Property> queryRegister(
            String search,
            String status,
            String condition,
            Long categoryId,
            Long propertyTypeId,
            Long areaId,
            LocalDate purchaseFrom,
            LocalDate purchaseTo,
            Pageable pageable) {

        String term = PagingSupport.normalizedSearch(search, MAX_SEARCH_LENGTH);
        String statusFilter = acceptedStatus(status);
        String conditionFilter = acceptedCondition(condition);

        if (statusFilter == null && status != null && !status.isBlank()) {
            throw new IllegalArgumentException("Unrecognised status filter: " + status);
        }
        if (conditionFilter == null && condition != null && !condition.isBlank()) {
            throw new IllegalArgumentException("Unrecognised condition filter: " + condition);
        }
        if (purchaseFrom != null && purchaseTo != null && purchaseFrom.isAfter(purchaseTo)) {
            throw new IllegalArgumentException(
                    "The start date must not be after the end date.");
        }

        return propertyRepository.findFilterPage(
                term,
                term == null ? null : PagingSupport.likePattern(term),
                statusFilter,
                conditionFilter,
                positiveId(categoryId),
                positiveId(propertyTypeId),
                positiveId(areaId),
                purchaseFrom,
                purchaseTo,
                pageable);
    }

    /**
     * Filter values arrive from the address bar, where case is easy to get
     * wrong; the query vocabulary itself is always upper-case.
     */
    private static String upperBound(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase();
    }

    /**
     * The status as the register understands it, or null when it is blank or
     * not one of the known statuses. Used to build the links a page hands back
     * so that a rejected value is never echoed into them, and the query itself
     * only ever sees a value from ALLOWED_STATUSES.
     */
    public static String acceptedStatus(String status) {
        String value = upperBound(status);
        return value != null && ALLOWED_STATUSES.contains(value) ? value : null;
    }

    /** As {@link #acceptedStatus(String)}, for the condition vocabulary. */
    public static String acceptedCondition(String condition) {
        String value = upperBound(condition);
        return value != null && ALLOWED_CONDITIONS.contains(value) ? value : null;
    }

    /** Negative identifiers cannot exist, so they mean "no filter". */
    private static Long positiveId(Long id) {
        return id == null || id < 0 ? null : id;
    }

    private void validateStatusTransition(Property property, String newStatus) {
        String currentStatus = property.getStatus();

        // Terminal state: RETIRED assets cannot be moved to any other status
        if ("RETIRED".equals(currentStatus)) {
            throw new IllegalArgumentException("Retired assets cannot undergo status transitions.");
        }

        // ASSIGNED assets cannot be arbitrarily marked available or retired without going through returns
        if ("ASSIGNED".equals(currentStatus)) {
            if ("AVAILABLE".equals(newStatus)) {
                throw new IllegalArgumentException("Assigned assets cannot be marked available without being returned.");
            }
            if ("RETIRED".equals(newStatus)) {
                throw new IllegalArgumentException("Assigned assets cannot be retired. They must be returned first.");
            }
        }

        // Cannot transition directly to ASSIGNED via simple status update; must use assignment workflow
        if ("ASSIGNED".equals(newStatus)) {
            throw new IllegalArgumentException("Assets can only become ASSIGNED through the assignment workflow.");
        }

        // Assets cannot be marked AVAILABLE if condition is DAMAGED or BROKEN
        if ("AVAILABLE".equals(newStatus) && !"GOOD".equals(property.getCondition())) {
            throw new IllegalArgumentException("Assets with damaged or broken condition cannot be marked available.");
        }
    }

    private AppUser getActiveUser(String email) {
        if (email == null) {
            throw new IllegalArgumentException("User email is required.");
        }
        AppUser user = appUserRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("Logged-in user was not found."));

        if (!user.isActive()) {
            throw new IllegalArgumentException("Inactive users cannot perform asset operations.");
        }
        return user;
    }
}
