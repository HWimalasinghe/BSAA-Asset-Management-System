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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PropertyServiceTest {

    private final PropertyRepository propertyRepository = mock(PropertyRepository.class);
    private final PropertyTypeRepository propertyTypeRepository = mock(PropertyTypeRepository.class);
    private final PhysicalAreaRepository physicalAreaRepository = mock(PhysicalAreaRepository.class);
    private final PropertyStatusHistoryRepository statusHistoryRepository = mock(PropertyStatusHistoryRepository.class);
    private final AppUserRepository appUserRepository = mock(AppUserRepository.class);

    private final PropertyService propertyService = new PropertyService(
            propertyRepository,
            propertyTypeRepository,
            physicalAreaRepository,
            statusHistoryRepository,
            appUserRepository
    );

    private PropertyType activeType;
    private PhysicalArea activeArea;
    private AppUser activeUser;

    @BeforeEach
    void setUp() {
        reset(propertyRepository, propertyTypeRepository, physicalAreaRepository, statusHistoryRepository, appUserRepository);

        activeType = new PropertyType();
        activeType.setPropertyTypeId(1L);
        activeType.setName("Laptop");
        activeType.setActive(true);

        activeArea = new PhysicalArea();
        activeArea.setAreaId(2L);
        activeArea.setName("Main Office");
        activeArea.setActive(true);

        activeUser = new AppUser();
        activeUser.setUserId(3L);
        activeUser.setEmail("admin@example.com");
        activeUser.setActive(true);

        when(propertyTypeRepository.findById(1L)).thenReturn(Optional.of(activeType));
        when(physicalAreaRepository.findById(2L)).thenReturn(Optional.of(activeArea));
        when(appUserRepository.findByEmail("admin@example.com")).thenReturn(Optional.of(activeUser));
        when(propertyRepository.save(any(Property.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void shouldCreateValidAssetWithGoodCondition() {
        when(propertyRepository.existsByPropertyCodeIgnoreCase("ASSET-001")).thenReturn(false);

        Property created = propertyService.createProperty(
                "ASSET-001", 1L, 2L, "SN-123", "MacBook Pro",
                LocalDate.now(), "GOOD", "admin@example.com"
        );

        assertNotNull(created);
        assertEquals("ASSET-001", created.getPropertyCode());
        assertEquals("AVAILABLE", created.getStatus());
        assertEquals("GOOD", created.getCondition());

        ArgumentCaptor<PropertyStatusHistory> captor = ArgumentCaptor.forClass(PropertyStatusHistory.class);
        verify(statusHistoryRepository).save(captor.capture());
        PropertyStatusHistory history = captor.getValue();
        assertNull(history.getOldStatus());
        assertEquals("AVAILABLE", history.getNewStatus());
        assertEquals(activeUser, history.getChangedBy());
    }

    @Test
    void shouldCreateValidAssetWithDamagedCondition() {
        when(propertyRepository.existsByPropertyCodeIgnoreCase("ASSET-002")).thenReturn(false);

        Property created = propertyService.createProperty(
                "ASSET-002", 1L, 2L, null, "Desk Chair",
                LocalDate.now(), "DAMAGED", "admin@example.com"
        );

        assertNotNull(created);
        assertEquals("DAMAGED", created.getStatus());
        assertEquals("DAMAGED", created.getCondition());

        ArgumentCaptor<PropertyStatusHistory> captor = ArgumentCaptor.forClass(PropertyStatusHistory.class);
        verify(statusHistoryRepository).save(captor.capture());
        assertEquals("DAMAGED", captor.getValue().getNewStatus());
    }

    @Test
    void shouldCreateValidAssetWithBrokenCondition() {
        when(propertyRepository.existsByPropertyCodeIgnoreCase("ASSET-003")).thenReturn(false);

        Property created = propertyService.createProperty(
                "ASSET-003", 1L, 2L, null, "Projector",
                LocalDate.now(), "BROKEN", "admin@example.com"
        );

        assertNotNull(created);
        assertEquals("UNDER_REPAIR", created.getStatus());
        assertEquals("BROKEN", created.getCondition());

        ArgumentCaptor<PropertyStatusHistory> captor = ArgumentCaptor.forClass(PropertyStatusHistory.class);
        verify(statusHistoryRepository).save(captor.capture());
        assertEquals("UNDER_REPAIR", captor.getValue().getNewStatus());
    }

    @Test
    void shouldRejectDuplicatePropertyCode() {
        when(propertyRepository.existsByPropertyCodeIgnoreCase("DUPLICATE-001")).thenReturn(true);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                propertyService.createProperty(
                        "DUPLICATE-001", 1L, 2L, null, null,
                        LocalDate.now(), "GOOD", "admin@example.com"
                )
        );

        assertEquals("A property with this code already exists.", ex.getMessage());
        verify(propertyRepository, never()).save(any());
    }

    @Test
    void shouldRejectMissingPropertyCode() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                propertyService.createProperty(
                        "   ", 1L, 2L, null, null,
                        LocalDate.now(), "GOOD", "admin@example.com"
                )
        );

        assertEquals("Property code is required.", ex.getMessage());
    }

    @Test
    void shouldRejectInactivePropertyType() {
        activeType.setActive(false);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                propertyService.createProperty(
                        "ASSET-004", 1L, 2L, null, null,
                        LocalDate.now(), "GOOD", "admin@example.com"
                )
        );

        assertEquals("Please select an active property type.", ex.getMessage());
    }

    @Test
    void shouldRejectInactiveArea() {
        activeArea.setActive(false);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                propertyService.createProperty(
                        "ASSET-005", 1L, 2L, null, null,
                        LocalDate.now(), "GOOD", "admin@example.com"
                )
        );

        assertEquals("Please select an active physical area.", ex.getMessage());
    }

    @Test
    void shouldRejectInvalidCondition() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                propertyService.createProperty(
                        "ASSET-006", 1L, 2L, null, null,
                        LocalDate.now(), "UNKNOWN", "admin@example.com"
                )
        );

        assertEquals("Invalid property condition.", ex.getMessage());
    }

    @Test
    void shouldRejectMissingPurchaseDate() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                propertyService.createProperty(
                        "ASSET-007", 1L, 2L, null, null,
                        null, "GOOD", "admin@example.com"
                )
        );

        assertEquals("Registration date is required.", ex.getMessage());
    }

    @Test
    void shouldUpdateAssetWithoutLosingUnrelatedFields() {
        Property existing = new Property();
        existing.setPropertyId(10L);
        existing.setPropertyCode("CODE-010");
        existing.setPropertyType(activeType);
        existing.setArea(activeArea);
        existing.setSerialNumber("ORIGINAL-SN");
        existing.setDescription("Original Description");
        existing.setPurchaseDate(LocalDate.of(2025, 1, 15));
        existing.setCondition("GOOD");
        existing.setStatus("ASSIGNED");

        when(propertyRepository.findById(10L)).thenReturn(Optional.of(existing));
        when(propertyRepository.existsByPropertyCodeIgnoreCaseAndPropertyIdNot("CODE-010", 10L)).thenReturn(false);

        Property updated = propertyService.updateProperty(
                10L, "CODE-010", 1L, 2L, "UPDATED-SN", "Updated Description",
                LocalDate.of(2025, 1, 15), "GOOD", "admin@example.com"
        );

        assertEquals("UPDATED-SN", updated.getSerialNumber());
        assertEquals("Updated Description", updated.getDescription());
        assertEquals("ASSIGNED", updated.getStatus()); // Status must be preserved
    }

    @Test
    void shouldPreventUnavailableAssetFromBecomingAvailableThroughOrdinaryUpdate() {
        Property damagedProperty = new Property();
        damagedProperty.setPropertyId(11L);
        damagedProperty.setPropertyCode("DAMAGED-011");
        damagedProperty.setPropertyType(activeType);
        damagedProperty.setArea(activeArea);
        damagedProperty.setPurchaseDate(LocalDate.now());
        damagedProperty.setCondition("DAMAGED");
        damagedProperty.setStatus("DAMAGED");

        when(propertyRepository.findById(11L)).thenReturn(Optional.of(damagedProperty));

        Property updated = propertyService.updateProperty(
                11L, "DAMAGED-011", 1L, 2L, null, "Updated description",
                LocalDate.now(), "DAMAGED", "admin@example.com"
        );

        assertEquals("DAMAGED", updated.getStatus());
        assertNotEquals("AVAILABLE", updated.getStatus());
    }

    @Test
    void shouldTransitionAvailableToDamagedIfConditionChangesToDamaged() {
        Property goodProperty = new Property();
        goodProperty.setPropertyId(12L);
        goodProperty.setPropertyCode("GOOD-012");
        goodProperty.setPropertyType(activeType);
        goodProperty.setArea(activeArea);
        goodProperty.setPurchaseDate(LocalDate.now());
        goodProperty.setCondition("GOOD");
        goodProperty.setStatus("AVAILABLE");

        when(propertyRepository.findById(12L)).thenReturn(Optional.of(goodProperty));

        Property updated = propertyService.updateProperty(
                12L, "GOOD-012", 1L, 2L, null, null,
                LocalDate.now(), "DAMAGED", "admin@example.com"
        );

        assertEquals("DAMAGED", updated.getStatus());
        assertEquals("DAMAGED", updated.getCondition());

        ArgumentCaptor<PropertyStatusHistory> captor = ArgumentCaptor.forClass(PropertyStatusHistory.class);
        verify(statusHistoryRepository).save(captor.capture());
        assertEquals("AVAILABLE", captor.getValue().getOldStatus());
        assertEquals("DAMAGED", captor.getValue().getNewStatus());
    }

    @Test
    void shouldRetireAvailableAssetAndLogHistory() {
        Property availableProperty = new Property();
        availableProperty.setPropertyId(13L);
        availableProperty.setStatus("AVAILABLE");

        when(propertyRepository.findById(13L)).thenReturn(Optional.of(availableProperty));

        Property retired = propertyService.retireProperty(13L, "Damaged beyond repair", "admin@example.com");

        assertEquals("RETIRED", retired.getStatus());

        ArgumentCaptor<PropertyStatusHistory> captor = ArgumentCaptor.forClass(PropertyStatusHistory.class);
        verify(statusHistoryRepository).save(captor.capture());
        assertEquals("AVAILABLE", captor.getValue().getOldStatus());
        assertEquals("RETIRED", captor.getValue().getNewStatus());
        assertEquals("Damaged beyond repair", captor.getValue().getReason());
    }

    @Test
    void shouldRejectRetiringAssignedAsset() {
        Property assignedProperty = new Property();
        assignedProperty.setPropertyId(14L);
        assignedProperty.setStatus("ASSIGNED");

        when(propertyRepository.findById(14L)).thenReturn(Optional.of(assignedProperty));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                propertyService.retireProperty(14L, "Retiring", "admin@example.com")
        );

        assertEquals("Assigned assets cannot be retired. They must be returned first.", ex.getMessage());
    }

    @Test
    void shouldRejectRetiringAlreadyRetiredAsset() {
        Property retiredProperty = new Property();
        retiredProperty.setPropertyId(15L);
        retiredProperty.setStatus("RETIRED");

        when(propertyRepository.findById(15L)).thenReturn(Optional.of(retiredProperty));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                propertyService.retireProperty(15L, "Retiring again", "admin@example.com")
        );

        assertEquals("Asset is already retired.", ex.getMessage());
    }

    @Test
    void shouldRejectTransitioningOutOfRetired() {
        Property retiredProperty = new Property();
        retiredProperty.setPropertyId(16L);
        retiredProperty.setStatus("RETIRED");
        retiredProperty.setCondition("GOOD");

        when(propertyRepository.findById(16L)).thenReturn(Optional.of(retiredProperty));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                propertyService.changeStatus(16L, "AVAILABLE", "Trying to resurrect", "admin@example.com")
        );

        assertEquals("Retired assets cannot undergo status transitions.", ex.getMessage());
    }

    @Test
    void shouldRejectTransitioningAssignedToAvailableDirectly() {
        Property assignedProperty = new Property();
        assignedProperty.setPropertyId(17L);
        assignedProperty.setStatus("ASSIGNED");
        assignedProperty.setCondition("GOOD");

        when(propertyRepository.findById(17L)).thenReturn(Optional.of(assignedProperty));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                propertyService.changeStatus(17L, "AVAILABLE", "Direct available", "admin@example.com")
        );

        assertEquals("Assigned assets cannot be marked available without being returned.", ex.getMessage());
    }

    @Test
    void shouldRejectMarkingAssetAvailableIfDamagedOrBroken() {
        Property damagedProperty = new Property();
        damagedProperty.setPropertyId(18L);
        damagedProperty.setStatus("UNDER_REPAIR");
        damagedProperty.setCondition("BROKEN");

        when(propertyRepository.findById(18L)).thenReturn(Optional.of(damagedProperty));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                propertyService.changeStatus(18L, "AVAILABLE", "Premature available", "admin@example.com")
        );

        assertEquals("Assets with damaged or broken condition cannot be marked available.", ex.getMessage());
    }

    @Test
    void shouldRejectDirectTransitionToAssigned() {
        Property availableProperty = new Property();
        availableProperty.setPropertyId(19L);
        availableProperty.setStatus("AVAILABLE");

        when(propertyRepository.findById(19L)).thenReturn(Optional.of(availableProperty));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                propertyService.changeStatus(19L, "ASSIGNED", "Bypassing assignment flow", "admin@example.com")
        );

        assertEquals("Assets can only become ASSIGNED through the assignment workflow.", ex.getMessage());
    }

    @Test
    void shouldAllowValidTransitionToUnderInspectionOrRepair() {
        Property availableProperty = new Property();
        availableProperty.setPropertyId(20L);
        availableProperty.setStatus("AVAILABLE");
        availableProperty.setCondition("GOOD");

        when(propertyRepository.findById(20L)).thenReturn(Optional.of(availableProperty));

        Property updated = propertyService.changeStatus(20L, "UNDER_INSPECTION", "Annual inspection", "admin@example.com");

        assertEquals("UNDER_INSPECTION", updated.getStatus());
        verify(statusHistoryRepository).save(any(PropertyStatusHistory.class));
    }

    @Test
    void shouldRejectInactiveUser() {
        activeUser.setActive(false);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                propertyService.createProperty(
                        "ASSET-099", 1L, 2L, null, null,
                        LocalDate.now(), "GOOD", "admin@example.com"
                )
        );

        assertEquals("Inactive users cannot perform asset operations.", ex.getMessage());
    }
}
