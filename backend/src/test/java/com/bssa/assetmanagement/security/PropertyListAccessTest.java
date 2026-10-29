package com.bssa.assetmanagement.security;

import com.bssa.assetmanagement.entity.PhysicalArea;
import com.bssa.assetmanagement.entity.Property;
import com.bssa.assetmanagement.entity.PropertyCategory;
import com.bssa.assetmanagement.entity.PropertyType;
import com.bssa.assetmanagement.repository.PhysicalAreaRepository;
import com.bssa.assetmanagement.repository.PropertyCategoryRepository;
import com.bssa.assetmanagement.repository.PropertyTypeRepository;
import com.bssa.assetmanagement.service.PropertyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

/**
 * Drives the register's search, filter, sort, paging and CSV download through
 * the real Spring Security filter chain.
 *
 * The unit tests show what the controller does; these show what an attacker or
 * a mistyped address bar can actually get: a role without register rights is
 * refused on both the page and the download, and no hand-crafted sort key,
 * page size, filter value or markup in a search term produces anything the
 * service was not prepared to handle.
 */
@SpringBootTest
class PropertyListAccessTest {

    @Autowired
    private WebApplicationContext context;

    @MockitoBean
    private PropertyService propertyService;

    @MockitoBean
    private PropertyCategoryRepository propertyCategoryRepository;

    @MockitoBean
    private PropertyTypeRepository propertyTypeRepository;

    @MockitoBean
    private PhysicalAreaRepository physicalAreaRepository;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();

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

