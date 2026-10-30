package com.bssa.assetmanagement;

import com.bssa.assetmanagement.controller.HealthController;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Checks the things that only matter when somebody else installs this system on an
 * office server: what the shipped configuration says, what would be committed by
 * accident, and what the one unauthenticated endpoint gives away.
 *
 * None of these can be verified by reading the code in the IDE, because they are
 * properties of the files rather than of the classes - a credential pasted into
 * {@code application.properties}, a {@code server.address} that quietly breaks
 * every other computer in the building, a {@code ddl-auto} that lets Hibernate
 * rewrite a live schema. Each test is a deployment incident that has been made
 * impossible instead of documented and hoped for.
 *
 * No Spring context and no database: the assertions read the files the application
 * is started with, so they hold on a machine that has no MySQL at all.
 */
class DeploymentReadinessTest {

    /**
     * Placeholder the configuration template uses for every credential. A real
     * password in the tracked template is the failure this guards against.
     */
    private static final String PASSWORD_PLACEHOLDER = "set-the-real-password-here";

    /**
     * Matches an assignment whose name says credential, capturing the name and the
     * value, so a tracked file can be judged on whether its value is a placeholder
     * or somebody's real secret. Quoted values are supported, because the
     * configuration template is PowerShell and every value there is in quotes.
     */
    private static final Pattern CREDENTIAL_ASSIGNMENT = Pattern.compile(
            "(?i)^\\s*(?:\\$env:)?([A-Za-z0-9_.]*(?:password|passwd|secret|token|api[_-]?key)"
                    + "[A-Za-z0-9_.]*)\\s*=\\s*[\"']?([^\"'#\\s][^\"'#]*)[\"']?\\s*$");

    // -------------------------------------------------------------------------
    // The configuration the application starts with
    // -------------------------------------------------------------------------

    @Test
    void applicationMustNotAlterTheSchemaItIsPointedAt() {
        Properties configuration = shippedConfiguration();

        // ddl-auto=validate or update against the office database rewrites tables
        // that hold assignments and history. The schema is managed by
        // database/schema.sql and by hand-written SQL, never by the ORM.
        assertEquals("none", configuration.getProperty("spring.jpa.hibernate.ddl-auto"),
                "Hibernate must never change a deployed schema by itself.");
    }

    @Test
    void adminBootstrapMustBeDisabledUnlessSomebodyAsksForIt() {
        Properties configuration = shippedConfiguration();

        String enabled = configuration.getProperty("app.bootstrap-admin.enabled");
        assertNotNull(enabled,
                "The admin bootstrap needs an explicit default rather than an absent property.");
        assertTrue(enabled.contains("${BOOTSTRAP_ADMIN_ENABLED:false}"),
                "First-run account creation must stay off unless an operator sets the "
                        + "environment variable, so a restart cannot invent an administrator.");

        // The component itself is conditional on the same property; if that ever
        // drifts, the property above stops meaning anything.
        String bootstrapSource = readRepositoryFile(
                "backend/src/main/java/com/bssa/assetmanagement/bootstrap/InitialAdminBootstrap.java");
        assertTrue(bootstrapSource.contains("name = \"app.bootstrap-admin.enabled\""),
                "InitialAdminBootstrap must stay conditional on the property the "
                        + "configuration sets to false.");
    }

    @Test
    void sqlStatementLoggingIsOffInTheShippedConfiguration() {
        Properties configuration = shippedConfiguration();

        // Statements carry parameter values, and parameter values are staff names,
        // emails and the occasional password during an account create.
        assertEquals("false", configuration.getProperty("spring.jpa.show-sql"));
        assertEquals("false",
                configuration.getProperty("spring.jpa.properties.hibernate.format_sql"));
    }

