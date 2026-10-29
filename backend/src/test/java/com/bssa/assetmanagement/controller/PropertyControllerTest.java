package com.bssa.assetmanagement.controller;

import com.bssa.assetmanagement.entity.AppUser;
import com.bssa.assetmanagement.entity.PhysicalArea;
import com.bssa.assetmanagement.entity.Property;
import com.bssa.assetmanagement.entity.PropertyCategory;
import com.bssa.assetmanagement.entity.PropertyType;
import com.bssa.assetmanagement.repository.AppUserRepository;
import com.bssa.assetmanagement.repository.PhysicalAreaRepository;
import com.bssa.assetmanagement.repository.PropertyCategoryRepository;
import com.bssa.assetmanagement.repository.PropertyRepository;
import com.bssa.assetmanagement.repository.PropertyTypeRepository;
import com.bssa.assetmanagement.service.PropertyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class PropertyControllerTest {

    private final PropertyRepository propertyRepository = mock(PropertyRepository.class);
    private final PropertyTypeRepository propertyTypeRepository = mock(PropertyTypeRepository.class);
    private final PropertyCategoryRepository propertyCategoryRepository = mock(PropertyCategoryRepository.class);
    private final PhysicalAreaRepository physicalAreaRepository = mock(PhysicalAreaRepository.class);
    private final PropertyService propertyService = mock(PropertyService.class);
    private final AppUserRepository appUserRepository = mock(AppUserRepository.class);

    private final PropertyController controller = new PropertyController(
            propertyRepository,
            propertyTypeRepository,
            propertyCategoryRepository,
            physicalAreaRepository,
            propertyService,
            appUserRepository
    );

    private UsernamePasswordAuthenticationToken auth;

    @BeforeEach
    void setUp() {
        reset(propertyRepository, propertyTypeRepository, propertyCategoryRepository,
                physicalAreaRepository, propertyService, appUserRepository);

        auth = new UsernamePasswordAuthenticationToken(
                "admin@example.com",
                "pwd",
                List.of(new SimpleGrantedAuthority("ROLE_INVENTORY_ADMIN"))
        );

        AppUser activeUser = new AppUser();
        activeUser.setEmail("admin@example.com");
        activeUser.setActive(true);
        when(appUserRepository.findByEmail("admin@example.com")).thenReturn(Optional.of(activeUser));

        // Master data behind the filter dropdowns.
        PropertyCategory category = new PropertyCategory();
        category.setCategoryId(1L);
        category.setName("Laptops");
        PropertyType type = new PropertyType();
        type.setPropertyTypeId(2L);
        type.setName("ThinkPad T14");
        type.setCategory(category);
        PhysicalArea area = new PhysicalArea();
        area.setAreaId(3L);
        area.setName("Finance Floor");

        when(propertyCategoryRepository.findAllByActiveTrueOrderByNameAsc()).thenReturn(List.of(category));
        when(propertyTypeRepository.findAllByActiveTrueOrderByNameAsc()).thenReturn(List.of(type));
        when(physicalAreaRepository.findAllByActiveTrueOrderByNameAsc()).thenReturn(List.of(area));
    }

    @Test
    void listPropertiesShouldPopulateModel() {
        Model model = new ConcurrentModel();
        when(propertyService.searchProperties(
                null, null, null, null, null, null, null, null, null, 25, "registered", "asc"))
                .thenReturn(page(List.of(registerProperty("LAP-0001"))));

        String view = list(model, null, null, null, null, null, null);

        assertEquals("properties/list", view);
        assertEquals(1, ((List<?>) model.getAttribute("properties")).size());
        assertEquals(1L, model.getAttribute("totalElements"));
        // The page needs the dropdown options and the validated order to render
        // controls that cannot lose the reader's current search.
        assertFalse(((List<?>) model.getAttribute("statusOptions")).isEmpty());
        assertFalse(((List<?>) model.getAttribute("categoryOptions")).isEmpty());
        assertEquals(25, model.getAttribute("pageSize"));
        assertEquals("registered", model.getAttribute("sortKey"));
        assertEquals("asc", model.getAttribute("sortDirection"));
        assertEquals(Boolean.FALSE, model.getAttribute("filtersActive"));
        assertTrue(((String) model.getAttribute("filterQuery")).contains("sort=registered"));
        assertTrue(((String) model.getAttribute("filterQuery")).contains("size=25"));
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

    @Test
    void searchAndFiltersAreForwardedAndPreservedInLinks() {
        Model model = new ConcurrentModel();
        when(propertyService.searchProperties(
                "laptop & desk", "AVAILABLE", null, null, null, null, null, null, 3, 50, "code", "desc"))
                .thenReturn(page(List.of(registerProperty("LAP-0001"))));

        controller.listProperties("laptop & desk", "AVAILABLE", null, null, null, null,
                null, null, 3, 50, "code", "desc", model, auth);

        assertEquals("laptop & desk", model.getAttribute("search"));
        assertEquals(Boolean.TRUE, model.getAttribute("filtersActive"));

        // The link query must be encoded, or a search term holding & would
        // invent extra parameters, and it must keep the order and page size.
        String filterQuery = (String) model.getAttribute("filterQuery");
        assertTrue(filterQuery.contains("search=laptop+%26+desk"), filterQuery);
        assertTrue(filterQuery.contains("status=AVAILABLE"));
        assertTrue(filterQuery.contains("size=50"), filterQuery);
        assertTrue(filterQuery.contains("sort=code&dir=desc"), filterQuery);
        assertFalse(filterQuery.contains("page="), "the page number belongs to the links, not the shared query");

        // A column-header link adds the order it is switching to, so the query it
        // is built on must not already hold a sort - the browser would keep the
        // earlier pair and the column would never change hands.
        String headerQuery = (String) model.getAttribute("headerQuery");
        assertFalse(headerQuery.contains("sort="), headerQuery);
        assertFalse(headerQuery.contains("dir="), headerQuery);
        assertTrue(headerQuery.contains("search=laptop+%26+desk"), headerQuery);
    }

    @Test
    void anUnknownSortOrPageSizeIsReplacedBeforeThePageIsRendered() {
        Model model = new ConcurrentModel();
        when(propertyService.searchProperties(
                null, null, null, null, null, null, null, null, 0, 25, "registered", "asc"))
                .thenReturn(page(List.of()));

        list(model, null, null, 0, 999, "drop table", "sideways");

        assertEquals("registered", model.getAttribute("sortKey"));
        assertEquals("asc", model.getAttribute("sortDirection"));
        assertEquals(25, model.getAttribute("pageSize"));
    }

    @Test
    void anUnrecognisedFilterShowsAWarningWithoutRepeatingTheBadValue() {
        Model model = new ConcurrentModel();
        when(propertyService.searchProperties(any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any()))
                .thenThrow(new IllegalArgumentException("Unrecognised status filter: SYSDBA"));

        String view = list(model, null, "SYSDBA", null, null, null, null);

        assertEquals("properties/list", view);
        assertEquals("That filter is not one of the available options.", model.getAttribute("errorMessage"));
        assertTrue(((List<?>) model.getAttribute("properties")).isEmpty());
    }

    @Test
    void aDeactivatedCallerCannotOpenTheRegister() {
        AppUser inactiveUser = new AppUser();
        inactiveUser.setEmail("admin@example.com");
        inactiveUser.setActive(false);
        when(appUserRepository.findByEmail("admin@example.com")).thenReturn(Optional.of(inactiveUser));

        assertEquals("redirect:/login?logout", list(new ConcurrentModel(), null, null, null, null, null, null));
        verifyNoInteractions(propertyService);
    }

    @Test
    void exportWritesTheFilteredRowsWithTheRegisterColumns() {
        when(propertyService.findMatchingProperties(
                "laptop", "AVAILABLE", null, null, null, null, null, null, "code", "asc"))
                .thenReturn(List.of(registerProperty("LAP-0001")));

        ResponseEntity<byte[]> response = controller.exportRegister(
                "laptop", "AVAILABLE", null, null, null, null, null, null, "code", "asc", auth);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("attachment; filename=\"asset-register-" + LocalDate.now() + ".csv\"",
                response.getHeaders().getFirst("Content-Disposition"));
        assertTrue(response.getHeaders().getContentType().includes(
                new MediaType("text", "csv", StandardCharsets.UTF_8)));

        String csv = new String(response.getBody(), StandardCharsets.UTF_8).replace("\uFEFF", "");
        assertEquals("Asset Code,Property Type,Category,Physical Area,Status,Condition,"
                        + "Serial Number,Purchase Date,Description\r\n"
                        + "LAP-0001,ThinkPad T14,Laptops,Finance Floor,AVAILABLE,GOOD,"
                        + "SN-001,2024-01-01,\"Desk, finance\"\r\n", csv);
    }

    @Test
    void exportDefangsAValueThatWouldOpenAsASpreadsheetFormula() {
        Property boobyTrapped = registerProperty("LAP-0002");
        boobyTrapped.setDescription("=HYPERLINK(\"http://evil.example\")");
        when(propertyService.findMatchingProperties(any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any()))
                .thenReturn(List.of(boobyTrapped));

        ResponseEntity<byte[]> response = controller.exportRegister(
                null, null, null, null, null, null, null, null, null, null, auth);

        String csv = new String(response.getBody(), StandardCharsets.UTF_8);
        assertTrue(csv.contains("'=HYPERLINK"), csv);
        assertFalse(csv.contains(",=HYPERLINK"), "a formula must never reach the file unguarded");
    }

    @Test
    void exportRefusesAnUnrecognisedFilterAndDoesNotNameIt() {
        when(propertyService.findMatchingProperties(any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any()))
                .thenThrow(new IllegalArgumentException("Unrecognised status filter: SYSDBA"));

        ResponseEntity<byte[]> response = controller.exportRegister(
                null, "SYSDBA", null, null, null, null, null, null, null, null, auth);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        String body = new String(response.getBody(), StandardCharsets.UTF_8);
        assertFalse(body.contains("SYSDBA"), body);
        assertTrue(body.contains("not recognised"), body);
    }

    @Test
    void aDeactivatedCallerCannotDownloadTheExport() {
        AppUser inactiveUser = new AppUser();
        inactiveUser.setEmail("admin@example.com");
        inactiveUser.setActive(false);
        when(appUserRepository.findByEmail("admin@example.com")).thenReturn(Optional.of(inactiveUser));

        ResponseEntity<byte[]> response = controller.exportRegister(
                null, null, null, null, null, null, null, null, null, null, auth);

        assertEquals(HttpStatus.FOUND, response.getStatusCode());
        assertEquals("/login?logout", response.getHeaders().getFirst("Location"));
        verifyNoInteractions(propertyService);
    }

    private String list(
            Model model,
            String search,
            String status,
            Integer page,
            Integer size,
            String sort,
            String dir) {

        return controller.listProperties(search, status, null, null, null, null,
                null, null, page, size, sort, dir, model, auth);
    }

    private Page<Property> page(List<Property> rows) {
        return new PageImpl<>(rows, PageRequest.of(0, 25), rows.size());
    }

    private Property registerProperty(String code) {
        PropertyCategory category = new PropertyCategory();
        category.setCategoryId(1L);
        category.setName("Laptops");
        PropertyType type = new PropertyType();
        type.setPropertyTypeId(2L);
        type.setName("ThinkPad T14");
        type.setCategory(category);
        PhysicalArea area = new PhysicalArea();
        area.setAreaId(3L);
        area.setName("Finance Floor");

        Property property = new Property();
        property.setPropertyId(1L);
        property.setPropertyCode(code);
        property.setPropertyType(type);
        property.setArea(area);
        property.setStatus("AVAILABLE");
        property.setCondition("GOOD");
        property.setSerialNumber("SN-001");
        property.setPurchaseDate(LocalDate.of(2024, 1, 1));
        property.setDescription("Desk, finance");
        return property;
    }
}