        when(propertyService.searchProperties(any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(asset("LAP-0001")), PageRequest.of(0, 10), 60));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/properties", "/properties/export.csv"})
    @WithMockUser(username = "staff@example.com", roles = {"OFFICE_STAFF"})
    void officeStaffIsRefusedTheRegisterAndTheDownload(String url) throws Exception {
        mockMvc.perform(get(url))
                .andExpect(status().isForbidden())
                // The refused response must not carry any register data.
                .andExpect(content().string(not(containsString("LAP-0001"))));

        verifyNoInteractions(propertyService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/properties", "/properties/export.csv"})
    @WithMockUser(username = "manager@example.com", roles = {"INVENTORY_MANAGER"})
    void inventoryManagerIsRefusedToo(String url) throws Exception {
        mockMvc.perform(get(url)).andExpect(status().isForbidden());
        verifyNoInteractions(propertyService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/properties", "/properties/export.csv"})
    void anonymousCallersAreSentToLogin(String url) throws Exception {
        mockMvc.perform(get(url)).andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
        verifyNoInteractions(propertyService);
    }

    @Test
    @WithMockUser(username = "admin@example.com", roles = {"INVENTORY_ADMIN"})
    void theRegisterRendersSearchControlsAndPreservesThemInEveryLink() throws Exception {
        // Sixty matching rows at ten per page, and this is the second page.
        when(propertyService.searchProperties(eq("laptop"), eq("AVAILABLE"), any(), any(), any(), any(),
                any(), any(), eq(1), eq(10), eq("code"), eq("desc")))
                .thenReturn(new PageImpl<>(List.of(asset("LAP-0001")), PageRequest.of(1, 10), 60));

        MvcResult result = mockMvc.perform(get("/properties")
                        .param("search", "laptop")
                        .param("status", "AVAILABLE")
                        .param("sort", "code")
                        .param("dir", "desc")
                        .param("size", "10")
                        .param("page", "1"))
                .andExpect(status().isOk())
                .andReturn();

        String html = result.getResponse().getContentAsString();

        assertThatSearchBoxKeepsTheTerm(html);
        assertTrue(html.contains("value=\"AVAILABLE\""), "the active status option must stay selected");
        // Sorting and paging links must carry the filters, order and page size.
        assertTrue(html.contains("sort=code"), html);
        assertTrue(html.contains("dir=asc"), "clicking the sorted column again reverses it");
        assertTrue(html.contains("status=AVAILABLE"), "filter links keep the active filter");
        assertTrue(html.contains("size=10"), "paging keeps the chosen page size");
        assertTrue(html.contains("page=0"), "the previous-page link is offered");
        assertTrue(html.contains("page=2"), "the next-page link is offered");
        assertTrue(html.contains("Showing <strong>11</strong>"), "the summary states which rows are on this page");
        assertTrue(html.contains("of <strong>60</strong>"), "the total comes from the count query");
        assertTrue(html.contains("/properties/export.csv"), "the download is offered");
    }

    @Test
    @WithMockUser(username = "admin@example.com", roles = {"INVENTORY_ADMIN"})
    void theRequestValuesAreWhatTheServiceIsGiven() throws Exception {
        mockMvc.perform(get("/properties")
                        .param("search", "monitor")
                        .param("condition", "BROKEN")
                        .param("categoryId", "1")
                        .param("propertyTypeId", "2")
                        .param("areaId", "3")
                        .param("purchaseFrom", "2023-01-01")
                        .param("purchaseTo", "2023-12-31")
                        .param("page", "4")
                        .param("size", "50")
                        .param("sort", "purchaseDate")
                        .param("dir", "desc"))
                .andExpect(status().isOk());

        verify(propertyService).searchProperties(
                eq("monitor"), any(), eq("BROKEN"), eq(1L), eq(2L), eq(3L),
                eq(LocalDate.of(2023, 1, 1)), eq(LocalDate.of(2023, 12, 31)),
                eq(4), eq(50), eq("purchaseDate"), eq("desc"));
    }

    @Test
    @WithMockUser(username = "admin@example.com", roles = {"INVENTORY_ADMIN"})
    void aHandCraftedSortKeyNeverReachesTheQuery() throws Exception {
        mockMvc.perform(get("/properties").param("sort", "passwordHash").param("dir", "sideways"))
                .andExpect(status().isOk());

        verify(propertyService).searchProperties(any(), any(), any(), any(), any(), any(),
                any(), any(), any(), eq(25), eq(PropertyService.DEFAULT_ASSET_REGISTER_SORT), eq("asc"));
    }

    @Test
    @WithMockUser(username = "admin@example.com", roles = {"INVENTORY_ADMIN"})
    void aMarkupBearingSearchTermIsRenderedAsText() throws Exception {
        MvcResult result = mockMvc.perform(get("/properties")
                        .param("search", "<script>alert(1)</script>"))
                .andExpect(status().isOk())
                .andReturn();

        String html = result.getResponse().getContentAsString();
        assertTrue(html.contains("&lt;script&gt;alert(1)&lt;/script&gt;"),
                "the term must be escaped where it is echoed back");
        assertTrue(html.contains("search=%3Cscript%3Ealert%281%29%3C%2Fscript%3E"),
                "links must carry the term URL-encoded, not as raw markup");
    }

    @Test
    @WithMockUser(username = "admin@example.com", roles = {"INVENTORY_ADMIN"})
    void anUnrecognisedFilterIsReportedWithoutRepeatingTheValue() throws Exception {
        when(propertyService.searchProperties(any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalArgumentException("Unrecognised status filter: SYSDBA"));

        mockMvc.perform(get("/properties").param("status", "SYSDBA"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("not one of the available options")))
                .andExpect(content().string(not(containsString("SYSDBA"))))
                .andExpect(content().string(not(containsString("LAP-0001"))));
    }

    @Test
    @WithMockUser(username = "admin@example.com", roles = {"INVENTORY_ADMIN"})
    void aReversedDateRangeIsRejectedBeforeAnythingIsQueried() throws Exception {
        when(propertyService.searchProperties(any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalArgumentException("The start date must not be after the end date."));

        mockMvc.perform(get("/properties")
                        .param("purchaseFrom", "2024-06-01")
                        .param("purchaseTo", "2024-01-01"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("not one of the available options")));
    }

    @Test
    @WithMockUser(username = "admin@example.com", roles = {"INVENTORY_ADMIN"})
    void aMalformedDateIsRefusedByTheFrameworkWithoutQuerying() throws Exception {
        mockMvc.perform(get("/properties").param("purchaseFrom", "last Tuesday"))
                .andExpect(status().isBadRequest());

        verify(propertyService, never()).searchProperties(any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any());
    }

    @Test
    @WithMockUser(username = "admin@example.com", roles = {"INVENTORY_ADMIN"})
    void anEmptyResultSetStillRendersTheTableAndNoBrokenPager() throws Exception {
        when(propertyService.searchProperties(any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 25), 0));

        mockMvc.perform(get("/properties"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("No properties have been registered yet.")))
                // No pager links and no "Showing 0-0 of 0" line when there is nothing.
                .andExpect(content().string(not(containsString("Showing "))))
                .andExpect(content().string(not(containsString("page=1"))));
    }

    @Test
    @WithMockUser(username = "admin@example.com", roles = {"SYSTEM_ADMIN"})
    void theDownloadCarriesTheFilteredCsvWithFormulaValuesDefanged() throws Exception {
        Property trap = asset("LAP-0002");
        trap.setDescription("=HYPERLINK(\"http://evil.example\",\"click\")");
        when(propertyService.findMatchingProperties(any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any()))
                .thenReturn(List.of(trap));

        MvcResult result = mockMvc.perform(get("/properties/export.csv")
                        .param("search", "laptop")
                        .param("sort", "code")
                        .param("dir", "asc"))
                .andExpect(status().isOk())
                .andReturn();

        String disposition = result.getResponse().getHeader("Content-Disposition");
        assertTrue(disposition.startsWith("attachment; filename=\"asset-register-"), disposition);
        assertTrue(result.getResponse().getContentType().startsWith("text/csv"),
                result.getResponse().getContentType());

        String csv = new String(result.getResponse().getContentAsByteArray(),
                java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(csv.startsWith("\uFEFF"), "the byte-order mark keeps Excel in UTF-8");
        assertTrue(csv.contains("'=HYPERLINK"), csv);
        assertTrue(csv.contains("Asset Code,Property Type,Category,Physical Area"), csv);
        assertTrue(csv.contains("LAP-0002"), csv);

        verify(propertyService).findMatchingProperties(eq("laptop"), any(), any(), any(), any(), any(),
                any(), any(), eq("code"), eq("asc"));
    }

    @Test
    @WithMockUser(username = "admin@example.com", roles = {"INVENTORY_ADMIN"})
    void theDownloadRefusesAnUnrecognisedFilterWithoutNamingIt() throws Exception {
        when(propertyService.findMatchingProperties(any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any()))
                .thenThrow(new IllegalArgumentException("Unrecognised status filter: SYSDBA"));

        mockMvc.perform(get("/properties/export.csv").param("status", "SYSDBA"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("not recognised")))
                .andExpect(content().string(not(containsString("SYSDBA"))));
    }

    private void assertThatSearchBoxKeepsTheTerm(String html) {
        assertTrue(html.contains("value=\"laptop\""), "the search term must stay in its box");
    }

    private Property asset(String code) {
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
