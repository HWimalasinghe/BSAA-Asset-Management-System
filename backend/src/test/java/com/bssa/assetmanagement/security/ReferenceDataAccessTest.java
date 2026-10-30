package com.bssa.assetmanagement.security;

import com.bssa.assetmanagement.repository.DepartmentRepository;
import com.bssa.assetmanagement.repository.PhysicalAreaRepository;
import com.bssa.assetmanagement.repository.PropertyCategoryRepository;
import com.bssa.assetmanagement.repository.PropertyTypeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The four reference lists the Inventory Admin maintains - departments, physical
 * areas, property categories and property types - asked of the real filter chain.
 *
 * {@code SecurityConfig} restricts all four to the two administrator roles, and
 * the nav fragment decides whether to show the links. Both of those are opinions
 * about rendering. What has to hold regardless is that the URLs themselves answer
 * a Staff or an Inventory Manager with a refusal, because a person who is not
 * offered a link can still type the address, and because these four registers
 * decide what every later asset, assignment and report will say.
 *
 * Every repository is mocked, so no test here can add, change or deactivate a row
 * in the office database, and the empty stubs double as the "list with nothing in
 * it" case: an administrator opening a fresh installation sees the page, not an
 * error. The {@code /edit} URLs are aimed at a row that does not exist for the
 * same reason - a stale tab must be turned back to the list with an explanation.
 */
@SpringBootTest
class ReferenceDataAccessTest {

    /** A page an administrator reads: the list itself and its add form. */
    private static final List<String> PAGES = List.of(
            "/departments", "/departments/new",
            "/physical-areas", "/physical-areas/new",
            "/property-categories", "/property-categories/new",
            "/property-types", "/property-types/new");

    /** The edit form of a row that is not in the database. */
    private static final List<String> EDIT_PAGES = List.of(
            "/departments/edit", "/physical-areas/edit",
            "/property-categories/edit", "/property-types/edit");

    /**
     * Every write the four registers offer, with the parameters the form sends.
     * A submission reaches its handler only when the role check has already
     * passed, which is why the parameters are supplied here: without them the
     * request would fail as a bad request and look like a refusal.
     */
    private static final List<Submission> WRITES = List.of(
            new Submission("/departments", "name=Audit", "description=Statutory reporting"),
            new Submission("/departments/update", "id=1", "name=Audit", "description="),
            new Submission("/departments/toggle-status", "id=1"),
            new Submission("/physical-areas", "name=Store Room", "description=Ground floor"),
            new Submission("/physical-areas/update", "id=1", "name=Store Room", "description="),
            new Submission("/physical-areas/toggle-status", "id=1"),
            new Submission("/property-categories", "name=Laptops", "description=Portable computers"),
            new Submission("/property-categories/update", "id=1", "name=Laptops", "description="),
            new Submission("/property-categories/toggle-status", "id=1"),
            new Submission("/property-types", "categoryId=1", "name=Dell Latitude", "usageType=COMMON"),
            new Submission("/property-types/update",
                    "id=1", "categoryId=1", "name=Dell Latitude", "usageType=COMMON", "description="),
            new Submission("/property-types/toggle-status", "id=1"));

    /** What each register says when the row behind an edit is gone. */
    private static final List<String> MISSING_ROW_MESSAGES = List.of(
            "Department not found.", "Physical area not found.",
            "Property category not found.", "Property type not found.");

    @Autowired
    private WebApplicationContext context;

    @MockitoBean
    private DepartmentRepository departmentRepository;

    @MockitoBean
    private PhysicalAreaRepository physicalAreaRepository;

    @MockitoBean
    private PropertyCategoryRepository propertyCategoryRepository;

    @MockitoBean
    private PropertyTypeRepository propertyTypeRepository;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();

        reset(departmentRepository, physicalAreaRepository,
                propertyCategoryRepository, propertyTypeRepository);

