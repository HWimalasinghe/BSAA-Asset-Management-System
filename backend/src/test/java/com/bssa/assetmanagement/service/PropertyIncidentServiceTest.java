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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PropertyIncidentServiceTest {

    private final PropertyIncidentRepository incidentRepository = mock(PropertyIncidentRepository.class);
    private final PropertyRepository propertyRepository = mock(PropertyRepository.class);
    private final AppUserRepository appUserRepository = mock(AppUserRepository.class);
    private final PropertyStatusHistoryRepository statusHistoryRepository = mock(PropertyStatusHistoryRepository.class);
    private final PhysicalAreaRepository physicalAreaRepository = mock(PhysicalAreaRepository.class);

    private final PropertyIncidentService service = new PropertyIncidentService(
            incidentRepository,
            propertyRepository,
            appUserRepository,
            statusHistoryRepository,
            physicalAreaRepository
    );

    private AppUser staffUser;
    private AppUser otherStaffUser;
    private AppUser managerUser;
    private Property testProperty;

    @BeforeEach
    void setUp() {
        reset(incidentRepository, propertyRepository, appUserRepository, statusHistoryRepository, physicalAreaRepository);

        Role staffRole = new Role();
        staffRole.setRoleId(1L);
        staffRole.setName("OFFICE_STAFF");

        Role managerRole = new Role();
        managerRole.setRoleId(2L);
        managerRole.setName("INVENTORY_MANAGER");

        staffUser = new AppUser();
        staffUser.setUserId(10L);
        staffUser.setEmail("staff@example.com");
        staffUser.setFirstName("Staff");
        staffUser.setLastName("Member");
        staffUser.setActive(true);
        staffUser.setRoles(Set.of(staffRole));

        otherStaffUser = new AppUser();
        otherStaffUser.setUserId(11L);
        otherStaffUser.setEmail("other@example.com");
        otherStaffUser.setFirstName("Other");
        otherStaffUser.setLastName("Staff");
        otherStaffUser.setActive(true);
        otherStaffUser.setRoles(Set.of(staffRole));

        managerUser = new AppUser();
        managerUser.setUserId(20L);
        managerUser.setEmail("manager@example.com");
        managerUser.setFirstName("Manager");
        managerUser.setLastName("User");
        managerUser.setActive(true);
        managerUser.setRoles(Set.of(managerRole));

        testProperty = new Property();
        testProperty.setPropertyId(100L);
        testProperty.setPropertyCode("PROP-001");
        testProperty.setDescription("Test Laptop");
        testProperty.setStatus("AVAILABLE");
        testProperty.setCondition("GOOD");

        when(appUserRepository.findByEmail("staff@example.com")).thenReturn(Optional.of(staffUser));
        when(appUserRepository.findByEmail("other@example.com")).thenReturn(Optional.of(otherStaffUser));
        when(appUserRepository.findByEmail("manager@example.com")).thenReturn(Optional.of(managerUser));
        when(propertyRepository.findById(100L)).thenReturn(Optional.of(testProperty));
        when(incidentRepository.save(any(PropertyIncident.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void reportIncident_validDamage_shouldSaveIncidentAndUpdatePropertyState() {
        PropertyIncident saved = service.reportIncident(
                100L,
                "DAMAGE",
                "Screen cracked during transit",
                "staff@example.com"
        );

        assertNotNull(saved);
        assertEquals("DAMAGE", saved.getIncidentType());
        assertEquals("OPEN", saved.getStatus());
        assertEquals("Screen cracked during transit", saved.getDescription());
        assertEquals(staffUser, saved.getReportedBy());

        assertEquals("DAMAGED", testProperty.getCondition());
        assertEquals("DAMAGED", testProperty.getStatus());

        verify(propertyRepository).save(testProperty);
        verify(statusHistoryRepository).save(any(PropertyStatusHistory.class));
        verify(incidentRepository).save(any(PropertyIncident.class));
    }

    @Test
    void reportIncident_validLost_shouldSetLostStatus() {
        PropertyIncident saved = service.reportIncident(
                100L,
                "LOST",
                "Asset misplaced during field visit",
                "staff@example.com"
        );

        assertNotNull(saved);
        assertEquals("LOST", saved.getIncidentType());
        // Property condition is not changed to LOST (LOST is a status, not a valid condition);
        // condition remains GOOD until an authorised manager explicitly updates it.
        assertEquals("GOOD", testProperty.getCondition());
        assertEquals("LOST", testProperty.getStatus());
    }

    @Test
    void reportIncident_assignedAsset_shouldTransitionToUnderInspection() {
        testProperty.setStatus("ASSIGNED");

        service.reportIncident(
                100L,
                "DAMAGE",
                "Keyboard water spill",
                "staff@example.com"
        );

        assertEquals("UNDER_INSPECTION", testProperty.getStatus());
        assertEquals("DAMAGED", testProperty.getCondition());
    }

    @Test
    void reportIncident_missingAsset_throwsIllegalArgumentException() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                service.reportIncident(999L, "DAMAGE", "Broken", "staff@example.com")
        );
        assertTrue(ex.getMessage().contains("Asset not found"));
    }

    @Test
    void reportIncident_retiredAsset_throwsIllegalArgumentException() {
        testProperty.setStatus("RETIRED");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                service.reportIncident(100L, "DAMAGE", "Broken", "staff@example.com")
        );
        assertTrue(ex.getMessage().contains("Cannot report incidents on retired assets"));
    }

    @Test
    void reportIncident_blankDescription_throwsIllegalArgumentException() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                service.reportIncident(100L, "DAMAGE", "   ", "staff@example.com")
        );
        assertTrue(ex.getMessage().contains("Incident description is required"));
    }

    @Test
    void reportIncident_invalidIncidentType_throwsIllegalArgumentException() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                service.reportIncident(100L, "UNKNOWN", "Details", "staff@example.com")
        );
        assertTrue(ex.getMessage().contains("Invalid incident type"));
    }

    @Test
    void reportIncident_duplicateOpenIncident_throwsIllegalStateException() {
        when(incidentRepository.existsByProperty_PropertyIdAndStatus(100L, "OPEN")).thenReturn(true);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                service.reportIncident(100L, "DAMAGE", "Details", "staff@example.com")
        );
        assertTrue(ex.getMessage().contains("An open incident report is already currently active"));
    }

    @Test
    void resolveIncident_unauthorizedStaff_throwsAccessDeniedException() {
        PropertyIncident incident = new PropertyIncident();
        incident.setIncidentId(1L);
        incident.setProperty(testProperty);
        incident.setStatus("OPEN");
        incident.setReportedBy(staffUser);

        when(incidentRepository.findById(1L)).thenReturn(Optional.of(incident));

        assertThrows(AccessDeniedException.class, () ->
                service.resolveIncident(1L, "APPROVED", "Approved for repair", "REPAIR", "staff@example.com")
        );
    }

    @Test
    void resolveIncident_authorizedManager_resolvesAndUpdatesProperty() {
        PropertyIncident incident = new PropertyIncident();
        incident.setIncidentId(1L);
        incident.setProperty(testProperty);
        incident.setStatus("OPEN");
        incident.setReportedBy(staffUser);

        when(incidentRepository.findById(1L)).thenReturn(Optional.of(incident));

        PropertyIncident resolved = service.resolveIncident(
                1L,
                "RESOLVED",
                "Sent to authorized vendor for repairs",
                "REPAIR",
                "manager@example.com"
        );

        assertEquals("RESOLVED", resolved.getStatus());
        assertEquals("Sent to authorized vendor for repairs", resolved.getDecision());
        assertEquals("REPAIR", resolved.getActionTaken());
        assertEquals(managerUser, resolved.getReviewedBy());
        assertNotNull(resolved.getReviewedAt());

        assertEquals("UNDER_REPAIR", testProperty.getStatus());
        verify(propertyRepository).save(testProperty);
        verify(statusHistoryRepository).save(any(PropertyStatusHistory.class));
    }

    @Test
    void resolveIncident_markLostAction_updatesPropertyToLost() {
        PropertyIncident incident = new PropertyIncident();
        incident.setIncidentId(1L);
        incident.setProperty(testProperty);
        incident.setStatus("OPEN");
        incident.setReportedBy(staffUser);

        when(incidentRepository.findById(1L)).thenReturn(Optional.of(incident));

        service.resolveIncident(
                1L,
                "RESOLVED",
                "Investigation concluded asset is unrecoverable",
                "LOST",
                "manager@example.com"
        );

        assertEquals("LOST", testProperty.getStatus());
    }

    @Test
    void resolveIncident_repeatedResolution_throwsIllegalStateException() {
        PropertyIncident incident = new PropertyIncident();
        incident.setIncidentId(1L);
        incident.setProperty(testProperty);
        incident.setStatus("RESOLVED");
        incident.setReportedBy(staffUser);

        when(incidentRepository.findById(1L)).thenReturn(Optional.of(incident));

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                service.resolveIncident(1L, "RESOLVED", "Notes", "REPAIR", "manager@example.com")
        );
        assertTrue(ex.getMessage().contains("Only open incidents can be reviewed or resolved"));
    }

    @Test
    void returnToService_authorizedManager_restoresPropertyToAvailable() {
        testProperty.setStatus("UNDER_REPAIR");
        testProperty.setCondition("DAMAGED");

        service.returnToService(100L, null, "Repairs completed and tested OK", "manager@example.com");

        assertEquals("AVAILABLE", testProperty.getStatus());
        assertEquals("GOOD", testProperty.getCondition());
        verify(propertyRepository).save(testProperty);
        verify(statusHistoryRepository).save(any(PropertyStatusHistory.class));
    }

    @Test
    void returnToService_withArea_updatesAreaAndRestoresProperty() {
        testProperty.setStatus("UNDER_REPAIR");
        testProperty.setCondition("DAMAGED");

        PhysicalArea area = new PhysicalArea();
        area.setAreaId(5L);
        area.setName("Floor 2 Storage");
        area.setActive(true);
        when(physicalAreaRepository.findById(5L)).thenReturn(Optional.of(area));

        service.returnToService(100L, 5L, "Returned to main storage", "manager@example.com");

        assertEquals("AVAILABLE", testProperty.getStatus());
        assertEquals("GOOD", testProperty.getCondition());
        assertEquals(area, testProperty.getArea());
    }

    @Test
    void returnToService_unauthorizedStaff_throwsAccessDeniedException() {
        testProperty.setStatus("UNDER_REPAIR");

        assertThrows(AccessDeniedException.class, () ->
                service.returnToService(100L, null, "Notes", "staff@example.com")
        );
    }

    @Test
    void returnToService_alreadyAvailableAndGood_throwsIllegalArgumentException() {
        testProperty.setStatus("AVAILABLE");
        testProperty.setCondition("GOOD");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                service.returnToService(100L, null, "Notes", "manager@example.com")
        );
        assertTrue(ex.getMessage().contains("Asset is already available"));
    }

    @Test
    void getIncidentForUser_staffOwnIncident_returnsIncident() {
        PropertyIncident incident = new PropertyIncident();
        incident.setIncidentId(1L);
        incident.setProperty(testProperty);
        incident.setStatus("OPEN");
        incident.setReportedBy(staffUser);

        when(incidentRepository.findWithDetailsByIncidentId(1L)).thenReturn(Optional.of(incident));

        PropertyIncident found = service.getIncidentForUser(1L, "staff@example.com", false);
        assertEquals(incident, found);
    }

    @Test
    void getIncidentForUser_staffOtherIncident_throwsAccessDeniedException() {
        PropertyIncident incident = new PropertyIncident();
        incident.setIncidentId(1L);
        incident.setProperty(testProperty);
        incident.setStatus("OPEN");
        incident.setReportedBy(otherStaffUser);

        when(incidentRepository.findWithDetailsByIncidentId(1L)).thenReturn(Optional.of(incident));

        assertThrows(AccessDeniedException.class, () ->
                service.getIncidentForUser(1L, "staff@example.com", false)
        );
    }

    @Test
    void getIncidentForUser_managerAnyIncident_returnsIncident() {
        PropertyIncident incident = new PropertyIncident();
        incident.setIncidentId(1L);
        incident.setProperty(testProperty);
        incident.setStatus("OPEN");
        incident.setReportedBy(staffUser);

        when(incidentRepository.findWithDetailsByIncidentId(1L)).thenReturn(Optional.of(incident));

        PropertyIncident found = service.getIncidentForUser(1L, "manager@example.com", true);
        assertEquals(incident, found);
    }
}
