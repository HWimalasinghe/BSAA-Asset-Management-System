package com.bssa.assetmanagement.security;

import com.bssa.assetmanagement.entity.AppUser;
import com.bssa.assetmanagement.entity.Role;
import com.bssa.assetmanagement.repository.AppUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises the sign-in boundary itself: the form, the credentials check, the
 * session that follows and the way a failure is answered.
 *
 * The other security tests start from an already-signed-in caller, because that
 * is what makes an authorization rule easy to state. None of them can see the
 * steps before: whether the form's field names are the ones the filter reads,
 * whether a deactivated account can still trade its password for a session, and
 * whether a failure tells an attacker something they did not already know. Those
 * are the questions here, answered through the real filter chain with the real
 * password encoder.
 *
 * Sign-in reads accounts through {@link AppUserRepository}, which is mocked, so
 * no test can be changed by whoever happens to exist in the development database
 * and no password here is a usable credential.
 */
@SpringBootTest
class AuthenticationFlowTest {

    private static final String PASSWORD = "ASignInTestPassword";
    private static final String ACTIVE_EMAIL = "signed.in@example.com";
    private static final String DISABLED_EMAIL = "deactivated@example.com";
    private static final String UNKNOWN_EMAIL = "nobody.exists@example.com";

    /** What the sign-in page says for every failure, and for every failure only. */
    private static final String GENERIC_FAILURE_MESSAGE =
            "Invalid email or password, or your account is disabled.";

    /** The parameter names the sign-in filter is configured to read. */
    private static final String USERNAME_FIELD = "username";
    private static final String PASSWORD_FIELD = "password";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockitoBean
    private AppUserRepository appUserRepository;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();

        String storedHash = passwordEncoder.encode(PASSWORD);