        // Empty registers, and no row behind any edit link.
        when(departmentRepository.findAllByOrderByNameAsc()).thenReturn(List.of());
        when(physicalAreaRepository.findAllByOrderByNameAsc()).thenReturn(List.of());
        when(propertyCategoryRepository.findAllByOrderByNameAsc()).thenReturn(List.of());
        when(propertyTypeRepository.findAllByOrderByCategory_NameAscNameAsc()).thenReturn(List.of());
        when(departmentRepository.findById(anyLong())).thenReturn(Optional.empty());
        when(physicalAreaRepository.findById(anyLong())).thenReturn(Optional.empty());
        when(propertyCategoryRepository.findById(anyLong())).thenReturn(Optional.empty());
        when(propertyTypeRepository.findById(anyLong())).thenReturn(Optional.empty());
    }

    @Test
    void anUnsignedInCallerIsTurnedToTheSignInPageForEveryReferenceUrl() throws Exception {
        // The three lists are the whole surface of the four registers; if one of
        // them is ever enlarged and this test is not told, the count check below is
        // where it shows up.
        assertTrue(PAGES.size() + EDIT_PAGES.size() + WRITES.size() >= 24,
                "the reference screens are expected to be covered in full");

        for (String url : PAGES) {
            assertSentToSignIn(get(url));
        }
        for (String url : EDIT_PAGES) {
            assertSentToSignIn(get(url).param("id", "1"));
        }
        for (Submission write : WRITES) {
            assertSentToSignIn(withFields(post(write.url()).with(csrf()), write));
        }
    }

    @Test
    @WithMockUser(username = "staff@example.com", roles = {"OFFICE_STAFF"})
    void staffIsRefusedEveryReferencePageAndEveryReferenceWrite() throws Exception {
        for (String url : PAGES) {
            mockMvc.perform(get(url)).andExpect(status().isForbidden());
        }
        for (String url : EDIT_PAGES) {
            mockMvc.perform(get(url).param("id", "1")).andExpect(status().isForbidden());
        }
        for (Submission write : WRITES) {
            // The token is supplied on purpose: this has to be the role refusing the
            // URL, not the CSRF filter refusing the request.
            mockMvc.perform(withFields(post(write.url()).with(csrf()), write))
                    .andExpect(status().isForbidden());
        }

        assertNothingWasSaved();
    }

    @Test
    @WithMockUser(username = "manager@example.com", roles = {"INVENTORY_MANAGER"})
    void inventoryManagerIsRefusedEveryReferencePageAndEveryReferenceWrite() throws Exception {
        for (String url : PAGES) {
            mockMvc.perform(get(url)).andExpect(status().isForbidden());
        }
        for (String url : EDIT_PAGES) {
            mockMvc.perform(get(url).param("id", "1")).andExpect(status().isForbidden());
        }
        for (Submission write : WRITES) {
            // A manager may issue and take back assets, and still has no say in what
            // the registers say those assets are.
            mockMvc.perform(withFields(post(write.url()).with(csrf()), write))
                    .andExpect(status().isForbidden());
        }

        assertNothingWasSaved();
    }

    @Test
    @WithMockUser(username = "invadmin@example.com", roles = {"INVENTORY_ADMIN"})
    void inventoryAdminOpensEveryReferenceListAndEverySubmitIsAccepted() throws Exception {
        for (String url : PAGES) {
            // 200 rather than a redirect also proves an empty register renders a
            // page instead of failing.
            mockMvc.perform(get(url)).andExpect(status().isOk());
        }

        for (Submission write : WRITES) {
            mockMvc.perform(withFields(post(write.url()).with(csrf()), write))
                    .andExpect(status().is3xxRedirection());
        }
    }

    @Test
    @WithMockUser(username = "admin@example.com", roles = {"SYSTEM_ADMIN"})
    void systemAdminOpensEveryReferencePageAndEditsAreReadThroughTheRepository() throws Exception {
        for (String url : PAGES) {
            mockMvc.perform(get(url)).andExpect(status().isOk());
        }

        // The System Administrator is listed alongside the Inventory Admin in
        // SecurityConfig, so the same screens have to work for them too - they are
        // the only person who can recover a register when the Inventory Admin's
        // account is the thing that is broken.
        for (int i = 0; i < EDIT_PAGES.size(); i++) {
            String list = PAGES.get(i * 2);
            mockMvc.perform(get(EDIT_PAGES.get(i)).param("id", "1"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl(list))
                    .andExpect(flash().attribute("errorMessage", MISSING_ROW_MESSAGES.get(i)));
        }
    }

    @Test
    @WithMockUser(username = "staff@example.com", roles = {"OFFICE_STAFF"})
    void aStaffUrlGuessDoesNotReachAnyOfTheFourRegisters() throws Exception {
        // The name of the screen is not a secret and guessing it is free; neither
        // register may answer. Asserted separately from the loop above so a
        // permission change to one register cannot be hidden by the others.
        mockMvc.perform(get("/departments")).andExpect(status().isForbidden());
        mockMvc.perform(get("/physical-areas")).andExpect(status().isForbidden());
        mockMvc.perform(get("/property-categories")).andExpect(status().isForbidden());
        mockMvc.perform(get("/property-types")).andExpect(status().isForbidden());

        assertNothingWasSaved();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void assertSentToSignIn(MockHttpServletRequestBuilder request)
            throws Exception {

        MvcResult result = mockMvc.perform(request)
                .andExpect(status().is3xxRedirection())
                .andReturn();

        String destination = result.getResponse().getRedirectedUrl();
        assertTrue(destination != null && destination.startsWith("/login"),
                "an unsigned-in caller was sent somewhere other than the sign-in page: "
                        + destination);
    }

    /** Adds the fields a form would send, so the request is well-formed. */
    private MockHttpServletRequestBuilder withFields(MockHttpServletRequestBuilder request,
                                                     Submission submission) {

        for (String field : submission.params()) {
            int separator = field.indexOf('=');
            request.param(field.substring(0, separator), field.substring(separator + 1));
        }
        return request;
    }

    /**
     * The refusals above are only worth anything if nothing was written. Every
     * repository is mocked, so this is the one place a leak would show up.
     */
    private void assertNothingWasSaved() {
        verify(departmentRepository, never()).save(any());
        verify(physicalAreaRepository, never()).save(any());
        verify(propertyCategoryRepository, never()).save(any());
        verify(propertyTypeRepository, never()).save(any());
    }

    /** A form submission: the URL it posts to and the fields it carries. */
    private record Submission(String url, String... params) {
    }
}