    @Test
    void dataSourcePasswordComesFromTheEnvironmentAndHasNoDefault() {
        Properties configuration = shippedConfiguration();

        assertEquals("${DB_PASSWORD}", configuration.getProperty("spring.datasource.password"),
                "The password must be read from the environment with no fallback value: a "
                        + "default would let a misconfigured server start against the wrong "
                        + "database instead of failing loudly.");
        assertEquals("${DB_USERNAME:root}", configuration.getProperty("spring.datasource.username"),
                "The username may default, but only to the documented development value.");
    }

    @Test
    void serverAddressMustNotBePinnedInTheConfiguration() {
        Properties configuration = shippedConfiguration();

        // Binding to 127.0.0.1 makes an office tool unreachable from every other
        // desk, and is the kind of change that looks harmless in a diff.
        assertFalse(configuration.stringPropertyNames().stream()
                        .anyMatch(name -> name.equalsIgnoreCase("server.address")),
                "The office deployment has to listen on every interface; binding one "
                        + "address belongs to the SERVER_ADDRESS environment variable, "
                        + "set deliberately and documented.");
        assertEquals("8080", configuration.getProperty("server.port"),
                "The port the firewall documentation and the office URLs are written around.");
    }

    @Test
    void shippedConfigurationCarriesNoCredential() {
        List<String> lines = readRepositoryLines(
                "backend/src/main/resources/application.properties");

        int credentialLines = 0;
        for (String line : lines) {
            Matcher matcher = CREDENTIAL_ASSIGNMENT.matcher(line);
            if (matcher.matches()) {
                credentialLines++;
                String value = matcher.group(2).trim();
                assertTrue(value.startsWith("${"),
                        "A literal credential is committed in application.properties: "
                                + line);
            }
        }

        // The datasource password is a placeholder reference today, so a run that
        // recognises nothing means the pattern and the file have drifted apart and
        // this test is checking nothing.
        assertTrue(credentialLines >= 1,
                "Expected to find at least one credential entry in the configuration "
                        + "file to check.");
    }

    // -------------------------------------------------------------------------
    // What the deployment files look like on purpose
    // -------------------------------------------------------------------------

    @Test
    void localSecretsAndOfficeDataCannotBeCommittedByAccident() {
        String gitIgnore = readRepositoryFile(".gitignore");

        // Each of these holds either a credential, office data, or one machine's
        // state. Losing any of them to a commit is not recoverable by deleting the
        // commit, because the repository is then the copy.
        assertTrue(gitIgnore.contains("deploy/env.local.ps1"),
                "The filled-in configuration file must stay out of the repository.");
        assertTrue(gitIgnore.contains("*.dump.sql") && gitIgnore.contains("*.sql.zip")
                        && gitIgnore.contains("backups/"),
                "Database dumps contain staff names and must never be tracked.");
        assertTrue(gitIgnore.contains("logs/") && gitIgnore.contains("*.log"),
                "Server logs are one machine's state and may contain session details.");
        assertTrue(gitIgnore.contains("!.env.example"),
                "The placeholder template has to stay tracked, or an installer has "
                        + "nothing to copy.");
    }

    @Test
    void configurationTemplateHoldsPlaceholdersOnly() {
        List<String> lines = readRepositoryLines("deploy/env.example.ps1");

        int credentialLines = 0;
        for (String line : lines) {
            Matcher matcher = CREDENTIAL_ASSIGNMENT.matcher(line);
            if (!matcher.matches()) {
                continue;
            }

            credentialLines++;
            assertEquals(PASSWORD_PLACEHOLDER, matcher.group(2).trim(),
                    "Every credential in a tracked file is a placeholder, or it is a "
                            + "secret: " + line);
        }

        assertTrue(credentialLines >= 2,
                "The template is expected to show at least the database and the bootstrap "
                        + "password entries; finding none means it changed shape and this "
                        + "test stopped checking anything.");
    }

