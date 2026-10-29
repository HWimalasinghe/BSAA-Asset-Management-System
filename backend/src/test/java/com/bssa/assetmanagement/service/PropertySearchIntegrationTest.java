package com.bssa.assetmanagement.service;

import com.bssa.assetmanagement.entity.PhysicalArea;
import com.bssa.assetmanagement.entity.Property;
import com.bssa.assetmanagement.entity.PropertyCategory;
import com.bssa.assetmanagement.entity.PropertyType;
import com.bssa.assetmanagement.repository.PhysicalAreaRepository;
import com.bssa.assetmanagement.repository.PropertyCategoryRepository;
import com.bssa.assetmanagement.repository.PropertyRepository;
import com.bssa.assetmanagement.repository.PropertyTypeRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the searchable, paged asset register against the real database, because
 * the generated SQL is the thing under test: the LIKE escaping, the optional
 * filter clauses, the count query and the ORDER BY built from a validated sort
 * key (including the joins behind type, category and area) cannot be checked
 * against mocks.
 *
 * Assertions are made about rows this test seeds, so pre-existing development
 * data cannot make them brittle.
 */
@SpringBootTest
@Transactional
class PropertySearchIntegrationTest {

    @Autowired
    private PropertyService propertyService;

    @Autowired
    private PropertyRepository propertyRepository;

    @Autowired
    private PropertyTypeRepository propertyTypeRepository;

    @Autowired
    private PropertyCategoryRepository propertyCategoryRepository;

    @Autowired
    private PhysicalAreaRepository physicalAreaRepository;

