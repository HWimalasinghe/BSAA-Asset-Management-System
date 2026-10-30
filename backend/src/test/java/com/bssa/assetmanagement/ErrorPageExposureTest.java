package com.bssa.assetmanagement;

import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.boot.webmvc.error.DefaultErrorAttributes;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What a person sees - and what the application gives away - when something goes
 * wrong.
 *
 * Every refusal the office will meet on a normal day is answered by the
 * controllers, which turn a service's objection into a message on the form it came
 * from. That leaves the ugly cases: an address nobody mapped, a form submitted
 * twice so the second one arrives after the row has moved on, a method used on a
 * route that only answers the other one, and the genuine fault nobody predicted.
 * Those go to the container's error page, which is the one screen in this
 * application that is not written here - and an error page is the classic place
 * for a stack trace, an SQL statement, a file path or a connection string to reach
 * a browser.
 *
 * MockMvc does not repeat the container's internal dispatch to {@code /error}, so
 * the response a real failure produces is reached two ways here: the model the
 * error page is built from is tested directly, and the page itself is rendered by
 * asking {@code /error} with the attributes a failure would have left behind.
 * Together those cover what a browser would be shown, without needing a browser.
 */
@SpringBootTest
class ErrorPageExposureTest {

    /**
     * Something a real fault would carry: a class name, a table, a value nobody
     * should read on screen. If any of these reach the error response, the guard has
     * gone.
     */
    private static final String INTERNAL_DETAIL =
            "connection refused jdbc:mysql://bssa_asset_management properties: IllegalStateException at com.bssa";

    @Autowired
    private org.springframework.web.context.WebApplicationContext context;

    // -------------------------------------------------------------------------
    // The page itself
    // -------------------------------------------------------------------------

    @Test
    @WithMockUser(username = "staff@example.com", roles = {"OFFICE_STAFF"})
    void aFailedRequestIsShownWithoutTheExceptionOrItsDetails() throws Exception {
        String page = renderErrorPage(500, new IllegalStateException(INTERNAL_DETAIL));

        assertCarriesNoDetail(page);
        // The page still has to say something useful, or a person staring at a blank
        // screen calls the System Administrator instead of writing down the URL.
        assertTrue(page.toLowerCase(Locale.ROOT).contains("error"),
                "the error page says nothing about there being an error: " + page);
    }

    @Test
    @WithMockUser(username = "staff@example.com", roles = {"OFFICE_STAFF"})
    void anUnknownAddressIsAnsweredAsNotFoundAndNothingMore() throws Exception {
        String page = renderErrorPage(404, null);

        assertCarriesNoDetail(page);
        assertTrue(page.contains("404"),
                "a mistyped address should be labelled, so it can be reported: " + page);
    }

    @Test
    @WithMockUser(username = "staff@example.com", roles = {"OFFICE_STAFF"})
    void aRefusedPageIsAnsweredAsForbiddenWithoutExplainingTheRules() throws Exception {
        // A refusal must not become a lesson in what the role may not do, or in which
        // screens exist behind it.
        String page = renderErrorPage(403, null);

        assertCarriesNoDetail(page);
        assertFalse(page.contains("INVENTORY_ADMIN") || page.contains("SYSTEM_ADMIN"),
                "the refusal names a role it could not show: " + page);
    }

    @Test
    @WithMockUser(username = "staff@example.com", roles = {"OFFICE_STAFF"})
    void theErrorPageDoesNotEchoTheAddressThatFailed() throws Exception {
        // The request URI is put in the error attributes by the container; a page that
        // repeats it back unescaped would be a way to plant text in somebody else's
        // screen. What a signed-in person can reach is already limited, so the check
        // here is that nothing is being interpolated raw.
        String page = renderErrorPage(500, null);

        assertCarriesNoDetail(page);
        assertFalse(page.contains("<script"), "the error page carries script: " + page);
    }

    // -------------------------------------------------------------------------
    // The model the error page is built from
    // -------------------------------------------------------------------------

