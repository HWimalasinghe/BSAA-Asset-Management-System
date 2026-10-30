package com.bssa.assetmanagement.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks every address the pages can send a person to against the address book
 * the application builds for itself.
 *
 * A broken link in this system is not a cosmetic fault: the office has no other
 * way to reach a screen. A mistyped {@code @{/retuens}} on a return form, a route
 * renamed in a controller and not in the page, a nav entry added for a page that
 * does not exist - each looks harmless in a diff and leaves somebody unable to
 * finish their work. The same argument covers a form that posts to a URL only
 * answering a read: it submits, the server says 405, and what the person typed is
 * gone.
 *
 * Nothing is rendered here and no database is needed. Two lists are taken from the
 * application's own definitions - the registered routes and the link expressions in
 * the shipped templates - and compared. The route list comes from the mappings
 * rather than a written-out set of URLs, in the same way as
 * {@code StateChangingRouteTest}: a hand-kept list stops being true the day somebody
 * adds a controller, and that is exactly the day this test is for.
 */
class TemplateLinkRoutingTest {

    /** The shared list navigation is told which page it belongs to, by URL. */
    private static final Pattern FRAGMENT_PAGE_URL = Pattern.compile(
            "(?:pager|sortableHeader)\\(\\s*'([^']+)'");

    /**
     * Links whose name says they change something, so each one has to be answered by
     * a POST handler. Edit forms are deliberately not in here: {@code /departments/edit}
     * and {@code /users/edit/{id}} show a form and are reads, while the form itself
     * posts to {@code /update} or {@code /users/{id}/edit}.
     */
    private static final Pattern ACTION_URL = Pattern.compile(
            "/(?:cancel|resolve|fulfill|review|retire|toggle-status|update|status"
                    + "|read|read-all|return-to-service|logout)$",
            Pattern.CASE_INSENSITIVE);

    /** The address sign-out posts to; registered by Spring Security, not a controller. */
    private static final String SIGN_OUT_URL = "/logout";

    private static final Pattern LINK_VARIABLE = Pattern.compile("\\{[^}]*}");

    // -------------------------------------------------------------------------
    // The checks
    // -------------------------------------------------------------------------

    @Test
    void everyLinkInEveryTemplateLeadsToARouteTheApplicationHas() {
        Routes routes = routes();
        Set<String> unresolved = new LinkedHashSet<>();
        int checked = 0;

        for (Path template : templates()) {
            String html = read(template);

            for (String expression : linkExpressions(html)) {
                Link link = linkOf(template, expression);

                if (link.stylesheet()) {
                    continue;
                }
                if (link.path() == null) {
                    unresolved.add(link.describe());
                    continue;
                }

                checked++;
                if (!routes.all().contains(normalize(link.path()))) {
                    unresolved.add(link.describe());
                }
            }
        }

        assertTrue(unresolved.isEmpty(), "these page links lead nowhere: " + unresolved);
        assertTrue(checked >= 200,
                "only " + checked + " page links were compared; the scan has stopped "
                        + "reading the templates properly");
    }

    @Test
    void everyFormThatChangesSomethingPostsToARouteThatAcceptsAPost() {
        Routes routes = routes();
        List<String> wrongVerb = new ArrayList<>();
        int actions = 0;

        for (Path template : templates()) {
            for (String expression : linkExpressions(read(template))) {
                Link link = linkOf(template, expression);
                if (link.path() == null || link.stylesheet()
                        || !ACTION_URL.matcher(link.path()).find()) {
                    continue;
                }

                actions++;
                if (!routes.writes().contains(normalize(link.path()))) {
                    wrongVerb.add(link.describe() + " - no POST handler answers it");
                }
            }
        }

        assertTrue(actions >= 10,
                "expected the application's action links to be found at all; got " + actions);
        assertTrue(wrongVerb.isEmpty(),
                "these forms post to a URL that only answers a read, or to nothing at all: "
                        + wrongVerb);
    }

    @Test
    void everyPageTheSharedListNavigationIsToldAboutCanBeOpened() {
        Routes routes = routes();
        Set<String> pages = new LinkedHashSet<>();
        List<String> missing = new ArrayList<>();

        for (Path template : templates()) {
            Matcher matcher = FRAGMENT_PAGE_URL.matcher(read(template));
            while (matcher.find()) {
                pages.add(matcher.group(1));
            }
        }

        for (String page : pages) {
            if (!routes.reads().contains(normalize(page))) {
                missing.add(page);
            }
        }

        // pager() and sortableHeader() build their hrefs by concatenating around the
        // URL they are handed, so no other test in this file would notice one of
        // these being wrong - and every paged list screen depends on them.
        assertTrue(pages.size() >= 7,
                "expected the shared navigation to be told about at least the seven paged "
                        + "lists, found only: " + pages);
        assertTrue(missing.isEmpty(),
                "the shared navigation is told about pages that cannot be opened: " + missing);
    }