    @Test
    void searchFindsAssetsAcrossCodeSerialDescriptionTypeCategoryAndArea() {
        PhysicalArea area = anyActiveArea();
        PropertyType type = newType(newCategory());
        Property property = newProperty(type, area, "AVAILABLE");

        property.setSerialNumber("SN-UNIQUE-" + UUID.randomUUID());
        property.setDescription("Docking station for the finance floor");
        propertyRepository.saveAndFlush(property);

        assertTrue(codes(propertyService.searchProperties(
                property.getPropertyCode(), null, null, null, null, null, null, null, 0, 25, null, null))
                .contains(property.getPropertyCode()), "asset code is searchable");

        assertTrue(codes(propertyService.searchProperties(
                "SN-UNIQUE", null, null, null, null, null, null, null, 0, 25, null, null))
                .contains(property.getPropertyCode()), "serial number is searchable");

        assertTrue(codes(propertyService.searchProperties(
                "docking STATION", null, null, null, null, null, null, null, 0, 25, null, null))
                .contains(property.getPropertyCode()), "description is searchable, case-insensitively");

        assertTrue(codes(propertyService.searchProperties(
                type.getName(), null, null, null, null, null, null, null, 0, 25, null, null))
                .contains(property.getPropertyCode()), "property type is searchable");

        assertTrue(codes(propertyService.searchProperties(
                type.getCategory().getName(), null, null, null, null, null, null, null, 0, 25, null, null))
                .contains(property.getPropertyCode()), "category is searchable");

        assertTrue(codes(propertyService.searchProperties(
                area.getName(), null, null, null, null, null, null, null, 0, 25, null, null))
                .contains(property.getPropertyCode()), "physical area is searchable");

        List<Property> unmatched = propertyService.searchProperties(
                "nothing-matches-this-" + UUID.randomUUID(), null, null, null, null, null,
                null, null, 0, 25, null, null).getContent();
        assertTrue(unmatched.isEmpty(), "an unrelated term must not match this asset");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "\t"})
    void blankSearchMeansShowEverythingRatherThanMatchNothing(String search) {
        Page<Property> unfiltered = propertyService.searchProperties(
                null, null, null, null, null, null, null, null, 0, 25, null, null);
        Page<Property> blank = propertyService.searchProperties(
                search, null, null, null, null, null, null, null, 0, 25, null, null);

        assertEquals(unfiltered.getTotalElements(), blank.getTotalElements(),
                "a blank search box must not narrow the register");
    }

    @Test
    void typedWildcardsAreMatchedAsLiteralText() {
        PropertyType type = anyActiveType();
        Property property = newProperty(type, anyActiveArea(), "AVAILABLE");
        property.setDescription("100% recycled casing");
        propertyRepository.saveAndFlush(property);

        List<String> matches = codes(propertyService.searchProperties(
                "100%", null, null, null, null, null, null, null, 0, 25, null, null));

        assertTrue(matches.contains(property.getPropertyCode()),
                "a percent sign in the search text must match itself");
        assertTrue(matches.size() < propertyRepository.count(),
                "'%' must not be handed to LIKE as a wildcard that matches every row");
    }

    @Test
    void filtersCombineAndNarrowTheRegister() {
        PropertyType type = newType(newCategory());
        PhysicalArea area = anyActiveArea();

        Property match = newProperty(type, area, "DAMAGED");
        match.setCondition("BROKEN");
        match.setPurchaseDate(LocalDate.of(2023, 6, 15));
        propertyRepository.saveAndFlush(match);

        // Same type and area, but a different status, condition and date.
        Property otherStatus = newProperty(type, area, "AVAILABLE");
        otherStatus.setCondition("BROKEN");
        otherStatus.setPurchaseDate(LocalDate.of(2023, 6, 15));
        propertyRepository.saveAndFlush(otherStatus);

        Property otherCondition = newProperty(type, area, "DAMAGED");
        otherCondition.setCondition("GOOD");
        otherCondition.setPurchaseDate(LocalDate.of(2023, 6, 15));
        propertyRepository.saveAndFlush(otherCondition);

        Property outsideRange = newProperty(type, area, "DAMAGED");
        outsideRange.setCondition("BROKEN");
        outsideRange.setPurchaseDate(LocalDate.of(2019, 1, 2));
        propertyRepository.saveAndFlush(outsideRange);

        List<String> combined = codes(propertyService.searchProperties(
                null, "DAMAGED", "BROKEN", type.getCategory().getCategoryId(),
                type.getPropertyTypeId(), area.getAreaId(),
                LocalDate.of(2023, 1, 1), LocalDate.of(2023, 12, 31), 0, 25, null, null));

        assertEquals(List.of(match.getPropertyCode()), combined,
                "every condition must hold at once: status, condition, category, type, area and date range");
    }

    @Test
    void pagingVisitsEveryMatchingRowExactlyOnce() {
        PropertyType type = newType(newCategory());
        PhysicalArea area = anyActiveArea();
        List<String> seeded = new ArrayList<>();
        // Twelve rows so that the offered page size of 10 spans two pages.
        for (int i = 0; i < 12; i++) {
            seeded.add(propertyRepository.saveAndFlush(newProperty(type, area, "AVAILABLE")).getPropertyCode());
        }

        Page<Property> first = propertyService.searchProperties(
                type.getName(), null, null, null, type.getPropertyTypeId(), null, null, null,
                0, 10, "code", "asc");

        assertEquals(12, first.getTotalElements(), "the count query must cover the whole filtered set");
        assertEquals(2, first.getTotalPages(), "twelve rows ten per page need two pages");
        assertEquals(10, first.getContent().size(), "only one page of rows is fetched");
        assertTrue(first.hasNext(), "a page that is not the last must report a next page");

        List<String> walked = new ArrayList<>();
        for (int page = 0; page < first.getTotalPages(); page++) {
            walked.addAll(codes(propertyService.searchProperties(
                    type.getName(), null, null, null, type.getPropertyTypeId(), null, null, null,
                    page, 10, "code", "asc")));
        }

        assertTrue(walked.containsAll(seeded), "every matching row must appear on some page");
        assertEquals(walked.size(), new java.util.LinkedHashSet<>(walked).size(),
                "no row may appear on two pages");
        assertEquals(sortedCopy(walked), walked, "paging must follow the requested order, not the storage order");
    }

    @Test
    void sortingIsStableWhenManyRowsShareAValue() {
        PropertyType type = newType(newCategory());
        PhysicalArea area = anyActiveArea();
        // Identical status, so the primary sort value ties every single time.
        for (int i = 0; i < 12; i++) {
            propertyRepository.saveAndFlush(newProperty(type, area, "AVAILABLE"));
        }

        List<String> paged = new ArrayList<>();
        for (int page = 0; page < 2; page++) {
            paged.addAll(codes(propertyService.searchProperties(
                    type.getName(), "AVAILABLE", null, null, type.getPropertyTypeId(), null,
                    null, null, page, 10, "status", "asc")));
        }

        List<String> whole = codes(propertyService.searchProperties(
                type.getName(), "AVAILABLE", null, null, type.getPropertyTypeId(), null,
                null, null, 0, 100, "status", "asc"));

        assertEquals(whole, paged,
                "walking small pages must reproduce the large-page order exactly");
        assertEquals(12, whole.size(), "the fixture should have produced rows");
    }

    @ParameterizedTest
    @ValueSource(strings = {"code", "type", "category", "area", "serial", "status", "condition", "purchaseDate", "registered"})
    void everyAllowedSortKeyIsExecutable(String sortKey) {
        PropertyType type = newType(newCategory());
        propertyRepository.saveAndFlush(newProperty(type, anyActiveArea(), "AVAILABLE"));

        Page<Property> page = propertyService.searchProperties(
                null, null, null, null, null, null, null, null, 0, 10, sortKey, "desc");

        assertFalse(page.getContent().isEmpty(), "sorting by " + sortKey + " must still return rows");
    }

    @Test
    void sortingByADatabaseColumnIsRejectedAndDefaultsToNewestFirst() {
        Page<Property> page = propertyService.searchProperties(
                null, null, null, null, null, null, null, null, 0, 10, "(select 1)", "asc");

        assertEquals(1L, page.getSort().stream().count(),
                "an injected sort key must not become part of the query order");
        assertEquals("propertyId",
                page.getSort().iterator().next().getProperty(),
                "an unknown or hand-crafted sort key must fall back to the default order");
    }

    @Test
    void unsupportedPageSizeAndNegativePageAreNormalised() {
        Page<Property> page = propertyService.searchProperties(
                null, null, null, null, null, null, null, null, -4, 5000, null, null);

        assertEquals(PagingSupport.DEFAULT_PAGE_SIZE, page.getSize(),
                "an unoffered page size must not become the query limit");
        assertEquals(0, page.getNumber(), "a negative page must not produce a negative offset");
    }

    @Test
    void invalidFilterValuesAreRejectedBeforeAnyQueryRuns() {
        assertThrows(IllegalArgumentException.class, () -> propertyService.searchProperties(
                null, "NOT_A_STATUS", null, null, null, null, null, null, 0, 25, null, null));

        assertThrows(IllegalArgumentException.class, () -> propertyService.searchProperties(
                null, null, "WORN_OUT", null, null, null, null, null, 0, 25, null, null));

        assertThrows(IllegalArgumentException.class, () -> propertyService.searchProperties(
                null, null, null, null, null, null,
                LocalDate.of(2024, 5, 1), LocalDate.of(2024, 1, 1), 0, 25, null, null));
    }

    @Test
    void exportReceivesEveryFilteredRowInTheListOrder() {
        PropertyType type = newType(newCategory());
        PhysicalArea area = anyActiveArea();
        for (int i = 0; i < 12; i++) {
            propertyRepository.saveAndFlush(newProperty(type, area, "LOST"));
        }

        Page<Property> list = propertyService.searchProperties(
                null, "LOST", null, null, type.getPropertyTypeId(), null, null, null,
                0, 10, "code", "asc");
        List<Property> export = propertyService.findMatchingProperties(
                null, "LOST", null, null, type.getPropertyTypeId(), null, null, null, "code", "asc");

        assertEquals(10, list.getContent().size(), "the list itself stays on one page");
        assertEquals(list.getTotalElements(), export.size(),
                "the export must cover the whole filtered set, not only the visible page");
        assertEquals(export.stream().map(Property::getPropertyCode).sorted().toList(),
                export.stream().map(Property::getPropertyCode).toList(),
                "the export must follow the requested order");
    }

    private List<String> codes(Page<Property> page) {
        return page.getContent().stream().map(Property::getPropertyCode).toList();
    }

    private List<String> sortedCopy(List<String> values) {
        List<String> copy = new ArrayList<>(values);
        copy.sort(String::compareTo);
        return copy;
    }

    private PropertyType newType(PropertyCategory category) {
        PropertyType type = new PropertyType();
        type.setCategory(category);
        type.setName("TYPE-" + UUID.randomUUID().toString().substring(0, 10));
        type.setUsageType("COMMON");
        return propertyTypeRepository.saveAndFlush(type);
    }

    private PropertyCategory newCategory() {
        PropertyCategory category = new PropertyCategory();
        category.setName("CAT-" + UUID.randomUUID().toString().substring(0, 10));
        category.setActive(true);
        return propertyCategoryRepository.saveAndFlush(category);
    }

    private PropertyType anyActiveType() {
        List<PropertyType> types = propertyTypeRepository.findAllByActiveTrueOrderByNameAsc();
        return types.isEmpty() ? newType(newCategory()) : types.get(0);
    }

    private PhysicalArea anyActiveArea() {
        List<PhysicalArea> areas = physicalAreaRepository.findAllByActiveTrueOrderByNameAsc();
        assertFalse(areas.isEmpty(), "schema.sql must seed at least one physical area");
        return areas.get(0);
    }

    private Property newProperty(PropertyType type, PhysicalArea area, String status) {
        Property property = new Property();
        property.setPropertyCode("SRCH-" + UUID.randomUUID());
        property.setPropertyType(type);
        property.setArea(area);
        property.setPurchaseDate(LocalDate.of(2024, 1, 1));
        property.setCondition("GOOD");
        property.setStatus(status);
        return property;
    }
}
