package com.bssa.assetmanagement.service;

import com.bssa.assetmanagement.entity.AppUser;
import com.bssa.assetmanagement.entity.PhysicalArea;
import com.bssa.assetmanagement.entity.Property;
import com.bssa.assetmanagement.entity.PropertyAssignment;
import com.bssa.assetmanagement.entity.PropertyCategory;
import com.bssa.assetmanagement.entity.PropertyStatusHistory;
import com.bssa.assetmanagement.entity.PropertyType;
import com.bssa.assetmanagement.entity.Role;
import com.bssa.assetmanagement.repository.AppUserRepository;
import com.bssa.assetmanagement.repository.PhysicalAreaRepository;
import com.bssa.assetmanagement.repository.PropertyAssignmentRepository;
import com.bssa.assetmanagement.repository.PropertyRepository;
import com.bssa.assetmanagement.repository.PropertyStatusHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PropertyAssignmentServiceTest {

    private final PropertyRepository propertyRepository = mock(PropertyRepository.class);
    private final AppUserRepository appUserRepository = mock(AppUserRepository.class);
    private final PhysicalAreaRepository physicalAreaRepository = mock(PhysicalAreaRepository.class);
    private final PropertyAssignmentRepository assignmentRepository = mock(PropertyAssignmentRepository.class);
    private final PropertyStatusHistoryRepository statusHistoryRepository = mock(PropertyStatusHistoryRepository.class);

    private final PropertyAssignmentService service = new PropertyAssignmentService(
            propertyRepository,
            appUserRepository,
            physicalAreaRepository,
            assignmentRepository,
            statusHistoryRepository
    );

    private AppUser managerUser;
    private AppUser employeeUser;
    private Property availableProperty;
    private PhysicalArea activeArea;

    @BeforeEach
    void setUp() {
        reset(propertyRepository, appUserRepository, physicalAreaRepository,
                assignmentRepository, statusHistoryRepository);

        Role managerRole = new Role();
        managerRole.setRoleId(1L);
        managerRole.setName("INVENTORY_MANAGER");

        Role staffRole = new Role();
        staffRole.setRoleId(2L);
        staffRole.setName("OFFICE_STAFF");

        managerUser = new AppUser();
        managerUser.setUserId(1L);
        managerUser.setEmail("manager@example.com");
        managerUser.setFirstName("Inv");
        managerUser.setLastName("Manager");
        managerUser.setActive(true);
        managerUser.setRoles(Set.of(managerRole));

        employeeUser = new AppUser();
        employeeUser.setUserId(2L);
        employeeUser.setEmail("employee@example.com");
        employeeUser.setFirstName("Jane");
        employeeUser.setLastName("Smith");
        employeeUser.setActive(true);
        employeeUser.setRoles(Set.of(staffRole));

        PropertyCategory category = new PropertyCategory();
        category.setCategoryId(1L);
        category.setName("General");

        PropertyType propertyType = new PropertyType();
        propertyType.setPropertyTypeId(1L);
        propertyType.setName("Chair");
        propertyType.setCategory(category);

        activeArea = new PhysicalArea();
        activeArea.setAreaId(5L);
        activeArea.setName("Office A");
        activeArea.setActive(true);

        availableProperty = new Property();
        availableProperty.setPropertyId(50L);
        availableProperty.setPropertyCode("PROP-050");
        availableProperty.setDescription("Executive Chair");
        availableProperty.setStatus("AVAILABLE");
        availableProperty.setCondition("GOOD");
        availableProperty.setPropertyType(propertyType);
        availableProperty.setArea(activeArea);

        when(propertyRepository.findById(50L)).thenReturn(Optional.of(availableProperty));
        when(appUserRepository.findById(2L)).thenReturn(Optional.of(employeeUser));
        when(appUserRepository.findByEmail("manager@example.com")).thenReturn(Optional.of(managerUser));
        when(physicalAreaRepository.findById(5L)).thenReturn(Optional.of(activeArea));
        when(assignmentRepository.hasActiveAssignment(50L)).thenReturn(false);
        when(assignmentRepository.save(any(PropertyAssignment.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    // -------------------------------------------------------------------------
    // Happy paths
    // -------------------------------------------------------------------------

    @Test
    void assignProperty_validInputs_createsAssignmentAndSetsStatusToAssigned() {
        PropertyAssignment result = service.assignProperty(
                50L, 2L, 5L, "GOOD", "Ergonomic issue", "manager@example.com");

        assertNotNull(result);
        assertEquals(availableProperty, result.getProperty());
        assertEquals(employeeUser, result.getEmployee());
        assertEquals(managerUser, result.getIssuedBy());
        assertEquals("GOOD", result.getConditionAtIssue());
        assertEquals("Ergonomic issue", result.getNotes());

        assertEquals("ASSIGNED", availableProperty.getStatus());
        verify(assignmentRepository).save(result);
        verify(statusHistoryRepository).save(any(PropertyStatusHistory.class));
    }

    @Test
    void assignProperty_historyRecordsOldAndNewStatus() {
        service.assignProperty(50L, 2L, 5L, "GOOD", null, "manager@example.com");

        verify(statusHistoryRepository).save(argThat(h ->
                "AVAILABLE".equals(h.getOldStatus())
                && "ASSIGNED".equals(h.getNewStatus())
                && h.getChangedBy().equals(managerUser)
        ));
    }

    @Test
    void assignProperty_nullNotes_storedAsNull() {
        PropertyAssignment result = service.assignProperty(
                50L, 2L, 5L, "GOOD", null, "manager@example.com");
        assertNull(result.getNotes());
    }

    @Test
    void assignProperty_blankNotes_storedAsNull() {
        PropertyAssignment result = service.assignProperty(
                50L, 2L, 5L, "GOOD", "   ", "manager@example.com");
        assertNull(result.getNotes());
    }

    // -------------------------------------------------------------------------
    // Error paths
    // -------------------------------------------------------------------------

    @Test
    void assignProperty_propertyNotFound_throwsIllegalArgumentException() {
        when(propertyRepository.findById(999L)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class,
                () -> service.assignProperty(999L, 2L, 5L, "GOOD", null, "manager@example.com"));
    }

    @Test
    void assignProperty_propertyNotAvailable_throwsIllegalArgumentException() {
        availableProperty.setStatus("ASSIGNED");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.assignProperty(50L, 2L, 5L, "GOOD", null, "manager@example.com"));
        assertTrue(ex.getMessage().contains("Only available properties in good condition can be assigned"));
    }

    @Test
    void assignProperty_propertyNotInGoodCondition_throwsIllegalArgumentException() {
        availableProperty.setCondition("DAMAGED");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.assignProperty(50L, 2L, 5L, "GOOD", null, "manager@example.com"));
        assertTrue(ex.getMessage().contains("Only available properties in good condition can be assigned"));
    }

    @Test
    void assignProperty_alreadyHasActiveAssignment_throwsIllegalArgumentException() {
        when(assignmentRepository.hasActiveAssignment(50L)).thenReturn(true);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.assignProperty(50L, 2L, 5L, "GOOD", null, "manager@example.com"));
        assertTrue(ex.getMessage().contains("Property already has an active assignment"));
    }

    @Test
    void assignProperty_invalidConditionAtIssue_throwsIllegalArgumentException() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.assignProperty(50L, 2L, 5L, "EXCELLENT", null, "manager@example.com"));
        assertTrue(ex.getMessage().contains("Invalid condition at issue"));
    }

    @Test
    void assignProperty_employeeNotFound_throwsIllegalArgumentException() {
        when(appUserRepository.findById(999L)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class,
                () -> service.assignProperty(50L, 999L, 5L, "GOOD", null, "manager@example.com"));
    }

    @Test
    void assignProperty_inactiveEmployee_throwsIllegalArgumentException() {
        employeeUser.setActive(false);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.assignProperty(50L, 2L, 5L, "GOOD", null, "manager@example.com"));
        assertTrue(ex.getMessage().contains("Cannot assign a property to an inactive employee"));
    }

    @Test
    void assignProperty_inactiveIssuer_throwsIllegalArgumentException() {
        managerUser.setActive(false);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.assignProperty(50L, 2L, 5L, "GOOD", null, "manager@example.com"));
        assertTrue(ex.getMessage().contains("Inactive users cannot issue properties"));
    }

    @Test
    void assignProperty_inactiveArea_throwsIllegalArgumentException() {
        activeArea.setActive(false);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.assignProperty(50L, 2L, 5L, "GOOD", null, "manager@example.com"));
        assertTrue(ex.getMessage().contains("Please select an active physical area"));
    }
}