        when(appUserRepository.findByEmail(ACTIVE_EMAIL))
                .thenReturn(Optional.of(account(1L, ACTIVE_EMAIL, true, "OFFICE_STAFF", storedHash)));
        when(appUserRepository.findByEmail(DISABLED_EMAIL))
                .thenReturn(Optional.of(account(2L, DISABLED_EMAIL, false, "OFFICE_STAFF", storedHash)));
        when(appUserRepository.findByEmail(UNKNOWN_EMAIL)).thenReturn(Optional.empty());
    }

    @Test
    void aCorrectEmailAndPasswordOpensASessionThatCarriesThatAccountsRole() throws Exception {
        MvcResult signedIn = mockMvc.perform(signInRequest(ACTIVE_EMAIL, PASSWORD))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"))
                .andExpect(authenticated())
                .andExpect(authenticated().withAuthenticationName(ACTIVE_EMAIL))
                .andReturn();

        // The identity now lives in the session rather than in the request, and
        // the next call is answered as that account.
        MockHttpSession session = sessionOf(signedIn);
        SecurityContext stored = (SecurityContext) session
                .getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        assertNotNull(stored, "the session should hold the signed-in identity");

        // Contained rather than equal: along with the account's own roles,
        // Spring Security records how the caller proved who they are
        // (FACTOR_PASSWORD). What this test owes the reader is that the role the
        // account holds is the role the session carries.
        List<String> granted = new ArrayList<>();
        stored.getAuthentication().getAuthorities()
                .forEach(authority -> granted.add(authority.getAuthority()));
        assertTrue(granted.contains("ROLE_OFFICE_STAFF"),
                "the session should carry the account's role, got: " + granted);

        // And that call is refused the administrators' page outright, rather than
        // being turned away for not being signed in: the identity is real, the
        // permission is not.
        mockMvc.perform(get("/admin").session(session))
                .andExpect(status().isForbidden());
    }

    @Test
    void aWrongPasswordDoesNotOpenASession() throws Exception {
        mockMvc.perform(signInRequest(ACTIVE_EMAIL, "NotThePassword1"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?error"))
                .andExpect(unauthenticated());
    }

    /**
     * The account that switched off is the case a password check alone would let
     * through: its credentials are still correct, so only the account's own
     * status can refuse it. It is refused at the door, and no session is issued
     * for it to carry forward.
     */
    @Test
    void aDeactivatedAccountCannotTradeItsPasswordForASession() throws Exception {
        mockMvc.perform(signInRequest(DISABLED_EMAIL, PASSWORD))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?error"))
                .andExpect(unauthenticated());
    }

    /**
     * Three different ways of failing - wrong password, an account that is not
     * there, an account that has been switched off - must all land in the same
     * place and say the same thing. A page that distinguished them would tell
     * whoever is probing which addresses belong to real staff, which is half of
     * what an account takeover needs.
     */
    @Test
    void everySignInFailureIsAnsweredExactlyLikeEveryOther() throws Exception {
        List<MvcResult> failures = List.of(
                mockMvc.perform(signInRequest(ACTIVE_EMAIL, "NotThePassword1")).andReturn(),
                mockMvc.perform(signInRequest(UNKNOWN_EMAIL, PASSWORD)).andReturn(),
                mockMvc.perform(signInRequest(DISABLED_EMAIL, PASSWORD)).andReturn());

        for (MvcResult failure : failures) {
            assertTrue(failure.getResponse().getRedirectedUrl().startsWith("/login?error"),
                    "every failure should land on the same page: " + failure.getResponse().getRedirectedUrl());
            assertFalse(failure.getResponse().getRedirectedUrl().contains("disabled"),
                    "the destination should not describe why the sign-in failed");
        }

        String page = signInPageAfterFailure(failures.get(2));
        assertTrue(page.contains(GENERIC_FAILURE_MESSAGE),
                "the sign-in page should fall back to its one general message");

        for (String address : List.of(ACTIVE_EMAIL, DISABLED_EMAIL, UNKNOWN_EMAIL)) {
            for (String body : List.of(
                    signInPageAfterFailure(failures.get(0)),
                    signInPageAfterFailure(failures.get(1)),
                    page)) {
                assertFalse(body.contains(address),
                        "the page should not name the address somebody tried: " + address);
            }
        }
    }

    /**
     * A sign-in failure is also an attempt to put text into the page. The
     * submitted address is not echoed back, so it cannot be used to inject
     * markup through the form.
     */
    @Test
    void aSubmittedAddressIsNotEchoedBackIntoThePage() throws Exception {
        String injected = "<script>alert(1)</script>@example.com";
        when(appUserRepository.findByEmail(injected)).thenReturn(Optional.empty());

        MvcResult failure = mockMvc.perform(signInRequest(injected, PASSWORD)).andReturn();
        String page = signInPageAfterFailure(failure);

        assertFalse(page.contains("<script>alert(1)</script>"),
                "the address somebody typed should not be handed back as markup");
    }

    @Test
    void signingOutTakesTheIdentityOutOfTheSession() throws Exception {
        MvcResult signedIn = mockMvc.perform(signInRequest(ACTIVE_EMAIL, PASSWORD)).andReturn();
        MockHttpSession session = sessionOf(signedIn);
        String retiredSessionId = session.getId();

        mockMvc.perform(get("/admin").session(session))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/logout").session(session).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?logout"));

        // Destroyed, not merely emptied: whatever still holds the id has no
        // identity to read back from it, and cannot even be shown to have lost
        // one, because the session refuses to be read at all.
        assertTrue(session.isInvalid(), "signing out should leave the session unusable");
        assertThrows(IllegalStateException.class, () -> session
                .getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY),
                "an invalidated session should not answer questions about its contents");

        // What a browser replaying that id gets in return is the sign-in redirect,
        // not the refusal it earned while it was signed in as office staff.
        MvcResult replayed = mockMvc
                .perform(get("/admin").with(replayingSession(retiredSessionId)))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        String destination = replayed.getResponse().getRedirectedUrl();
        assertNotNull(destination, "a retired session should be sent somewhere");
        assertTrue(destination.startsWith("/login"),
                "a retired session should be asked to sign in again, got: " + destination);
    }

    /**
     * The sign-in page is open before it, and the form is the one the filter
     * reads: if the field names and the page ever part company, nobody can sign
     * in and the failure looks like an outage rather than a change.
     */
    @Test
    void theSignInPageCarriesTheFormTheFilterReadsAndItsOwnToken() throws Exception {
        String page = mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertTrue(page.contains("name=\"" + USERNAME_FIELD + "\""), "the form should post the field the filter reads");
        assertTrue(page.contains("name=\"" + PASSWORD_FIELD + "\""), "the form should post a password field");
        assertTrue(page.contains("name=\"_csrf\""),
                "the sign-in form should carry a CSRF token of its own");
        assertTrue(page.contains("action=\"/login\""), "the form should post to the login URL");
    }

    // ------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------

    /**
     * Submits the sign-in form the way the application's own form does, with the
     * parameter names SecurityConfig told the filter to read and a real CSRF
     * token, so a refusal cannot be mistaken for the filter stopping at the
     * token instead of at the password.
     */
    private MockHttpServletRequestBuilder signInRequest(String email, String submittedPassword) {
        return post("/login")
                .param(USERNAME_FIELD, email)
                .param(PASSWORD_FIELD, submittedPassword)
                .with(csrf());
    }

    /** The session the request ended up on, which is the one that is reused. */
    private MockHttpSession sessionOf(MvcResult result) {
        return (MockHttpSession) result.getRequest().getSession();
    }

    /**
     * Sends a request carrying a session id the server no longer knows, which is
     * what a back button, a bookmark or a copied cookie looks like.
     */
    private RequestPostProcessor replayingSession(String sessionId) {
        return (MockHttpServletRequest request) -> {
            request.setRequestedSessionId(sessionId);
            request.setRequestedSessionIdValid(false);
            request.setRequestedSessionIdFromCookie(true);
            return request;
        };
    }

    /**
     * Follows the redirect a failure produces, the way a browser would, so the
     * rendered page - not just the destination - can be read.
     */
    private String signInPageAfterFailure(MvcResult failure) throws Exception {
        return mockMvc.perform(get(failure.getResponse().getRedirectedUrl())
                        .session(sessionOf(failure)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private AppUser account(Long userId, String email, boolean active, String roleName, String storedHash) {
        Role role = new Role();
        role.setName(roleName);

        AppUser user = new AppUser();
        user.setUserId(userId);
        user.setEmployeeCode("AUTH" + userId);
        user.setFirstName("Sign");
        user.setLastName("In");
        user.setEmail(email);
        user.setPasswordHash(storedHash);
        user.setActive(active);
        user.setRoles(Set.of(role));
        return user;
    }
}
