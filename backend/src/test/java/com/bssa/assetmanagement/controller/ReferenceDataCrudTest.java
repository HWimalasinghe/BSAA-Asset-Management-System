package com.bssa.assetmanagement.controller;

import com.bssa.assetmanagement.entity.Department;
import com.bssa.assetmanagement.entity.PhysicalArea;
import com.bssa.assetmanagement.entity.PropertyCategory;
import com.bssa.assetmanagement.entity.PropertyType;
import com.bssa.assetmanagement.repository.DepartmentRepository;
import com.bssa.assetmanagement.repository.PhysicalAreaRepository;
import com.bssa.assetmanagement.repository.PropertyCategoryRepository;
import com.bssa.assetmanagement.repository.PropertyTypeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The four reference lists the Inventory Admin maintains: departments, physical
 * areas, property categories and property types.
 *
 * Nothing here needs the database. The controllers work directly against a
 * repository and turn every refusal into a redirect plus a flash message, which
 * is exactly the behaviour an office user sees - "A department with this name
 * already exists." on the screen they were filling in, rather than a stack trace.
 * So the tests call the controller methods the way the form does and assert what
 * was saved, what was refused, and what the user was told.
 *
 * The three things worth reading twice are the case-insensitive duplicate lookups
 * (the database's unique index would reject the row, but only after the user had
 * been sent to a server error), the self-excluding variant used on update (an edit
 * that keeps its own name must not be refused as a duplicate of itself), and the
 * missing-record paths, which happen whenever somebody keeps a browser tab open
 * over a row that was deactivated or is simply not there.
 */
class ReferenceDataCrudTest {

    private final DepartmentRepository departmentRepository = mock(DepartmentRepository.class);
    private final PhysicalAreaRepository physicalAreaRepository = mock(PhysicalAreaRepository.class);
    private final PropertyCategoryRepository propertyCategoryRepository =
            mock(PropertyCategoryRepository.class);
    private final PropertyTypeRepository propertyTypeRepository = mock(PropertyTypeRepository.class);

    private final DepartmentController departmentController =
            new DepartmentController(departmentRepository);
    private final PhysicalAreaController physicalAreaController =
            new PhysicalAreaController(physicalAreaRepository);
    private final PropertyCategoryController propertyCategoryController =
            new PropertyCategoryController(propertyCategoryRepository);
    private final PropertyTypeController propertyTypeController =
            new PropertyTypeController(propertyTypeRepository, propertyCategoryRepository);

    @BeforeEach
    void setUp() {
        reset(departmentRepository, physicalAreaRepository,
                propertyCategoryRepository, propertyTypeRepository);
    }

    // -------------------------------------------------------------------------
    // Departments
    // -------------------------------------------------------------------------

    @Test
    void departmentListShowsEveryRowIncludingDeactivatedOnes() {
        Department finance = department(1L, "Finance", true);
        Department legacy = department(2L, "Legacy Branch", false);
        when(departmentRepository.findAllByOrderByNameAsc())
                .thenReturn(List.of(legacy, finance));

        Model model = new ConcurrentModel();
        String view = departmentController.listDepartments(model);

        assertEquals("departments/list", view);
        assertEquals(List.of(legacy, finance), model.getAttribute("departments"));
        // The register is ordered by the query, not re-sorted here, and the
        // deactivated row is still listed - an admin has to be able to switch it
        // back on.
        verify(departmentRepository).findAllByOrderByNameAsc();
    }

    @Test
    void createDepartmentSavesATrimmedActiveRow() {
        RedirectAttributes flash = new RedirectAttributesModelMap();

        String view = departmentController.createDepartment(
                "  Finance  ", "Accounts and payroll", flash);

        assertEquals("redirect:/departments", view);
        Department saved = captureSavedDepartment();
        assertEquals("Finance", saved.getName());
        assertEquals("Accounts and payroll", saved.getDescription());
        assertTrue(saved.isActive(), "a new department has to be usable straight away");
        assertEquals("Department created successfully.", flashOf(flash).get("successMessage"));
    }

