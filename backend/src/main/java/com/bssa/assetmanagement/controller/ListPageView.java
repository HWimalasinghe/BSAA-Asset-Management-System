package com.bssa.assetmanagement.controller;

import com.bssa.assetmanagement.service.PagingSupport;
import org.springframework.data.domain.Page;
import org.springframework.ui.Model;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * The model every paged list page needs, in one place.
 *
 * The shared fragments in fragments/list-nav.html (sortable column headers and
 * the pager) read a fixed set of attributes, so a controller that renders a
 * paged list has to provide exactly those. Rather than copy the same eleven
 * addAttribute calls into every list, each controller calls
 * {@link #addListState} and {@link #addPageAttributes} and stays about its own
 * data.
 */
final class ListPageView {

    /** How many page numbers the pager shows at once. */
    private static final int PAGE_WINDOW = 5;

    private ListPageView() {
    }

    /**
     * Records the order, page size and active query that the filter form, the
     * column headers and the pager links are built from.
     *
     * @param filterQuery the accepted filters and page size only, as produced
     *                    by {@link Query}; never a raw request parameter and
     *                    never the sort, which is added here
     */
    static void addListState(
            Model model,
            String sortKey,
            String direction,
            int pageSize,
            String filterQuery,
            boolean filtersActive) {

        model.addAttribute("sortKey", sortKey);
        model.addAttribute("sortDirection", direction);
        model.addAttribute("pageSize", pageSize);
        model.addAttribute("pageSizes", PagingSupport.ALLOWED_PAGE_SIZES);
        // A column-header link cannot reuse the query below: it names the order
        // it is about to switch to, and a repeated parameter would be answered
        // with the first - the old - value, leaving the column unsortable.
        model.addAttribute("headerQuery", filterQuery);
        model.addAttribute("filterQuery", withOrder(filterQuery, sortKey, direction));
        model.addAttribute("filtersActive", filtersActive);
    }

    private static String withOrder(String filterQuery, String sortKey, String direction) {
        String order = query().add("sort", sortKey).add("dir", direction).toString();
        if (order.isEmpty()) {
            return filterQuery;
        }
        return filterQuery.isEmpty() ? order : filterQuery + "&" + order;
    }

    /**
     * The state for a paged list whose order cannot be changed.
     *
     * The work queues on the request, incident and notification pages are always
     * newest first, so they have no column headers to click - but their pager
     * still needs the query that carries the reader's filters and page size.
     */
    static void addPagingState(
            Model model,
            int pageSize,
            String filterQuery,
            boolean filtersActive) {

        model.addAttribute("pageSize", pageSize);
        model.addAttribute("pageSizes", PagingSupport.ALLOWED_PAGE_SIZES);
        model.addAttribute("headerQuery", filterQuery);
        model.addAttribute("filterQuery", filterQuery);
        model.addAttribute("filtersActive", filtersActive);
    }

    /** The rows plus the arithmetic the pager renders, from an already validated page. */
    static void addPageAttributes(Model model, String rowAttribute, Page<?> result) {
        model.addAttribute(rowAttribute, result.getContent());
        model.addAttribute("pageNumber", result.getNumber());
        model.addAttribute("pageSize", result.getSize());
        model.addAttribute("totalPages", result.getTotalPages());
        model.addAttribute("totalElements", result.getTotalElements());
        model.addAttribute("firstRow",
                PagingSupport.firstRow(result.getNumber(), result.getSize(), result.getTotalElements()));
        model.addAttribute("lastRow",
                PagingSupport.lastRow(result.getNumber(), result.getSize(), result.getTotalElements()));
        model.addAttribute("windowStart",
                PagingSupport.windowStart(result.getNumber(), result.getTotalPages(), PAGE_WINDOW));
        model.addAttribute("windowEnd",
                PagingSupport.windowEnd(result.getNumber(), result.getTotalPages(), PAGE_WINDOW));
    }

    /**
     * A query with nothing in it, so the pager of a list that failed validation
     * renders no links and no "showing 1-0 of 0" line.
     */
    static void addEmptyPageAttributes(Model model, String rowAttribute) {
        model.addAttribute(rowAttribute, java.util.List.of());
        model.addAttribute("pageNumber", 0);
        model.addAttribute("totalPages", 0);
        model.addAttribute("totalElements", 0L);
        model.addAttribute("firstRow", 0);
        model.addAttribute("lastRow", 0);
        model.addAttribute("windowStart", 0);
        model.addAttribute("windowEnd", -1);
    }

    static Query query() {
        return new Query();
    }

    /**
     * Accumulates a URL-encoded query string.
     *
     * Blanks and missing values are left out so links stay short, and every
     * value is encoded, so a search term containing &amp;, = or a space cannot
     * invent an extra parameter when it is copied into a link.
     */
    static final class Query {

        private final StringBuilder text = new StringBuilder();

        Query add(String name, Object value) {
            if (value == null) {
                return this;
            }
            String asText = String.valueOf(value);
            if (asText.isBlank()) {
                return this;
            }
            if (text.length() > 0) {
                text.append('&');
            }
            text.append(name).append('=')
                    .append(URLEncoder.encode(asText, StandardCharsets.UTF_8));
            return this;
        }

        @Override
        public String toString() {
            return text.toString();
        }
    }
}