    @Test
    void everyStylesheetAPageAsksForIsShipped() {
        List<String> missing = new ArrayList<>();
        Set<String> askedFor = new LinkedHashSet<>();

        for (Path template : templates()) {
            for (String expression : linkExpressions(read(template))) {
                Link link = linkOf(template, expression);
                if (!link.stylesheet()) {
                    continue;
                }

                askedFor.add(link.path());
                if (getClass().getResource("/static" + link.path()) == null) {
                    missing.add(link.describe());
                }
            }
        }

        assertTrue(askedFor.size() >= 4,
                "expected the four shipped stylesheets to be referenced, found: " + askedFor);
        assertTrue(missing.isEmpty(), "these stylesheets are asked for and not shipped: " + missing);
    }

    @Test
    void noPageCarriesADeadLinkAFormWithoutATargetOrAnUntypedButton() {
        List<String> deadLinks = new ArrayList<>();
        List<String> formsWithoutTarget = new ArrayList<>();
        List<String> untypedButtons = new ArrayList<>();
        int forms = 0;
        int buttons = 0;

        for (Path template : templates()) {
            String html = read(template);

            Matcher href = Pattern.compile("href\\s*=\\s*\"([^\"]*)\"").matcher(html);
            while (href.find()) {
                String value = href.group(1).trim();
                // A link whose target is decided at render time is a Thymeleaf
                // expression or an interpolation. Anything else that goes nowhere -
                // "#", "", "javascript:", a leftover placeholder - is a control the
                // office can click and get nothing from.
                if (value.equals("#") || value.isEmpty()
                        || value.startsWith("javascript:")
                        || value.contains("TODO") || value.contains("example.com")) {
                    deadLinks.add(template.getFileName() + ": href=\"" + value + "\"");
                }
            }

            for (String tag : openTags(html, "form")) {
                forms++;
                if (!tag.contains("th:action") && !tag.contains("action=")) {
                    formsWithoutTarget.add(template.getFileName() + ": " + tag);
                }
            }

            for (String tag : openTags(html, "button")) {
                buttons++;
                // A button with no type inside a form is a submit button, which is how
                // a "Mark Read" control ends up resubmitting the form it sits next to.
                if (!tag.contains("type=")) {
                    untypedButtons.add(template.getFileName() + ": " + tag);
                }
            }
        }

        assertTrue(deadLinks.isEmpty(), "these links go nowhere: " + deadLinks);
        assertTrue(formsWithoutTarget.isEmpty(),
                "these forms have no target, so they resubmit the page they are on: "
                        + formsWithoutTarget);
        assertTrue(untypedButtons.isEmpty(),
                "these buttons would submit the surrounding form by default: " + untypedButtons);
        assertTrue(forms >= 15 && buttons >= 15,
                "expected the application's forms and buttons to be there at all (found "
                        + forms + " forms, " + buttons + " buttons)");
    }

    @Test
    void theScanSeesThePagesAndTheRoutesTheApplicationActuallyHas() {
        Routes routes = routes();

        assertTrue(templates().size() >= 36,
                "only " + templates().size() + " templates were found, so the scan is not "
                        + "reading the shipped pages");

        // One example of each shape a link can be: the dashboard, a page with a
        // parameter, a page with a path variable, a file download, a write-only
        // action, and the one address Spring Security registers for itself.
        assertTrue(routes.reads().contains("/"), routes.reads().toString());
        assertTrue(routes.reads().contains("/requests/*"),
                "the request detail page: " + routes.reads());
        assertTrue(routes.reads().contains("/users/edit/*"),
                "the account edit page: " + routes.reads());
        assertTrue(routes.reads().contains("/properties/export.csv"),
                "the register export: " + routes.reads());
        assertTrue(routes.writes().contains("/requests/*/review"),
                "approving a request is a POST: " + routes.writes());
        assertTrue(routes.writes().contains(SIGN_OUT_URL),
                "sign-out is the one address Spring Security registers itself, and the "
                        + "pages post to it");

        // Reads and writes have to be collected separately or the verb check above
        // would pass on a scan that had merged the two.
        assertFalse(routes.reads().contains("/departments/toggle-status"),
                "a write-only route has been collected as a read: " + routes.reads());
        assertFalse(routes.writes().contains("/properties/export.csv"),
                "a read-only route has been collected as a write: " + routes.writes());

        // The application answers some 79 routes across 76 distinct addresses; a
        // count this far down means a controller has stopped being scanned.
        assertTrue(routes.all().size() >= 60,
                "only " + routes.all().size() + " routes were found; a controller has "
                        + "stopped being scanned");
    }