    @Test
    void createDepartmentRefusesABlankNameAndSavesNothing() {
        RedirectAttributes flash = new RedirectAttributesModelMap();

        String view = departmentController.createDepartment("   ", null, flash);

        assertEquals("redirect:/departments/new", view, "back to the form the user was on");
        assertEquals("Department name is required.", flashOf(flash).get("errorMessage"));
        verify(departmentRepository, never()).save(any());
    }

    @Test
    void createDepartmentRefusesANameThatOnlyDiffersInCase() {
        // The lookup is the case-insensitive one and gets the trimmed value, so
        // typing "finance" is answered by the database's own folding rather than
        // reaching its unique index and producing a server error.
        when(departmentRepository.existsByNameIgnoreCase("finance")).thenReturn(true);
        RedirectAttributes flash = new RedirectAttributesModelMap();

        String view = departmentController.createDepartment(" finance ", "x", flash);

        assertEquals("redirect:/departments/new", view);
        assertEquals("A department with this name already exists.",
                flashOf(flash).get("errorMessage"));
        verify(departmentRepository).existsByNameIgnoreCase("finance");
        verify(departmentRepository, never()).save(any());
    }

    @Test
    void updateDepartmentKeepsADescriptionOnlyWhileThereIsOne() {
        Department existing = department(1L, "Finance", true);
        existing.setDescription("Accounts and payroll");
        when(departmentRepository.findById(1L)).thenReturn(Optional.of(existing));
        RedirectAttributes flash = new RedirectAttributesModelMap();

        // Clearing the box is how an admin removes a description; the blank value
        // has to become null rather than "  ".
        String view = departmentController.updateDepartment(1L, "Finance", "   ", flash);

        assertEquals("redirect:/departments", view);
        assertEquals("Finance", existing.getName());
        assertNull(existing.getDescription());
        assertEquals("Department updated successfully.", flashOf(flash).get("successMessage"));
    }