    @Test
    void deploymentScriptsAreShippedWhereTheDocumentationPoints() {
        List<String> expected = List.of(
                "deploy/start-server.ps1",
                "deploy/stop-server.ps1",
                "deploy/backup-database.ps1",
                "deploy/restore-database.ps1",
                "deploy/configure-firewall.ps1",
                "deploy/env.example.ps1",
                "deploy/database-access.sql.example",
                "database/schema.sql");

        for (String relativePath : expected) {
            Path file = repositoryRoot().resolve(relativePath);
            assertTrue(Files.isRegularFile(file), "Missing: " + relativePath);
            try {
                assertTrue(Files.size(file) > 0, "Empty file: " + relativePath);
            } catch (IOException ex) {
                fail("Could not read " + relativePath + ": " + ex.getMessage());
            }
        }
    }

    @Test
    void firewallScriptNeedsElevationAndOffersADryRun() {
        String script = readRepositoryFile("deploy/configure-firewall.ps1");

        // Applying rules needs an administrator; saying so in the script itself
        // turns a silent no-op into a message. SupportsShouldProcess is what makes
        // -WhatIf meaningful, which is how an operator checks the plan first.
        assertTrue(script.contains("#Requires -RunAsAdministrator"),
                "configure-firewall.ps1 must refuse to half-run without elevation.");
        assertTrue(script.contains("SupportsShouldProcess"),
                "The firewall script must offer -WhatIf before it changes the host.");
        assertTrue(script.contains("-DeepPortScan") || script.contains("Get-NetFirewallRule"),
                "The script has to resolve the rules it claims about.");
    }

    // -------------------------------------------------------------------------
    // The one endpoint anybody can reach
    // -------------------------------------------------------------------------

    @Test
    void healthEndpointRevealsNothingBeyondThatTheServiceIsUp() throws Exception {
        HealthController controller = new HealthController();
        var payload = controller.health();

        assertEquals(2, payload.size(),
                "The unauthenticated probe must stay a liveness answer, not a summary "
                        + "of what is in the database.");
        assertEquals("UP", payload.get("status"));

        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        String body = mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertTrue(body.contains("UP"), "The probe should still answer: " + body);
        for (String forbidden : List.of(
                "password", "token", "email", "employee", "department", "session",
                "jdbc", "hikari", "user_id")) {
            assertFalse(body.toLowerCase().contains(forbidden),
                    "The unauthenticated health response mentions " + forbidden + ": " + body);
        }
    }

    // -------------------------------------------------------------------------
    // Reading the repository from a test
    // -------------------------------------------------------------------------

    private Properties shippedConfiguration() {
        Properties configuration = new Properties();
        try (InputStream in = getClass().getResourceAsStream("/application.properties")) {
            assertNotNull(in, "application.properties is not on the classpath.");
            configuration.load(in);
        } catch (IOException ex) {
            fail("Could not read application.properties: " + ex.getMessage());
        }
        return configuration;
    }

    private List<String> readRepositoryLines(String relativePath) {
        Path file = repositoryRoot().resolve(relativePath);
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new AssertionError(
                    "Could not read " + relativePath + " from " + file.toAbsolutePath()
                            + ": " + ex.getMessage(), ex);
        }
    }

    private String readRepositoryFile(String relativePath) {
        return String.join("\n", readRepositoryLines(relativePath));
    }

    /**
     * The repository root, found by walking up from where the build was started.
     * Tests run with {@code backend} as the working directory, and the assertions
     * above are about files outside it, so the walk has to succeed rather than be
     * assumed - a wrong guess fails with the path it tried, not with a mystery.
     */
    private Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath();

        while (candidate != null) {
            if (Files.isDirectory(candidate.resolve(".git"))
                    || Files.isRegularFile(candidate.resolve("database").resolve("schema.sql"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }

        throw new AssertionError(
                "Could not find the repository root above " + Path.of("").toAbsolutePath()
                        + ". Deployment tests read files outside the module, so run them "
                        + "from the checkout (mvnw test in backend).");
    }
}