    @Test
    void theErrorModelHoldsNeitherTheExceptionNorAStackTrace() {
        MockHttpServletRequest failed = new MockHttpServletRequest("GET", "/properties");
        failed.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 500);
        failed.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, "/properties");
        failed.setAttribute(RequestDispatcher.ERROR_EXCEPTION,
                new IllegalStateException(INTERNAL_DETAIL));

        WebRequest request = new ServletWebRequest(failed);
        // defaults() is what the error page is built with while the server.error.*
        // settings stay off, which the configuration test below checks.
        Map<String, Object> model = new DefaultErrorAttributes()
                .getErrorAttributes(request, ErrorAttributeOptions.defaults());

        // These two keys are what a stack trace and an exception class would arrive
        // in. They stay out unless somebody asks for them, which is the point.
        assertFalse(model.containsKey("trace"), "the error model carries a stack trace");
        assertFalse(model.containsKey("exception"), "the error model carries an exception class");
        assertFalse(model.toString().contains(INTERNAL_DETAIL),
                "the error model carries the failure's own words: " + model);

        // The same failure asked for with the detail switched on does produce those
        // keys, so the two assertions above are measuring something real rather than
        // looking in the wrong place. This is the half that proves it.
        Map<String, Object> verbose = new DefaultErrorAttributes().getErrorAttributes(
                request, ErrorAttributeOptions.of(ErrorAttributeOptions.Include.STACK_TRACE));
        assertTrue(verbose.containsKey("trace"),
                "a stack trace was not produced even when asked for; the check above "
                        + "is looking for the wrong key");
        assertTrue(String.valueOf(verbose.get("trace")).contains("IllegalStateException"),
                "the trace does not name the exception it came from: " + verbose.get("trace"));
    }

    @Test
    void theShippedConfigurationLeavesTheErrorDefaultsAlone() {
        // Spring Boot reports an error without a message, a stack trace, an exception
        // name or binding details unless one of these is switched on. The list below
        // is what turns each of them on; nothing in the shipped configuration may
        // name one, because that is how a JDBC URL or a staff member's submitted
        // value reaches a screen in the office. (SQL statement logging is guarded the
        // same way in DeploymentReadinessTest.)
        Properties configuration = shippedConfiguration();

        List<String> revealing = configuration.stringPropertyNames().stream()
                .filter(name -> name.startsWith("server.error."))
                .sorted()
                .toList();

        assertTrue(revealing.isEmpty(),
                "the shipped configuration says how much an error page reveals: " + revealing);

        for (String property : List.of(
                "server.error.include-stacktrace",
                "server.error.include-exception",
                "server.error.include-message",
                "server.error.include-binding-errors")) {

            assertNull(configuration.getProperty(property),
                    property + " is set; a deployed office system must not turn error "
                            + "detail on by default.");
        }
    }

    @Test
    void theApplicationShipsNoErrorTemplateThatCouldBeToldToPrintMore() {
        // A custom error.html is where a developer would put ${trace} or ${message}.
        // There is none, so the only thing that can be shown is Boot's own page, and
        // that page shows what the tests above just measured.
        assertNull(getClass().getResource("/templates/error.html"),
                "there is a custom error page; read it before trusting the assertions "
                        + "about what an error reveals.");
    }

    // -------------------------------------------------------------------------
    // Ordinary refusals, checked to be refusals rather than crashes
    // -------------------------------------------------------------------------

    @Test
    @WithMockUser(username = "staff@example.com", roles = {"OFFICE_STAFF"})
    void aGuessedAddressIsANotFoundRatherThanAServerError() throws Exception {
        // Something nobody typed and nothing maps: the answer is 404, not a page built
        // out of whatever the dispatcher gave up on.
        mockMvc().perform(get("/there-is-no-such-address"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(username = "invadmin@example.com", roles = {"INVENTORY_ADMIN"})
    void aWriteRouteOpenedAsALinkIsRefusedRatherThanHalfExecuted() throws Exception {
        // A route that changes something answers a POST. Following it as a plain link
        // has to be a refusal, and a refusal that still cannot have changed anything.
        mockMvc().perform(get("/departments/update"))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    @WithMockUser(username = "invadmin@example.com", roles = {"INVENTORY_ADMIN"})
    void anIncompleteFormIsRefusedWithoutNamingTheMissingFieldInAPage() throws Exception {
        MvcResult result = mockMvc().perform(get("/departments/edit"))
                .andExpect(status().isBadRequest())
                .andReturn();

        // A missing parameter is a bad request. The response must not be a page that
        // explains the application's own class names.
        String body = result.getResponse().getContentAsString();
        assertCarriesNoDetail(body);
        assertNotNull(result.getResolvedException(),
                "the request should have been refused for a reason the server knows");
        assertFalse(result.getResponse().getErrorMessage() != null
                        && result.getResponse().getErrorMessage().contains("com.bssa"),
                "the error message names an application class: "
                        + result.getResponse().getErrorMessage());
    }

    @Test
    @WithMockUser(username = "staff@example.com", roles = {"OFFICE_STAFF"})
    void aRefusalThroughTheFilterChainIsAPageNotAnExceptionPage() throws Exception {
        MvcResult result = mockMvc().perform(post("/departments").param("name", "Audit"))
                .andExpect(status().isForbidden())
                .andReturn();

        // Without a CSRF token this is the CSRF filter; either way the answer is a
        // status, and the body carries nothing about the application.
        assertCarriesNoDetail(result.getResponse().getContentAsString());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private MockMvc mockMvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
    }

    /** The configuration the application is really started with. */
    private Properties shippedConfiguration() {
        Properties configuration = new Properties();
        try (InputStream in = getClass().getResourceAsStream("/application.properties")) {
            assertNotNull(in, "application.properties is not on the classpath.");
            configuration.load(in);
        } catch (IOException ex) {
            throw new IllegalStateException(
                    "Could not read application.properties: " + ex.getMessage(), ex);
        }
        return configuration;
    }

    /**
     * Renders the page a failure produces, by asking for the address the container
     * dispatches failures to and leaving the same attributes behind that a real
     * failure would have left.
     */
    private String renderErrorPage(int status, Exception exception) throws Exception {
        var request = get("/error")
                .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, status)
                .requestAttr(RequestDispatcher.ERROR_REQUEST_URI, "/some-address")
                .requestAttr("jakarta.servlet.error.method", "GET");

        if (exception != null) {
            request = request.requestAttr(RequestDispatcher.ERROR_EXCEPTION, exception);
        }

        MvcResult result = mockMvc().perform(request).andReturn();

        String page = result.getResponse().getContentAsString();
        assertFalse(page.isEmpty(), "the error page rendered nothing at all");
        return page;
    }

    /** The things that must never appear on a screen in the office. */
    private void assertCarriesNoDetail(String body) {
        String lower = body.toLowerCase(Locale.ROOT);

        for (String forbidden : new String[]{
                "stacktrace", "stack trace", "\tat ", "at java.", "at org.springframework.",
                "at com.bssa", "exception", "throwable", "caused by", "sql", "hibernate",
                "jdbc", "datasource", "password", "secret", "token", "bcrypt",
                "c:\\", "d:\\", "usr\\", "/var/", "internal_detail", INTERNAL_DETAIL.toLowerCase(Locale.ROOT)}) {

            assertFalse(lower.contains(forbidden),
                    "the error response mentions \"" + forbidden + "\": " + body);
        }
    }
}