    @Test
    void updateDepartmentIsNotADuplicateOfItself() {
        Department existing = department(1L, "Finance", true);
        when(departmentRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(departmentRepository
                .existsByNameIgnoreCaseAndDepartmentIdNot("Finance", 1L)).thenReturn(false);
        RedirectAttributes flash = new RedirectAttributesModelMap();

        String view = departmentController.updateDepartment(1L, "Finance", "Still accounts", flash);

        assertEquals("redirect:/departments", view);
        assertEquals("Still accounts", existing.getDescription());
        verify(departmentRepository).save(existing);
    }

    @Test
    void updateDepartmentRefusesANameBelongingToAnotherDepartment() {
        when(departmentRepository.findById(1L)).thenReturn(Optional.of(department(1L, "Finance", true)));
        when(departmentRepository
                .existsByNameIgnoreCaseAndDepartmentIdNot("IT", 1L)).thenReturn(true);
        RedirectAttributes flash = new RedirectAttributesModelMap();

        String view = departmentController.updateDepartment(1L, "IT", null, flash);

        assertEquals("redirect:/departments/edit?id=1", view);
        assertEquals("A department with this name already exists.",
                flashOf(flash).get("errorMessage"));
        verify(departmentRepository, never()).save(any());
    }

    @Test
    void updatingAMissingDepartmentGoesBackToTheListWithoutSaving() {
        when(departmentRepository.findById(99L)).thenReturn(Optional.empty());
        RedirectAttributes flash = new RedirectAttributesModelMap();

        String view = departmentController.updateDepartment(99L, "Finance", null, flash);

        assertEquals("redirect:/departments", view);
        assertEquals("Department not found.", flashOf(flash).get("errorMessage"));
        verify(departmentRepository, never()).save(any());
    }

    @Test
    void deactivatingAndReactivatingADepartmentSayWhichHappened() {
        Department finance = department(1L, "Finance", true);
        when(departmentRepository.findById(1L)).thenReturn(Optional.of(finance));

        RedirectAttributes deactivated = new RedirectAttributesModelMap();
        assertEquals("redirect:/departments",
                departmentController.toggleDepartmentStatus(1L, deactivated));
        assertFalse(finance.isActive());
        assertEquals("Department deactivated successfully.",
                flashOf(deactivated).get("successMessage"));

        RedirectAttributes activated = new RedirectAttributesModelMap();
        assertEquals("redirect:/departments",
                departmentController.toggleDepartmentStatus(1L, activated));
        assertTrue(finance.isActive());
        assertEquals("Department activated successfully.",
                flashOf(activated).get("successMessage"));
    }

    @Test
    void togglingAMissingDepartmentReportsItInsteadOfFailing() {
        when(departmentRepository.findById(anyLong())).thenReturn(Optional.empty());
        RedirectAttributes flash = new RedirectAttributesModelMap();

        String view = departmentController.toggleDepartmentStatus(99L, flash);

        assertEquals("redirect:/departments", view);
        assertEquals("Department not found.", flashOf(flash).get("errorMessage"));
        verify(departmentRepository, never()).save(any());
    }

    @Test
    void openingTheEditFormForAMissingDepartmentReturnsToTheList() {
        when(departmentRepository.findById(99L)).thenReturn(Optional.empty());
        RedirectAttributes flash = new RedirectAttributesModelMap();

        String view = departmentController.showEditForm(99L, new ConcurrentModel(), flash);

        assertEquals("redirect:/departments", view);
        assertEquals("Department not found.", flashOf(flash).get("errorMessage"));
    }

    // -------------------------------------------------------------------------
    // Physical areas
    // -------------------------------------------------------------------------

    @Test
    void physicalAreaListShowsEveryRowInNameOrder() {
        PhysicalArea firstFloor = area(1L, "First Floor", true);
        when(physicalAreaRepository.findAllByOrderByNameAsc()).thenReturn(List.of(firstFloor));

        Model model = new ConcurrentModel();
        String view = physicalAreaController.listPhysicalAreas(model);

        assertEquals("physical-areas/list", view);
        assertEquals(List.of(firstFloor), model.getAttribute("physicalAreas"));
    }

    @Test
    void createPhysicalAreaSavesATrimmedActiveRow() {
        RedirectAttributes flash = new RedirectAttributesModelMap();

        String view = physicalAreaController.createPhysicalArea(
                "  Server Room ", "Air-conditioned", flash);

        assertEquals("redirect:/physical-areas", view);
        ArgumentCaptor<PhysicalArea> saved = ArgumentCaptor.forClass(PhysicalArea.class);
        verify(physicalAreaRepository).save(saved.capture());
        assertEquals("Server Room", saved.getValue().getName());
        assertEquals("Air-conditioned", saved.getValue().getDescription());
        assertTrue(saved.getValue().isActive());
        assertEquals("Physical area created successfully.",
                flashOf(flash).get("successMessage"));
    }

    @Test
    void createPhysicalAreaRefusesABlankName() {
        RedirectAttributes flash = new RedirectAttributesModelMap();

        String view = physicalAreaController.createPhysicalArea("", null, flash);

        assertEquals("redirect:/physical-areas/new", view);
        assertEquals("Physical area name is required.", flashOf(flash).get("errorMessage"));
        verify(physicalAreaRepository, never()).save(any());
    }

    @Test
    void createPhysicalAreaRefusesADuplicateName() {
        // The controller forwards the name as typed, only trimmed: folding the case
        // is the IgnoreCase query's job, not something to redo in Java.
        when(physicalAreaRepository.existsByNameIgnoreCase("Store")).thenReturn(true);
        RedirectAttributes flash = new RedirectAttributesModelMap();

        String view = physicalAreaController.createPhysicalArea(" Store ", null, flash);

        assertEquals("redirect:/physical-areas/new", view);
        assertEquals("A physical area with this name already exists.",
                flashOf(flash).get("errorMessage"));
        verify(physicalAreaRepository).existsByNameIgnoreCase("Store");
        verify(physicalAreaRepository, never()).save(any());
    }

    @Test
    void updatePhysicalAreaRefusesANameTakenByAnotherArea() {
        when(physicalAreaRepository.findById(1L)).thenReturn(Optional.of(area(1L, "Store", true)));
        when(physicalAreaRepository.existsByNameIgnoreCaseAndAreaIdNot("Office", 1L)).thenReturn(true);
        RedirectAttributes flash = new RedirectAttributesModelMap();

        String view = physicalAreaController.updatePhysicalArea(1L, "Office", null, flash);

        assertEquals("redirect:/physical-areas/edit?id=1", view);
        assertEquals("A physical area with this name already exists.",
                flashOf(flash).get("errorMessage"));
        verify(physicalAreaRepository, never()).save(any());
    }

    @Test
    void deactivatingAPhysicalAreaIsReversibleAndMissingRowsAreRefused() {
        PhysicalArea store = area(1L, "Store", true);
        when(physicalAreaRepository.findById(1L)).thenReturn(Optional.of(store));

        RedirectAttributes flash = new RedirectAttributesModelMap();
        assertEquals("redirect:/physical-areas",
                physicalAreaController.togglePhysicalAreaStatus(1L, flash));
        assertFalse(store.isActive());
        assertEquals("Physical area deactivated successfully.",
                flashOf(flash).get("successMessage"));

        when(physicalAreaRepository.findById(99L)).thenReturn(Optional.empty());
        RedirectAttributes missing = new RedirectAttributesModelMap();
        assertEquals("redirect:/physical-areas",
                physicalAreaController.togglePhysicalAreaStatus(99L, missing));
        assertEquals("Physical area not found.", flashOf(missing).get("errorMessage"));
    }

    // -------------------------------------------------------------------------
    // Property categories
    // -------------------------------------------------------------------------

    @Test
    void categoryListShowsEveryRowInNameOrder() {
        PropertyCategory laptops = category(1L, "Laptops", true);
        when(propertyCategoryRepository.findAllByOrderByNameAsc()).thenReturn(List.of(laptops));

        Model model = new ConcurrentModel();
        String view = propertyCategoryController.listCategories(model);

        assertEquals("property-categories/list", view);
        assertEquals(List.of(laptops), model.getAttribute("categories"));
    }

    @Test
    void createCategorySavesATrimmedActiveRow() {
        RedirectAttributes flash = new RedirectAttributesModelMap();

        String view = propertyCategoryController.createCategory(
                "  Laptops ", "Portable computers", flash);

        assertEquals("redirect:/property-categories", view);
        ArgumentCaptor<PropertyCategory> saved = ArgumentCaptor.forClass(PropertyCategory.class);
        verify(propertyCategoryRepository).save(saved.capture());
        assertEquals("Laptops", saved.getValue().getName());
        assertEquals("Portable computers", saved.getValue().getDescription());
        assertTrue(saved.getValue().isActive());
        assertEquals("Property category created successfully.",
                flashOf(flash).get("successMessage"));
    }

    @Test
    void createCategoryRefusesABlankNameAndADuplicate() {
        RedirectAttributes blank = new RedirectAttributesModelMap();
        assertEquals("redirect:/property-categories/new",
                propertyCategoryController.createCategory("  ", null, blank));
        assertEquals("Category name is required.", flashOf(blank).get("errorMessage"));

        when(propertyCategoryRepository.existsByNameIgnoreCase("Laptops")).thenReturn(true);
        RedirectAttributes duplicate = new RedirectAttributesModelMap();
        assertEquals("redirect:/property-categories/new",
                propertyCategoryController.createCategory(" Laptops ", null, duplicate));
        assertEquals("A category with this name already exists.",
                flashOf(duplicate).get("errorMessage"));

        verify(propertyCategoryRepository, never()).save(any());
    }

    @Test
    void updateCategoryClearsADescriptionAndRefusesAMissingRow() {
        PropertyCategory laptops = category(1L, "Laptops", true);
        laptops.setDescription("Portable computers");
        when(propertyCategoryRepository.findById(1L)).thenReturn(Optional.of(laptops));

        RedirectAttributes flash = new RedirectAttributesModelMap();
        assertEquals("redirect:/property-categories",
                propertyCategoryController.updateCategory(1L, "Laptops", " ", flash));
        assertNull(laptops.getDescription());
        assertEquals("Property category updated successfully.",
                flashOf(flash).get("successMessage"));

        when(propertyCategoryRepository.findById(99L)).thenReturn(Optional.empty());
        RedirectAttributes missing = new RedirectAttributesModelMap();
        assertEquals("redirect:/property-categories",
                propertyCategoryController.updateCategory(99L, "Laptops", null, missing));
        assertEquals("Property category not found.", flashOf(missing).get("errorMessage"));
    }

    @Test
    void deactivatingACategoryIsReversible() {
        PropertyCategory laptops = category(1L, "Laptops", true);
        when(propertyCategoryRepository.findById(1L)).thenReturn(Optional.of(laptops));

        RedirectAttributes deactivated = new RedirectAttributesModelMap();
        propertyCategoryController.toggleCategoryStatus(1L, deactivated);
        assertFalse(laptops.isActive());
        assertEquals("Property category deactivated successfully.",
                flashOf(deactivated).get("successMessage"));

        RedirectAttributes activated = new RedirectAttributesModelMap();
        propertyCategoryController.toggleCategoryStatus(1L, activated);
        assertTrue(laptops.isActive());
        assertEquals("Property category activated successfully.",
                flashOf(activated).get("successMessage"));
    }

    // -------------------------------------------------------------------------
    // Property types - the one register with two extra rules of its own
    // -------------------------------------------------------------------------

    @Test
    void typeListShowsEveryRowAndTheFormOffersTheCategoriesToChooseFrom() {
        PropertyCategory laptops = category(1L, "Laptops", true);
        PropertyType laptop = type(1L, "Dell Latitude", "COMMON", laptops);
        when(propertyTypeRepository.findAllByOrderByCategory_NameAscNameAsc())
                .thenReturn(List.of(laptop));
        when(propertyCategoryRepository.findAllByOrderByNameAsc()).thenReturn(List.of(laptops));

        Model model = new ConcurrentModel();
        assertEquals("property-types/list", propertyTypeController.listPropertyTypes(model));
        assertEquals(List.of(laptop), model.getAttribute("propertyTypes"));

        Model formModel = new ConcurrentModel();
        assertEquals("property-types/form", propertyTypeController.showCreateForm(formModel));
        assertEquals(List.of(laptops), formModel.getAttribute("categories"));
    }

    @Test
    void createPropertyTypeSavesAgainstTheChosenCategory() {
        PropertyCategory laptops = category(1L, "Laptops", true);
        when(propertyCategoryRepository.findById(1L)).thenReturn(Optional.of(laptops));
        RedirectAttributes flash = new RedirectAttributesModelMap();

        String view = propertyTypeController.createPropertyType(
                1L, "  Dell Latitude ", "INDIVIDUAL", "Serial-tracked", flash);

        assertEquals("redirect:/property-types", view);
        ArgumentCaptor<PropertyType> saved = ArgumentCaptor.forClass(PropertyType.class);
        verify(propertyTypeRepository).save(saved.capture());
        assertEquals("Dell Latitude", saved.getValue().getName());
        assertEquals("INDIVIDUAL", saved.getValue().getUsageType());
        assertEquals(laptops, saved.getValue().getCategory());
        assertTrue(saved.getValue().isActive());
        assertEquals("Property type created successfully.",
                flashOf(flash).get("successMessage"));
    }

    @Test
    void createPropertyTypeRefusesABlankNameAndAnUnknownUsageType() {
        RedirectAttributes blank = new RedirectAttributesModelMap();
        assertEquals("redirect:/property-types/new",
                propertyTypeController.createPropertyType(1L, " ", "COMMON", null, blank));
        assertEquals("Property type name is required.", flashOf(blank).get("errorMessage"));

        // The vocabulary is COMMON or INDIVIDUAL; anything else would be a row no
        // other screen can interpret.
        RedirectAttributes usage = new RedirectAttributesModelMap();
        assertEquals("redirect:/property-types/new",
                propertyTypeController.createPropertyType(1L, "Dell Latitude", "SHARED", null, usage));
        assertEquals("Invalid usage type.", flashOf(usage).get("errorMessage"));

        verify(propertyTypeRepository, never()).save(any());
    }

    @Test
    void createPropertyTypeRefusesADeactivatedOrMissingCategory() {
        PropertyCategory retired = category(1L, "Pagers", false);
        when(propertyCategoryRepository.findById(1L)).thenReturn(Optional.of(retired));
        RedirectAttributes deactivated = new RedirectAttributesModelMap();
        assertEquals("redirect:/property-types/new",
                propertyTypeController.createPropertyType(1L, "Beepers", "COMMON", null, deactivated));
        assertEquals("Please select an active property category.",
                flashOf(deactivated).get("errorMessage"));

        when(propertyCategoryRepository.findById(99L)).thenReturn(Optional.empty());
        RedirectAttributes missing = new RedirectAttributesModelMap();
        assertEquals("redirect:/property-types/new",
                propertyTypeController.createPropertyType(99L, "Beepers", "COMMON", null, missing));
        assertEquals("Please select an active property category.",
                flashOf(missing).get("errorMessage"));

        verify(propertyTypeRepository, never()).save(any());
    }

    @Test
    void createPropertyTypeRefusesTheSameNameAndUsageTypeInOneCategory() {
        // The duplicate rule is per category and per usage type, so "Dell Latitude"
        // can exist twice as long as one is COMMON and the other INDIVIDUAL.
        when(propertyCategoryRepository.findById(1L))
                .thenReturn(Optional.of(category(1L, "Laptops", true)));
        when(propertyTypeRepository
                .existsByCategory_CategoryIdAndNameIgnoreCaseAndUsageType(1L, "Dell Latitude", "COMMON"))
                .thenReturn(true);
        RedirectAttributes flash = new RedirectAttributesModelMap();

        String view = propertyTypeController.createPropertyType(
                1L, "Dell Latitude", "COMMON", null, flash);

        assertEquals("redirect:/property-types/new", view);
        assertEquals("A property type with this name and usage type already exists in this category.",
                flashOf(flash).get("errorMessage"));
        verify(propertyTypeRepository, never()).save(any());
    }

    @Test
    void updatePropertyTypeRefusesAMissingRowABadUsageTypeAndItsOwnDuplicate() {
        when(propertyTypeRepository.findById(99L)).thenReturn(Optional.empty());
        RedirectAttributes missing = new RedirectAttributesModelMap();
        assertEquals("redirect:/property-types",
                propertyTypeController.updatePropertyType(99L, 1L, "Dell", "COMMON", null, missing));
        assertEquals("Property type not found.", flashOf(missing).get("errorMessage"));

        PropertyType laptop = type(1L, "Dell Latitude", "COMMON", category(1L, "Laptops", true));
        when(propertyTypeRepository.findById(1L)).thenReturn(Optional.of(laptop));
        RedirectAttributes badUsage = new RedirectAttributesModelMap();
        assertEquals("redirect:/property-types/edit?id=1",
                propertyTypeController.updatePropertyType(1L, 1L, "Dell Latitude", "BORROWED", null, badUsage));
        assertEquals("Invalid usage type.", flashOf(badUsage).get("errorMessage"));

        when(propertyCategoryRepository.findById(1L))
                .thenReturn(Optional.of(category(1L, "Laptops", true)));
        when(propertyTypeRepository
                .existsByCategory_CategoryIdAndNameIgnoreCaseAndUsageTypeAndPropertyTypeIdNot(
                        1L, "ThinkPad", "COMMON", 1L))
                .thenReturn(true);
        RedirectAttributes duplicate = new RedirectAttributesModelMap();
        assertEquals("redirect:/property-types/edit?id=1",
                propertyTypeController.updatePropertyType(1L, 1L, "ThinkPad", "COMMON", null, duplicate));
        assertEquals("A property type with this name and usage type already exists in this category.",
                flashOf(duplicate).get("errorMessage"));

        verify(propertyTypeRepository, never()).save(any());
    }

    @Test
    void updatePropertyTypeMovesItToAnotherCategoryAndClearsItsDescription() {
        PropertyType laptop = type(1L, "Dell Latitude", "COMMON", category(1L, "Laptops", true));
        laptop.setDescription("Old note");
        PropertyCategory tablets = category(2L, "Tablets", true);
        when(propertyTypeRepository.findById(1L)).thenReturn(Optional.of(laptop));
        when(propertyCategoryRepository.findById(2L)).thenReturn(Optional.of(tablets));
        RedirectAttributes flash = new RedirectAttributesModelMap();

        String view = propertyTypeController.updatePropertyType(
                1L, 2L, "Dell Latitude", "INDIVIDUAL", "", flash);

        assertEquals("redirect:/property-types", view);
        assertEquals(tablets, laptop.getCategory());
        assertEquals("INDIVIDUAL", laptop.getUsageType());
        assertNull(laptop.getDescription());
        assertEquals("Property type updated successfully.",
                flashOf(flash).get("successMessage"));
    }

    @Test
    void deactivatingAPropertyTypeIsReversibleAndAMissingRowIsRefused() {
        PropertyType laptop = type(1L, "Dell Latitude", "COMMON", category(1L, "Laptops", true));
        when(propertyTypeRepository.findById(1L)).thenReturn(Optional.of(laptop));

        RedirectAttributes deactivated = new RedirectAttributesModelMap();
        assertEquals("redirect:/property-types",
                propertyTypeController.togglePropertyTypeStatus(1L, deactivated));
        assertFalse(laptop.isActive());
        assertEquals("Property type deactivated successfully.",
                flashOf(deactivated).get("successMessage"));

        when(propertyTypeRepository.findById(99L)).thenReturn(Optional.empty());
        RedirectAttributes missing = new RedirectAttributesModelMap();
        assertEquals("redirect:/property-types",
                propertyTypeController.togglePropertyTypeStatus(99L, missing));
        assertEquals("Property type not found.", flashOf(missing).get("errorMessage"));
        // The one save above is the deactivation; the missing row must not add a
        // second.
        verify(propertyTypeRepository, times(1)).save(any());
    }

    // -------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------

    /**
     * A create that reaches the duplicate check has to have saved exactly one row;
     * reading it back is how the trimming and the default state are asserted.
     */
    private Department captureSavedDepartment() {
        ArgumentCaptor<Department> saved = ArgumentCaptor.forClass(Department.class);
        verify(departmentRepository).save(saved.capture());
        return saved.getValue();
    }

    private static java.util.Map<String, ?> flashOf(RedirectAttributes attributes) {
        return attributes.getFlashAttributes();
    }

    private Department department(Long id, String name, boolean active) {
        Department department = new Department();
        department.setDepartmentId(id);
        department.setName(name);
        department.setActive(active);
        return department;
    }

    private PhysicalArea area(Long id, String name, boolean active) {
        PhysicalArea area = new PhysicalArea();
        area.setAreaId(id);
        area.setName(name);
        area.setActive(active);
        return area;
    }

    private PropertyCategory category(Long id, String name, boolean active) {
        PropertyCategory category = new PropertyCategory();
        category.setCategoryId(id);
        category.setName(name);
        category.setActive(active);
        return category;
    }

    private PropertyType type(Long id, String name, String usageType, PropertyCategory category) {
        PropertyType type = new PropertyType();
        type.setPropertyTypeId(id);
        type.setName(name);
        type.setUsageType(usageType);
        type.setCategory(category);
        type.setActive(true);
        return type;
    }
}
