package com.bssa.assetmanagement.controller;

import com.bssa.assetmanagement.entity.AppUser;
import com.bssa.assetmanagement.entity.Property;
import com.bssa.assetmanagement.repository.AppUserRepository;
import com.bssa.assetmanagement.repository.PhysicalAreaRepository;
import com.bssa.assetmanagement.repository.PropertyRepository;
import com.bssa.assetmanagement.repository.PropertyTypeRepository;
import com.bssa.assetmanagement.service.PropertyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PropertyControllerTest {

    private final PropertyRepository propertyRepository = mock(PropertyRepository.class);
    private final PropertyTypeRepository propertyTypeRepository = mock(PropertyTypeRepository.class);
    private final PhysicalAreaRepository physicalAreaRepository = mock(PhysicalAreaRepository.class);
    private final PropertyService propertyService = mock(PropertyService.class);
    private final AppUserRepository appUserRepository = mock(AppUserRepository.class);

    private final PropertyController controller = new PropertyController(
            propertyRepository,
            propertyTypeRepository,
            physicalAreaRepository,
            propertyService,
            appUserRepository
    );

    private UsernamePasswordAuthenticationToken auth;

    @BeforeEach
    void setUp() {
        reset(propertyRepository, propertyTypeRepository, physicalAreaRepository, propertyService, appUserRepository);

        auth = new UsernamePasswordAuthenticationToken(
                "admin@example.com",
                "pwd",
                List.of(new SimpleGrantedAuthority("ROLE_INVENTORY_ADMIN"))
        );

        AppUser activeUser = new AppUser();
        activeUser.setEmail("admin@example.com");
        activeUser.setActive(true);
        when(appUserRepository.findByEmail("admin@example.com")).thenReturn(Optional.of(activeUser));
    }

    @Test
    void listPropertiesShouldPopulateModel() {
        Model model = new ConcurrentModel();
        when(propertyRepository.findAllByOrderByPropertyIdDesc()).thenReturn(List.of(new Property()));

        String view = controller.listProperties(model, auth, new RedirectAttributesModelMap());

        assertEquals("properties/list", view);
        assertEquals(1, ((List<?>) model.getAttribute("properties")).size());
    }

    @Test
    void createPropertyShouldRedirectOnSuccess() {
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        String view = controller.createProperty(
                "PROP-001", 1L, 2L, "SN123", "Desk",
                LocalDate.now(), "GOOD", auth, redirectAttributes
        );

        assertEquals("redirect:/properties", view);
        assertEquals("Property registered successfully.", redirectAttributes.getFlashAttributes().get("successMessage"));
        verify(propertyService).createProperty(any(), any(), any(), any(), any(), any(), any(), eq("admin@example.com"));
    }

    @Test
    void createPropertyShouldRedirectToFormOnError() {
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        doThrow(new IllegalArgumentException("Property code is required."))
                .when(propertyService).createProperty(any(), any(), any(), any(), any(), any(), any(), any());

        String view = controller.createProperty(
                "", 1L, 2L, null, null,
                LocalDate.now(), "GOOD", auth, redirectAttributes
        );

        assertEquals("redirect:/properties/new", view);
        assertEquals("Property code is required.", redirectAttributes.getFlashAttributes().get("errorMessage"));
    }

    @Test
    void updatePropertyShouldRedirectOnSuccess() {
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        String view = controller.updateProperty(
                10L, "PROP-001", 1L, 2L, "SN123", "Desk",
                LocalDate.now(), "GOOD", auth, redirectAttributes
        );

        assertEquals("redirect:/properties", view);
        assertEquals("Property updated successfully.", redirectAttributes.getFlashAttributes().get("successMessage"));
        verify(propertyService).updateProperty(eq(10L), any(), any(), any(), any(), any(), any(), any(), eq("admin@example.com"));
    }

    @Test
    void updatePropertyShouldRedirectToEditOnError() {
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        doThrow(new IllegalArgumentException("Another property already uses this code."))
                .when(propertyService).updateProperty(any(), any(), any(), any(), any(), any(), any(), any(), any());

        String view = controller.updateProperty(
                10L, "PROP-002", 1L, 2L, null, null,
                LocalDate.now(), "GOOD", auth, redirectAttributes
        );

        assertEquals("redirect:/properties/10/edit", view);
        assertEquals("Another property already uses this code.", redirectAttributes.getFlashAttributes().get("errorMessage"));
    }

    @Test
    void retirePropertyShouldRedirectWithSuccess() {
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        String view = controller.retireProperty(10L, "Old equipment", auth, redirectAttributes);

        assertEquals("redirect:/properties", view);
        assertEquals("Asset retired successfully.", redirectAttributes.getFlashAttributes().get("successMessage"));
        verify(propertyService).retireProperty(10L, "Old equipment", "admin@example.com");
    }

    @Test
    void inactiveCallerShouldBeRedirectedToLogout() {
        AppUser inactiveUser = new AppUser();
        inactiveUser.setEmail("admin@example.com");
        inactiveUser.setActive(false);
        when(appUserRepository.findByEmail("admin@example.com")).thenReturn(Optional.of(inactiveUser));

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        String view = controller.createProperty(
                "PROP-001", 1L, 2L, null, null,
                LocalDate.now(), "GOOD", auth, redirectAttributes
        );

        assertEquals("redirect:/login?logout", view);
        verifyNoInteractions(propertyService);
    }
}
