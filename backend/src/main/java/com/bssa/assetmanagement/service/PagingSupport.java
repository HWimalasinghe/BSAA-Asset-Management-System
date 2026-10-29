package com.bssa.assetmanagement.service;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.util.List;
import java.util.Map;

/**
 * Turns raw list-page request parameters into a validated {@link Sort} and
 * {@link PageRequest}.
 *
 * Page number, page size and sort order all arrive from the address bar, so they
 * must not reach the database unchecked: an arbitrary sort property would be
 * resolved against the JPA metamodel, and an unbounded offset is paid for on
 * every click. Each list declares the sort keys it accepts, and anything
 * unknown or missing falls back to that list's default instead of turning the
 * page into an error page.
 */
public final class PagingSupport {

    /** Page sizes the list pages offer. Anything else is normalised. */
    public static final List<Integer> ALLOWED_PAGE_SIZES = List.of(10, 25, 50, 100);
    public static final int DEFAULT_PAGE_SIZE = 25;

    /**
     * Ceiling on the requested page. Pages past the last one return no rows, so
     * a huge offset is both pointless and expensive to compute.
     */
    public static final int MAX_PAGE = 10_000;

    /**
     * Ceiling on a free-text search term. Long enough for a code, a name or a
     * serial number, short enough that a pasted paragraph cannot turn into a
     * table scan.
     */
    public static final int MAX_SEARCH_LENGTH = 100;

    private static final String DEFAULT_DIRECTION = "asc";

    private PagingSupport() {
    }

    /**
     * The search term as a list query should receive it: trimmed, lower-cased,
     * or null when the box was left empty, so that a blank search means "show
     * everything" rather than "match nothing".
     *
     * @throws IllegalArgumentException when the term is longer than the list
     *                                  can sensibly search
     */
    public static String normalizedSearch(String search, int maxLength) {
        if (search == null) {
            return null;
        }
        String trimmed = search.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > maxLength) {
            throw new IllegalArgumentException(
                    "Search text must be " + maxLength + " characters or fewer.");
        }
        return trimmed.toLowerCase();
    }

    /** Same, with the ceiling every list in this application uses. */
    public static String normalizedSearch(String search) {
        return normalizedSearch(search, MAX_SEARCH_LENGTH);
    }

    /**
     * Builds a LIKE pattern, escaping the wildcards the user typed so that
     * searching for "100%" or "A_B" finds the literal text instead of turning
     * the term into a pattern that matches unrelated rows.
     *
     * The queries pair this with {@code ESCAPE '!'}, so '!' itself has to be
     * doubled first.
     */
    public static String likePattern(String term) {
        String escaped = term
                .replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_");
        return "%" + escaped + "%";
    }

    /**
     * Builds the order for a list.
     *
     * @param sortField      requested sort key, may be null or unknown
     * @param direction      requested direction, anything but "desc" means ascending
     * @param allowedSorts   the only sort keys accepted, mapped to the property they order by
     * @param defaultSortKey key used when the requested sort is absent or not allowed
     * @param tieBreaker     unique property (the primary key) appended so that rows
     *                       sharing a value keep the same relative order on every page
     */
    public static Sort sort(
            String sortField,
            String direction,
            Map<String, String> allowedSorts,
            String defaultSortKey,
            String tieBreaker) {

        String key = safeSortKey(sortField, allowedSorts, defaultSortKey);
        String property = allowedSorts.get(key);
        Sort primary = Sort.by(isDescending(direction) ? Sort.Direction.DESC : Sort.Direction.ASC, property);

        // Ordering by the unique key itself needs no tiebreaker, and repeating it
        // would ask the database for a second, contradictory direction.
        return property.equals(tieBreaker) ? primary : primary.and(Sort.by(Sort.Direction.ASC, tieBreaker));
    }

    /**
     * @param page requested zero-based page, may be null or out of range
     * @param size requested page size, may be null or unsupported
     */
    public static PageRequest pageRequest(
            Integer page,
            Integer size,
            String sortField,
            String direction,
            Map<String, String> allowedSorts,
            String defaultSortKey,
            String tieBreaker) {

        return PageRequest.of(
                safePage(page),
                safeSize(size),
                sort(sortField, direction, allowedSorts, defaultSortKey, tieBreaker));
    }

    /** Requests beyond the last page are clamped so the pager cannot be walked into absurd offsets. */
    public static int safePage(Integer page) {
        if (page == null || page < 0) {
            return 0;
        }
        return Math.min(page, MAX_PAGE);
    }

    public static int safeSize(Integer size) {
        return (size == null || !ALLOWED_PAGE_SIZES.contains(size)) ? DEFAULT_PAGE_SIZE : size;
    }

    /** Accepts only a known sort key; unknown or missing keys resolve to the default. */
    public static String safeSortKey(String sortKey, Map<String, String> allowedSorts, String defaultSortKey) {
        if (sortKey != null && allowedSorts.containsKey(sortKey)) {
            return sortKey;
        }
        if (!allowedSorts.containsKey(defaultSortKey)) {
            throw new IllegalArgumentException(
                    "The default sort '" + defaultSortKey + "' is not an allowed sort for this list.");
        }
        return defaultSortKey;
    }

    /** Echoes back only "asc" or "desc" so header links and form fields stay well-formed. */
    public static String safeDirection(String direction) {
        return direction != null && "desc".equalsIgnoreCase(direction.trim())
                ? "desc"
                : DEFAULT_DIRECTION;
    }

    /**
     * First row shown on the page, counted from one, for a "showing 1-25 of 137"
     * line. Zero when there is nothing to show.
     */
    public static int firstRow(int pageNumber, int pageSize, long totalElements) {
        if (totalElements <= 0) {
            return 0;
        }
        long offset = (long) pageNumber * pageSize;
        // A page typed into the address bar can point past the end of the list.
        // Reporting a row there would be a fiction, and a first row above the
        // total reads backwards ("showing 101-60 of 60"), so an empty page
        // reports no rows at all.
        return offset >= totalElements ? 0 : (int) offset + 1;
    }

    /** Last row shown on the page, never past the total. */
    public static int lastRow(int pageNumber, int pageSize, long totalElements) {
        int first = firstRow(pageNumber, pageSize, totalElements);
        if (first == 0) {
            return 0;
        }
        return (int) Math.min((long) first - 1 + pageSize, totalElements);
    }

    /**
     * First page number in a window of at most {@code window} links centred on
     * the current page, so a register with hundreds of pages still shows a
     * usable row of links.
     */
    public static int windowStart(int pageNumber, int totalPages, int window) {
        int half = window / 2;
        int start = pageNumber - half;
        if (start < 0) {
            return 0;
        }
        // Keep the window full by pulling it back when it overruns the end.
        return Math.min(start, Math.max(0, totalPages - window));
    }

    /** Last page number (inclusive) in the window; below the start when there are no pages. */
    public static int windowEnd(int pageNumber, int totalPages, int window) {
        return Math.min(windowStart(pageNumber, totalPages, window) + window - 1, totalPages - 1);
    }

    private static boolean isDescending(String direction) {
        return direction != null && "desc".equalsIgnoreCase(direction.trim());
    }
}
