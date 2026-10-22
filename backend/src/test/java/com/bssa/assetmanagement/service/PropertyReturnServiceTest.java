package com.bssa.assetmanagement.service;

import com.bssa.assetmanagement.entity.AppUser;
import com.bssa.assetmanagement.entity.PhysicalArea;
import com.bssa.assetmanagement.entity.Property;
import com.bssa.assetmanagement.entity.PropertyAssignment;
import com.bssa.assetmanagement.entity.PropertyCategory;
import com.bssa.assetmanagement.entity.PropertyIncident;
import com.bssa.assetmanagement.entity.PropertyReturn;
import com.bssa.assetmanagement.entity.PropertyStatusHistory;
import com.bssa.assetmanagement.entity.PropertyType;
import com.bssa.assetmanagement.entity.Role;
import com.bssa.assetmanagement.repository.AppUserRepository;
import com.bssa.assetmanagement.repository.PropertyAssignmentRepository;
import com.bssa.assetmanagement.repository.PropertyIncidentRepository;
import com.bssa.assetmanagement.repository.PropertyRepository;
import com.bssa.assetmanagement.repository.PropertyReturnRepository;
import com.bssa.assetmanagement.repository.PropertyStatusHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PropertyReturnServiceTest {

    private final PropertyAssignmentRepository assignmentRepository = mock(PropertyAssignmentRepository.class);
    private final PropertyReturnRepository returnRepository = mock(PropertyReturnRepository.class);
    private final PropertyRepository propertyRepository = mock(PropertyRepository.class);
    private final AppUserRepository appUserRepository = mock(AppUserRepository.class);
    private final PropertyStatusHistoryRepository statusHistoryRepository = mock(PropertyStatusHistoryRepository.class);
    private final PropertyIncidentRepository incidentRepository = mock(PropertyIncidentRepository.class);

    private final PropertyReturnService service = new PropertyReturnService(
            assignmentRepository,
            returnRepository,
            propertyRepository,
            appUserRepository,
            statusHistoryRepository,
            incidentRepository
    );

    private AppUser managerUser;
    private AppUser employeeUser;
    private Property testProperty;
    private PropertyAssignment testAssignment;

    @BeforeEach
    void setUp() {
        reset(assignmentRepository, returnRepository, propertyRepository,
                appUserRepository, statusHistoryRepository, incidentRepository);

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
        employeeUser.setFirstName("John");
        employeeUser.setLastName("Doe");
        employeeUser.setActive(true);
        employeeUser.setRoles(Set.of(staffRole));

        PropertyCategory category = new PropertyCategory();
        category.setCategoryId(1L);
        category.setName("IT Equipment");

        PropertyType propertyType = new PropertyType();
        propertyType.setPropertyTypeId(1L);
        propertyType.setName("Laptop");
        propertyType.setCategory(category);

        PhysicalArea area = new PhysicalArea();
        area.setAreaId(1L);
        area.setName("Floor 1 Store");
        area.setActive(true);

        testProperty = new Property();
        testProperty.setPropertyId(100L);
        testProperty.setPropertyCode("PROP-100");
        testProperty.setDescription("Dell Laptop");
        testProperty.setStatus("ASSIGNED");
        testProperty.setCondition("GOOD");
        testProperty.setPropertyType(propertyType);
        testProperty.setArea(area);

        testAssignment = new PropertyAssignment();
        testAssignment.setAssignmentId(10L);
        testAssignment.setProperty(testProperty);
        testAssignment.setEmployee(employeeUser);
        testAssignment.setIssuedBy(managerUser);
        testAssignment.setArea(area);
        testAssignment.setConditionAtIssue("GOOD");

        when(assignmentRepository.findById(10L)).thenReturn(Optional.of(testAssignment));
        when(appUserRepository.findByEmail("manager@example.com")).thenReturn(Optional.of(managerUser));
        when(returnRepository.existsByAssignment_AssignmentId(10L)).thenReturn(false);
        when(returnRepository.save(any(PropertyReturn.class))).thenAnswer(inv -> inv.getArgument(0));
        when(incidentRepository.save(any(PropertyIncident.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    // -------------------------------------------------------------------------
    // Happy paths
    // -------------------------------------------------------------------------

    @Test
    void returnProperty_goodCondition_setsPropertyToAvailable() {
        PropertyReturn result = service.returnProperty(10L, "GOOD", "No issues", "manager@example.com");

        assertNotNull(result);
        assertEquals(employeeUser, result.getReturnedBy());
        assertEquals(managerUser, result.getReceivedBy());
        assertEquals("GOOD", result.getCondition());

        assertEquals("AVAILABLE", testProperty.getStatus());
        assertEquals("GOOD", testProperty.getCondition());

        verify(propertyRepository).save(testProperty);
        verify(statusHistoryRepository).save(any(PropertyStatusHistory.class));
        // No incident expected for a good-condition return.
        verifyNoInteractions(incidentRepository);
    }

    @Test
    void returnProperty_damagedCondition_setsPropertyToDamagedAndCreatesIncident() {
        PropertyReturn result = service.returnProperty(10L, "DAMAGED", "Cracked screen", "manager@example.com");

        assertEquals("DAMAGED", result.getCondition());
        assertEquals("DAMAGED", testProperty.getStatus());
        assertEquals("DAMAGED", testProperty.getCondition());

        verify(propertyRepository).save(testProperty);
        verify(statusHistoryRepository).save(any(PropertyStatusHistory.class));

        // An OPEN incident must be auto-created so managers can review it.
        verify(incidentRepository).save(argThat(incident ->
                "DAMAGE".equals(incident.getIncidentType())
                && "OPEN".equals(incident.getStatus())
                && incident.getProperty().equals(testProperty)
                && incident.getReportedBy().equals(managerUser)
        ));
    }

    @Test
    void returnProperty_brokenCondition_setsPropertyToUnderRepairAndCreatesIncident() {
        PropertyReturn result = service.returnProperty(10L, "BROKEN", null, "manager@example.com");

        assertEquals("BROKEN", result.getCondition());
        assertEquals("UNDER_REPAIR", testProperty.getStatus());
        assertEquals("BROKEN", testProperty.getCondition());

        verify(incidentRepository).save(argThat(incident ->
                "BROKEN".equals(incident.getIncidentType())
                && "OPEN".equals(incident.getStatus())
        ));
    }

    @Test
    void returnProperty_historyRecordsOldAndNewStatus() {
        service.returnProperty(10L, "GOOD", null, "manager@example.com");

        verify(statusHistoryRepository).save(argThat(h ->
                "ASSIGNED".equals(h.getOldStatus())
                && "AVAILABLE".equals(h.getNewStatus())
                && h.getChangedBy().equals(managerUser)
        ));
    }

    // -------------------------------------------------------------------------
    // Error paths
    // -------------------------------------------------------------------------

    @Test
    void returnProperty_assignmentNotFound_throwsIllegalArgumentException() {
        when(assignmentRepository.findById(999L)).thenReturn(Optional.empty());

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.returnProperty(999L, "GOOD", null, "manager@example.com"));
        assertEquals("Assignment not found.", ex.getMessage());
    }

    @Test
    void returnProperty_alreadyReturned_throwsIllegalArgumentException() {
        when(returnRepository.existsByAssignment_AssignmentId(10L)).thenReturn(true);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.returnProperty(10L, "GOOD", null, "manager@example.com"));
        assertEquals("This assignment has already been returned.", ex.getMessage());
    }

    @Test
    void returnProperty_propertyNotAssigned_throwsIllegalArgumentException() {
        testProperty.setStatus("AVAILABLE");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.returnProperty(10L, "GOOD", null, "manager@example.com"));
        assertTrue(ex.getMessage().contains("Only assigned properties can be returned"));
    }

    @Test
    void returnProperty_invalidCondition_throwsIllegalArgumentException() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.returnProperty(10L, "LOST", null, "manager@example.com"));
        assertTrue(ex.getMessage().contains("Invalid return condition"));
    }

    @Test
    void returnProperty_inactiveReceiver_throwsIllegalArgumentException() {
        managerUser.setActive(false);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.returnProperty(10L, "GOOD", null, "manager@example.com"));
        assertTrue(ex.getMessage().contains("Inactive users cannot receive returned properties"));
    }

    @Test
    void returnProperty_notesAreTrimmedAndPreserved() {
        PropertyReturn result = service.returnProperty(10L, "GOOD", "   Some notes   ", "manager@example.com");
        assertEquals("Some notes", result.getNotes());
    }

    @Test
    void returnProperty_blankNotesStoredAsNull() {
        PropertyReturn result = service.returnProperty(10L, "GOOD", "   ", "manager@example.com");
        assertNull(result.getNotes());
    }
}