    // -------------------------------------------------------------------------
    // The application's route table
    // -------------------------------------------------------------------------

    /** The registered routes, with path variables replaced by a wildcard. */
    private record Routes(Set<String> reads, Set<String> writes) {

        Set<String> all() {
            Set<String> every = new LinkedHashSet<>(reads);
            every.addAll(writes);
            return every;
        }
    }

    /** One link, as found in a page. */
    private record Link(Path template, String expression, String path, boolean stylesheet) {

        /** Kept so an assertion can say which page and which link it means. */
        String describe() {
            return template + " -> @{" + expression + "}" + (path == null ? "" : " (" + path + ")");
        }
    }

    private Routes routes() {
        Set<String> reads = new LinkedHashSet<>();
        Set<String> writes = new LinkedHashSet<>();

        for (Class<?> controller : controllerClasses()) {
            String prefix = classPrefix(controller);

            for (Method method : controller.getDeclaredMethods()) {
                if (method.isSynthetic()) {
                    continue;
                }
                collect(prefix, method, GetMapping.class, reads);
                collect(prefix, method, PostMapping.class, writes);
                collect(prefix, method, PutMapping.class, writes);
                collect(prefix, method, PatchMapping.class, writes);
                collect(prefix, method, DeleteMapping.class, writes);
            }
        }

        // Sign-out is not a controller route: Spring Security registers the handler
        // from the logout() block of SecurityConfig. It is named here so the
        // comparison above is complete; AuthenticationFlowTest and
        // StateChangingRouteTest are what prove the address works and refuses.
        writes.add(normalize(SIGN_OUT_URL));

        return new Routes(reads, writes);
    }

    private void collect(String prefix, Method method,
                         Class<? extends Annotation> mappingType, Set<String> into) {

        String[] paths = pathsOf(AnnotatedElementUtils.findMergedAnnotation(method, mappingType));
        if (paths == null) {
            return;
        }
        for (String path : paths) {
            into.add(normalize(prefix + path));
        }
    }

    /** The paths a mapping names, or null when the method does not answer that verb. */
    private String[] pathsOf(Annotation mapping) {
        if (mapping == null) {
            return null;
        }

        List<String> paths = new ArrayList<>();
        if (mapping instanceof GetMapping get) {
            paths.addAll(List.of(get.value()));
            paths.addAll(List.of(get.path()));
        } else if (mapping instanceof PostMapping post) {
            paths.addAll(List.of(post.value()));
            paths.addAll(List.of(post.path()));
        } else if (mapping instanceof PutMapping put) {
            paths.addAll(List.of(put.value()));
            paths.addAll(List.of(put.path()));
        } else if (mapping instanceof PatchMapping patch) {
            paths.addAll(List.of(patch.value()));
            paths.addAll(List.of(patch.path()));
        } else if (mapping instanceof DeleteMapping delete) {
            paths.addAll(List.of(delete.value()));
            paths.addAll(List.of(delete.path()));
        } else {
            return null;
        }

        if (paths.isEmpty()) {
            // A mapping that names no path is the class prefix itself - the create
            // routes of this application.
            return new String[]{""};
        }
        return paths.toArray(String[]::new);
    }

