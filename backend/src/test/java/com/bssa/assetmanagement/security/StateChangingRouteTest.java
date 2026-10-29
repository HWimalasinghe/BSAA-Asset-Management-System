package com.bssa.assetmanagement.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Walks the application's own route table and asks, of every route that can
 * change something, whether an unsigned-in caller can use it and whether a
 * request forged from another site would be accepted.
 *
 * These tests read the mappings rather than a hand-written list of URLs on
 * purpose: a list goes stale the day somebody adds a controller, and the
 * question it stops answering is exactly the one that matters - whether the next
 * route is as well closed as the last one. Because the CSRF filter and the
 * authorization filter both run before any controller method is reached, none of
 * these requests can alter data.
 */
@SpringBootTest
class StateChangingRouteTest {

    /** Words that describe a change; a route carrying one must never answer a GET. */
    private static final Pattern MUTATION_IN_PATH = Pattern.compile(
            "(retire|cancel|resolve|fulfill-|toggle|deactivate|activate|delete|remove|mark|read-all|/read"
                    + "|return-to-service|update|create|import|status)",
            Pattern.CASE_INSENSITIVE);

    /** The mappings that answer a change rather than a read. */
    private static final List<Class<? extends Annotation>> MUTATION_MAPPINGS = List.of(
            PostMapping.class, PutMapping.class, PatchMapping.class, DeleteMapping.class);

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
    }

    /**
     * Every route that can change something is closed twice over: without a
     * CSRF token the request is refused outright, and with a valid token - which
     * is what a cross-site form cannot obtain - an anonymous caller is turned
     * away at the login page instead of reaching the handler.
     */
    @Test
    void everyStateChangingRouteRefusesAnUnsignedInCallerAndDemandsAToken() throws Exception {
        List<String> routes = stateChangingRoutes();
        assertFalse(routes.isEmpty(), "the route scan should have found the application's POST routes");

        for (String route : routes) {
            mockMvc.perform(post(route))
                    .andExpect(status().isForbidden());

            MvcResult withToken = mockMvc.perform(post(route).with(csrf()))
                    .andExpect(status().is3xxRedirection())
                    .andReturn();

            String destination = withToken.getResponse().getRedirectedUrl();
            assertNotNull(destination, route + " should redirect an anonymous caller");
            assertTrue(destination.startsWith("/login"),
                    route + " sent an unsigned-in caller somewhere other than the sign-in page: " + destination);
        }
    }

    /**
     * A GET must not change anything: browsers, crawlers and prefetching can
     * repeat one without any intent, and a token cannot be attached to it. Routes
     * that only show a form are allowed to keep a word describing what the form
     * will do, so only paths that name an action are refused here.
     */
    @Test
    void noReadRoutePerformsAStateChange() {
        List<String> offenders = new ArrayList<>();

        for (Method method : controllerMethods()) {
            GetMapping read = AnnotatedElementUtils.findMergedAnnotation(method, GetMapping.class);
            if (read == null) {
                continue;
            }

            List<String> declared = pathsOf(read);
            // A read mapping that names no path is the class prefix itself, and is
            // still a route a browser will repeat without meaning to.
            List<String> paths = declared.isEmpty() ? List.of("") : declared;

            for (String path : fullPaths(method, paths)) {
                // A route that renders a page or a form is a read, however the
                // form behind it is named.
                if (MUTATION_IN_PATH.matcher(path).find() && !rendersAForm(method)) {
                    offenders.add(method.getDeclaringClass().getSimpleName() + "." + method.getName() + " -> " + path);
                }
            }
        }

        assertTrue(offenders.isEmpty(),
                "these routes change data through a link, which no CSRF token can cover: " + offenders);
    }

    /**
     * The guard above trusts its own scan, so the scan is checked too: the words
     * it looks for must still match the routes the application actually exposes,
     * or a future mutation route could slip through unnoticed. The negative
     * half matters just as much - a scan that quietly collected read routes as
     * well would look more thorough than it is, and a URL that answers nothing
     * would be counted as covered.
     */
    @Test
    void theRouteScanSeesEveryControllerInTheApplication() {
        List<String> postRoutes = stateChangingRoutes();

        assertTrue(postRoutes.contains("/properties/1/retire"),
                "the scan should have found the asset retire route: " + postRoutes);
        assertTrue(postRoutes.contains("/requests/1/cancel"),
                "the scan should have found the request cancel route: " + postRoutes);
        assertTrue(postRoutes.contains("/incidents/1/resolve"),
                "the scan should have found the incident resolve route: " + postRoutes);
        assertTrue(postRoutes.contains("/notifications/1/read"),
                "the scan should have found the notification read route: " + postRoutes);
        assertTrue(postRoutes.contains("/users/status"),
                "the scan should have found the account status route: " + postRoutes);

        // These are reached by a link and answer nothing to a POST, so seeing
        // them here would mean the scan was listing paths rather than changes.
        assertFalse(postRoutes.contains("/api/health"),
                "a read-only route should not be described as state-changing: " + postRoutes);
        assertFalse(postRoutes.contains("/reports"),
                "a report page should not be described as state-changing: " + postRoutes);
        assertFalse(postRoutes.contains(""),
                "the scan should not invent a route out of a mapping that names no path: " + postRoutes);

        // A mapping that names no path is the class prefix itself, and those are
        // the application's create routes.
        assertTrue(postRoutes.contains("/properties"),
                "the scan should have read a pathless POST as the class prefix: " + postRoutes);
    }

    // ------------------------------------------------------------------------
    // Route table
    // ------------------------------------------------------------------------

    /** Every path that answers POST, PUT, PATCH or DELETE, with placeholders filled in. */
    private List<String> stateChangingRoutes() {
        List<String> routes = new ArrayList<>();

        for (Method method : controllerMethods()) {
            List<String> paths = new ArrayList<>();
            boolean changesState = false;

            for (Class<? extends Annotation> mappingType : MUTATION_MAPPINGS) {
                Annotation mapping =
                        AnnotatedElementUtils.findMergedAnnotation(method, mappingType);
                if (mapping == null) {
                    continue;
                }
                changesState = true;
                paths.addAll(pathsOf(mapping));
            }

            if (!changesState) {
                // A route that answers a read is not the subject of this test, and
                // counting it here would let a path that no POST ever reaches be
                // reported as guarded.
                continue;
            }

            if (paths.isEmpty()) {
                // @PostMapping with no path of its own is the class prefix.
                paths.add("");
            }

            for (String path : fullPaths(method, paths)) {
                routes.add(fillPlaceholders(path));
            }
        }

        return routes.stream().distinct().sorted().toList();
    }

    private List<Method> controllerMethods() {
        List<Method> methods = new ArrayList<>();

        for (Class<?> controller : controllerClasses()) {
            for (Method method : controller.getDeclaredMethods()) {
                if (method.isSynthetic()) {
                    continue;
                }
                methods.add(method);
            }
        }

        return methods;
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
                throw new IllegalStateException("A controller could not be loaded: " + definition.getBeanClassName(), ex);
            }
        }
        return classes;
    }

    /** A method with no mapping annotation is a helper, not a route. */
    private boolean isRoute(Method method) {
        return AnnotatedElementUtils.hasAnnotation(method, RequestMapping.class);
    }

    private boolean rendersAForm(Method method) {
        // Show-a-form handlers return a template name and are reached by a link;
        // they are named here so the check above stays about real changes.
        return method.getName().startsWith("show") || method.getName().startsWith("list")
                || method.getName().startsWith("view") || method.getName().startsWith("new");
    }

    /** Joins the class prefix to the method paths. */
    private List<String> fullPaths(Method method, List<String> methodPaths) {
        if (!isRoute(method)) {
            return List.of();
        }

        String prefix = classPrefix(method.getDeclaringClass());

        return methodPaths.stream().map(path -> prefix + path).toList();
    }

    private String classPrefix(Class<?> controller) {
        RequestMapping onClass =
                AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class);
        if (onClass == null || onClass.value().length == 0) {
            return "";
        }
        return onClass.value()[0];
    }

    private List<String> pathsOf(Annotation mapping) {
        if (mapping == null) {
            return List.of();
        }
        if (mapping instanceof PostMapping post) {
            return joined(post.value(), post.path());
        }
        if (mapping instanceof PutMapping put) {
            return joined(put.value(), put.path());
        }
        if (mapping instanceof PatchMapping patch) {
            return joined(patch.value(), patch.path());
        }
        if (mapping instanceof DeleteMapping delete) {
            return joined(delete.value(), delete.path());
        }
        if (mapping instanceof GetMapping get) {
            return joined(get.value(), get.path());
        }
        return List.of();
    }

    private List<String> joined(String[] first, String[] second) {
        List<String> paths = new ArrayList<>(List.of(first));
        paths.addAll(List.of(second));
        return paths;
    }

    /** {id} and friends become a sample value, so the URL can actually be called. */
    private String fillPlaceholders(String path) {
        Matcher matcher = Pattern.compile("\\{[^}]+}").matcher(path);
        StringBuilder filled = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(filled, "1");
        }
        matcher.appendTail(filled);
        return filled.toString();
    }
}
