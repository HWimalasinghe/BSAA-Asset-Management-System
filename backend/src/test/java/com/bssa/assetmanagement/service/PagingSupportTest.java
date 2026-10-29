package com.bssa.assetmanagement.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The gate in front of every paged list: whatever the address bar asks for must
 * become a bounded page, an offered page size and one of the declared sort
 * properties, or it is replaced by the list's default.
 */
class PagingSupportTest {

    private static final Map<String, String> SORTS = Map.of(
            "code", "propertyCode",
            "status", "status",
            "registered", "propertyId");

    private static final String TIE_BREAKER = "propertyId";

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"-1", "-100"})
    void aMissingOrNegativePageBecomesTheFirstPage(String requested) {
        Integer page = requested == null || requested.isEmpty()
                ? null : Integer.valueOf(requested);

        assertEquals(0, PagingSupport.safePage(page));
    }

    @Test
    void aRidiculouslyDeepPageIsCappedRatherThanAskedFor() {
        assertEquals(PagingSupport.MAX_PAGE, PagingSupport.safePage(999_999_999));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 1, 7, 999, 100_000})
    void onlyAnOfferedPageSizeIsUsed(int requested) {
        assertEquals(PagingSupport.DEFAULT_PAGE_SIZE, PagingSupport.safeSize(requested));
    }

    @Test
    void aRequestThatMentionsNoPageSizeUsesTheDefault() {
        assertEquals(PagingSupport.DEFAULT_PAGE_SIZE, PagingSupport.safeSize(null));
    }

    @ParameterizedTest
    @ValueSource(ints = {10, 25, 50, 100})
    void anOfferedPageSizeIsKept(int requested) {
        assertEquals(requested, PagingSupport.safeSize(requested));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"passwordHash", "propertyType", "(select 1)", "CODE", " status"})
    void anUnknownSortKeyFallsBackToTheDefault(String requested) {
        assertEquals("registered", PagingSupport.safeSortKey(requested, SORTS, "registered"));
    }

    @Test
    void aKnownSortKeyIsUsedAsGiven() {
        assertEquals("code", PagingSupport.safeSortKey("code", SORTS, "registered"));
    }

    @Test
    void aDefaultThatIsNotItselfAllowedIsAProgrammingMistake() {
        assertThrows(IllegalArgumentException.class,
                () -> PagingSupport.safeSortKey("unknown", SORTS, "secretColumn"));
    }

    @Test
    void theUniqueKeyIsAppendedSoEqualRowsKeepTheirOrder() {
        Sort sort = PagingSupport.sort("status", "asc", SORTS, "registered", TIE_BREAKER);

        assertEquals(2, sort.stream().count());
        assertEquals(Sort.Direction.ASC, sort.getOrderFor("status").getDirection());
        assertEquals(TIE_BREAKER, sort.getOrderFor(TIE_BREAKER).getProperty());
        assertEquals(Sort.Direction.ASC, sort.getOrderFor(TIE_BREAKER).getDirection(),
                "the tiebreaker stays ascending so the order is deterministic");
    }

    @Test
    void orderingByTheUniqueKeyAloneNeedsNoTiebreaker() {
        Sort sort = PagingSupport.sort("registered", "desc", SORTS, "registered", TIE_BREAKER);

        assertEquals(1, sort.stream().count());
        assertEquals(Sort.Direction.DESC, sort.getOrderFor(TIE_BREAKER).getDirection());
    }

    @ParameterizedTest
    @ValueSource(strings = {"desc", "DESC", " Desc "})
    void onlyARequestedDescendingOrderIsDescending(String direction) {
        Sort sort = PagingSupport.sort("code", direction, SORTS, "registered", TIE_BREAKER);

        assertEquals(Sort.Direction.DESC, sort.getOrderFor("propertyCode").getDirection());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"asc", "sideways", "up", "1"})
    void anythingElseIsAscending(String direction) {
        Sort sort = PagingSupport.sort("code", direction, SORTS, "registered", TIE_BREAKER);

        assertEquals(Sort.Direction.ASC, sort.getOrderFor("propertyCode").getDirection());
        assertEquals("asc", PagingSupport.safeDirection(direction));
    }

    @Test
    void thePageRequestCarriesTheValidatedValues() {
        PageRequest request = PagingSupport.pageRequest(
                3, 999, "passwordHash", "sideways", SORTS, "registered", TIE_BREAKER);

        assertEquals(3, request.getPageNumber());
        assertEquals(PagingSupport.DEFAULT_PAGE_SIZE, request.getPageSize());
        assertEquals("propertyId", request.getSort().iterator().next().getProperty());
        assertEquals(Sort.Direction.ASC, request.getSort().iterator().next().getDirection());
    }

    @ParameterizedTest
    @CsvSource({
            "0, 0, 2",
            "1, 0, 2",
            "2, 1, 3",
            "3, 2, 4",
            "9, 8, 10",
            "100, 98, 100"
    })
    void theWindowOfPageLinksIsCentredAndNeverRunsOffEitherEnd(
            int pageNumber, int expectedStart, int expectedEnd) {

        int totalPages = 101;
        int window = 3;

        assertEquals(expectedStart, PagingSupport.windowStart(pageNumber, totalPages, window));
        assertEquals(expectedEnd, PagingSupport.windowEnd(pageNumber, totalPages, window));
    }

    @Test
    void aSinglePageWindowsOnItselfAndNoPagesProduceAnEmptyWindow() {
        assertEquals(0, PagingSupport.windowEnd(0, 1, 5));
        // Nothing matched: the end stays below the start so no links render.
        assertEquals(-1, PagingSupport.windowEnd(0, 0, 5));
    }

    @ParameterizedTest
    @CsvSource({
            "0, 25, 60, 1, 25",
            "1, 25, 60, 26, 50",
            "2, 25, 60, 51, 60",
            "0, 25, 0, 0, 0",
            "0, 10, 4, 1, 4",
            // A page address-typed past the end holds no rows, so it must not
            // report a range - and never one that runs backwards.
            "3, 10, 30, 0, 0",
            "99, 25, 60, 0, 0"
    })
    void theSummaryCountsTheRowsActuallyShown(
            int pageNumber, int pageSize, long total, int expectedFirst, int expectedLast) {

        assertEquals(expectedFirst, PagingSupport.firstRow(pageNumber, pageSize, total));
        assertEquals(expectedLast, PagingSupport.lastRow(pageNumber, pageSize, total));
    }
}