    private Set<Class<?>> controllerClasses() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Controller.class));

        Set<Class<?>> classes = new LinkedHashSet<>();
        for (BeanDefinition definition : scanner.findCandidateComponents("com.bssa.assetmanagement")) {
            try {
                classes.add(Class.forName(definition.getBeanClassName()));
            } catch (ClassNotFoundException ex) {
                throw new IllegalStateException(
                        "A controller could not be loaded: " + definition.getBeanClassName(), ex);
            }
        }
        return classes;
    }

    private String classPrefix(Class<?> controller) {
        RequestMapping onClass =
                AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class);
        if (onClass == null || onClass.value().length == 0) {
            return "";
        }
        return onClass.value()[0];
    }

    // -------------------------------------------------------------------------
    // The templates
    // -------------------------------------------------------------------------

    /** Every page the application ships, from the classpath copy the server uses. */
    private List<Path> templates() {
        URL root = getClass().getResource("/templates");
        assertNotNull(root, "the application's templates are not on the classpath");

        List<Path> found = new ArrayList<>();
        try {
            Files.walk(Path.of(root.toURI()))
                    .filter(path -> path.getFileName().toString().endsWith(".html"))
                    .sorted()
                    .forEach(found::add);
        } catch (URISyntaxException | IOException ex) {
            throw new IllegalStateException(
                    "Could not walk the template directory at " + root + ": " + ex.getMessage(), ex);
        }
        return found;
    }

    private String read(Path template) {
        try {
            return Files.readString(template, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("Could not read " + template + ": " + ex.getMessage(), ex);
        }
    }

    /**
     * The inside of every {@code @{...}} in a page, matched on balanced braces
     * because a link carries parameters and those carry expressions of their own -
     * {@code @{/users/{userId}/edit(userId=${user.userId})}} has three closing
     * braces before the link itself ends.
     */
    private List<String> linkExpressions(String html) {
        List<String> expressions = new ArrayList<>();
        int cursor = 0;

        while (true) {
            int start = html.indexOf("@{", cursor);
            if (start < 0) {
                return expressions;
            }

            int depth = 0;
            int index = start + 1;
            for (; index < html.length(); index++) {
                char c = html.charAt(index);
                if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth--;
                    if (depth == 0) {
                        break;
                    }
                }
            }

            if (depth != 0) {
                throw new AssertionError("an @{ is never closed near character " + start + " of "
                        + html.substring(start, Math.min(html.length(), start + 120)));
            }

            expressions.add(html.substring(start + 2, index));
            cursor = index + 1;
        }
    }

    /**
     * Reads one link expression into an address. A parameter list starts at the first
     * {@code (}, a query at the first {@code ?}; anything that is not a leading-slash
     * literal comes back with no path, which the tests report rather than guess at.
     */
    private Link linkOf(Path template, String expression) {
        String body = expression.trim();

        int parameters = body.indexOf('(');
        int query = body.indexOf('?');
        int cut = body.length();
        if (parameters >= 0 || query >= 0) {
            int first = parameters < 0 ? query : (query < 0 ? parameters : Math.min(parameters, query));
            cut = first;
        }
        body = body.substring(0, cut).trim();

        boolean stylesheet = body.startsWith("/css/") || body.startsWith("/images/");
        if (stylesheet) {
            return new Link(template, expression, body, true);
        }
        // A computed address - @{${backUrl}}, @{'/somewhere'}, @{__${x}__} - cannot be
        // compared against a route table without running the page, so it is reported
        // rather than quietly skipped.
        if (!body.startsWith("/") || body.contains("$") || body.contains("__")) {
            return new Link(template, expression, null, false);
        }
        return new Link(template, expression, body, false);
    }

    /** {@code {id}} on both sides of a comparison becomes the same wildcard. */
    private String normalize(String path) {
        String normalized = LINK_VARIABLE.matcher(path).replaceAll("*");

        while (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized.isEmpty() ? "/" : normalized;
    }

    /**
     * The opening tag of an element, up to the {@code >} that ends it - ignoring any
     * {@code >} inside an attribute, which is ordinary in a condition such as
     * {@code th:if="${unreadCount > 0}"}.
     */
    private List<String> openTags(String html, String element) {
        List<String> tags = new ArrayList<>();
        String needle = "<" + element;
        int cursor = 0;

        while (true) {
            int start = html.indexOf(needle, cursor);
            if (start < 0 || start + needle.length() >= html.length()) {
                return tags;
            }

            char following = html.charAt(start + needle.length());
            if (Character.isLetterOrDigit(following) || following == '-') {
                // <form> and <format>: only the element itself counts.
                cursor = start + needle.length();
                continue;
            }

            StringBuilder tag = new StringBuilder();
            char quote = 0;
            int index = start;
            for (; index < html.length(); index++) {
                char c = html.charAt(index);
                tag.append(c);
                if (quote != 0) {
                    if (c == quote) {
                        quote = 0;
                    }
                } else if (c == '"' || c == '\'') {
                    quote = c;
                } else if (c == '>') {
                    break;
                }
            }

            tags.add(tag.toString());
            cursor = start + tag.length();
        }
    }
}
