package com.bssa.assetmanagement.service;

import com.bssa.assetmanagement.entity.AppUser;
import com.bssa.assetmanagement.entity.Department;
import com.bssa.assetmanagement.entity.PhysicalArea;
import com.bssa.assetmanagement.entity.Property;
import com.bssa.assetmanagement.entity.PropertyCategory;
import com.bssa.assetmanagement.entity.PropertyRequest;
import com.bssa.assetmanagement.entity.PropertyRequestItem;
import com.bssa.assetmanagement.entity.PropertyType;
import com.bssa.assetmanagement.entity.Role;
import com.bssa.assetmanagement.repository.AppUserRepository;
import com.bssa.assetmanagement.repository.DepartmentRepository;
import com.bssa.assetmanagement.repository.PhysicalAreaRepository;
import com.bssa.assetmanagement.repository.PropertyCategoryRepository;
import com.bssa.assetmanagement.repository.PropertyRepository;
import com.bssa.assetmanagement.repository.PropertyRequestRepository;
import com.bssa.assetmanagement.repository.PropertyTypeRepository;
import com.bssa.assetmanagement.repository.RoleRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves that one asset cannot be allocated twice, even when two allocations
 * overlap in time.
 *
 * The unit tests already show that a second issue is refused once the first has
 * finished. They cannot show what happens while both are in progress, which is
 * the case the rule exists for: an asset's availability is read, and only
 * afterwards is the assignment row written, so two overlapping requests would
 * each read the same asset as available and each write an allocation. Nothing in
 * schema.sql forbids two unreturned assignments to one asset, so the database
 * does not catch it either - the only protection is the write lock the services
 * take on the asset row. Run without that lock, these three tests reproduce what
 * the race actually looks like: the two transactions cross on the asset's own
 * update and MySQL aborts one with a deadlock, which reaches the user as a
 * database error rather than a refusal, and a rival that commits first can slip
 * past the availability check entirely.
 *
 * This class runs against the real database and deliberately does not roll its
 * work back: the point is what two committed transactions leave behind. Every row
 * it creates is recorded and deleted afterwards, and every search term, code and
 * name is invented here, so nothing it does can disturb the surrounding data.
 */
@SpringBootTest
class AssetAllocationConcurrencyIntegrationTest {

    @Autowired
    private PropertyAssignmentService assignmentService;

    @Autowired
    private PropertyRequestService requestService;

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private DepartmentRepository departmentRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private PropertyRepository propertyRepository;

    @Autowired
    private PropertyTypeRepository propertyTypeRepository;

    @Autowired
    private PropertyCategoryRepository propertyCategoryRepository;

    @Autowired
    private PhysicalAreaRepository physicalAreaRepository;

    @Autowired
    private PropertyRequestRepository requestRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    /** Every row this class created, so it can be removed whatever the test did. */
    private final List<Long> assetIds = new ArrayList<>();
    private final List<Long> userIds = new ArrayList<>();
    private final List<Long> departmentIds = new ArrayList<>();
    private final List<Long> requestIds = new ArrayList<>();
    private final List<Long> typeIds = new ArrayList<>();
    private final List<Long> categoryIds = new ArrayList<>();

    @AfterEach
    void removeEverythingThisClassCreated() {
        // Children before parents: every foreign key here is RESTRICT.
        for (Long assetId : assetIds) {
            jdbc.update("DELETE FROM notifications WHERE entity_type = 'ASSIGNMENT' AND entity_id IN "
                    + "(SELECT assignment_id FROM property_assignments WHERE property_id = ?)", assetId);
            jdbc.update("DELETE FROM property_returns WHERE assignment_id IN "
                    + "(SELECT assignment_id FROM property_assignments WHERE property_id = ?)", assetId);
            jdbc.update("DELETE FROM property_status_history WHERE property_id = ?", assetId);
            jdbc.update("DELETE FROM property_incidents WHERE property_id = ?", assetId);
            jdbc.update("DELETE FROM property_assignments WHERE property_id = ?", assetId);
        }
        // A low-stock alert names an asset type but belongs to its recipients,
        // who are outside this test, so it is the one row that cannot be reached
        // by following this class's own identifiers.
        for (Long typeId : typeIds) {
            jdbc.update("DELETE FROM notifications WHERE entity_type = 'PROPERTY_TYPE' AND entity_id = ?", typeId);
        }
        for (Long requestId : requestIds) {
            jdbc.update("DELETE FROM notifications WHERE entity_type = 'REQUEST' AND entity_id = ?", requestId);
            jdbc.update("DELETE FROM property_request_items WHERE request_id = ?", requestId);
            jdbc.update("DELETE FROM property_requests WHERE request_id = ?", requestId);
        }
        for (Long userId : userIds) {
            jdbc.update("DELETE FROM notifications WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM user_roles WHERE user_id = ?", userId);
        }
        for (Long userId : userIds) {
            jdbc.update("DELETE FROM users WHERE user_id = ?", userId);
        }
        for (Long assetId : assetIds) {
            jdbc.update("DELETE FROM properties WHERE property_id = ?", assetId);
        }
        for (Long typeId : typeIds) {
            jdbc.update("DELETE FROM property_types WHERE property_type_id = ?", typeId);
        }
        for (Long categoryId : categoryIds) {
            jdbc.update("DELETE FROM property_categories WHERE category_id = ?", categoryId);
        }
        for (Long departmentId : departmentIds) {
            jdbc.update("DELETE FROM departments WHERE department_id = ?", departmentId);
        }

        assetIds.clear();
        userIds.clear();
        departmentIds.clear();
        requestIds.clear();
        typeIds.clear();
        categoryIds.clear();
    }

    /**
     * Two issues of the same asset started together. Exactly one may succeed: the
     * other must be refused and must leave no assignment, no status change and no
     * history row behind.
     */
    @Test
    void twoOverlappingIssuesCannotBothTakeTheSameAsset() throws Exception {
        Fixture fixture = newFixture();
        AppUser firstHolder = newUser("HOLDER1");
        AppUser secondHolder = newUser("HOLDER2");

        // Either thread can lose the race, so neither may throw at the test.
        List<String> outcomes = runTogether(
                () -> issue(fixture, firstHolder),
                () -> issue(fixture, secondHolder));

        assertEquals(1, countAssignmentsOf(fixture.assetId()),
                "one asset may have one active assignment, whichever issue arrived first: " + outcomes);
        assertEquals(1, outcomes.stream().filter(ISSUED::equals).count(),
                "exactly one issue was expected to succeed: " + outcomes);

        String refusal = outcomes.stream().filter(outcome -> !ISSUED.equals(outcome)).findFirst().orElseThrow();
        assertTrue(refusal.toLowerCase().contains("available") || refusal.toLowerCase().contains("assignment"),
                "the losing issue should be refused for a business reason, not crash: " + refusal);

        assertEquals("ASSIGNED", assetStatus(fixture.assetId()));
        assertEquals(1, countHistoryRowsOf(fixture.assetId()),
                "the refused issue must not record a status change of its own: " + outcomes);
    }

    /**
     * Two approved requests fulfilled against the one remaining asset. Only one
     * may take it, and the request that lost must still stand as approved: a
     * request marked fulfilled with no asset behind it is the failure this rule
     * exists to prevent.
     */
    @Test
    void twoOverlappingFulfillmentsCannotBothTakeTheSameAsset() throws Exception {
        Fixture fixture = newFixture();
        AppUser manager = newManager("MANAGER");

        long firstRequestId = approvedRequest(newStaff("REQUESTER1"), fixture.typeId());
        long secondRequestId = approvedRequest(newStaff("REQUESTER2"), fixture.typeId());

        List<String> outcomes = runTogether(
                () -> fulfill(firstRequestId, fixture, manager),
                () -> fulfill(secondRequestId, fixture, manager));

        assertEquals(1, countAssignmentsOf(fixture.assetId()),
                "one asset may be allocated by one fulfillment only: " + outcomes);

        String winner = outcomes.stream()
                .filter(outcome -> outcome.startsWith(FULFILLED))
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected one fulfillment to succeed: " + outcomes));

        long winningRequestId = Long.parseLong(winner.substring(FULFILLED.length()));
        long losingRequestId = winningRequestId == firstRequestId ? secondRequestId : firstRequestId;

        assertEquals("FULFILLED", requestStatus(winningRequestId));
        assertEquals("APPROVED", requestStatus(losingRequestId),
                "a fulfillment that could not allocate the asset must not leave its request marked fulfilled");
    }

    /**
     * The lock is held until the transaction ends, which is what turns the
     * read-then-write into a single step. A transaction that has read the asset
     * under its write lock is parked here deliberately while a second issue is
     * attempted: the second must still be waiting when the first lets go, and must
     * only then be able to read the row.
     */
    @Test
    void aSecondIssueWaitsUntilTheTransactionThatLockedTheAssetEnds() throws Exception {
        Fixture fixture = newFixture();
        AppUser holder = newUser("HOLDER");

        CountDownLatch assetLocked = new CountDownLatch(1);
        CountDownLatch lockReleased = new CountDownLatch(1);

        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        Thread lockHolder = new Thread(() -> transaction.executeWithoutResult(status -> {
            assertTrue(propertyRepository.findByPropertyIdForUpdate(fixture.assetId()).isPresent(),
                    "the locking read should find the asset");
            assetLocked.countDown();
            try {
                assertTrue(lockReleased.await(30, TimeUnit.SECONDS), "the test should release the lock");
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }));
        lockHolder.setDaemon(true);

        ExecutorService single = Executors.newSingleThreadExecutor();
        try {
            lockHolder.start();
            assertTrue(assetLocked.await(30, TimeUnit.SECONDS), "the locking read should have taken the lock");

            Future<String> waiting = single.submit(() -> issue(fixture, holder));

            // Still parked on the lock, so no second allocation has been written
            // while the first transaction held the asset.
            assertThrows(TimeoutException.class, () -> waiting.get(1, TimeUnit.SECONDS),
                    "the second issue should be waiting for the asset, not reading it as available");

            lockReleased.countDown();
            assertNotNull(waiting.get(60, TimeUnit.SECONDS), "the second issue should proceed once released");
        } finally {
            lockReleased.countDown();
            single.shutdownNow();
            lockHolder.join(TimeUnit.SECONDS.toMillis(30));
        }
    }

    // ------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------

    /** Marker for an issue that got through, so outcomes stay readable. */
    private static final String ISSUED = "issued";
    /** Prefix, followed by the request id, for a fulfillment that got through. */
    private static final String FULFILLED = "fulfilled";

    private String issue(Fixture fixture, AppUser holder) {
        try {
            assignmentService.assignProperty(
                    fixture.assetId(), holder.getUserId(), fixture.areaId(),
                    "GOOD", null, fixture.issuerEmail());
            return ISSUED;
        } catch (IllegalArgumentException ex) {
            return ex.getMessage();
        }
    }

    private String fulfill(long requestId, Fixture fixture, AppUser manager) {
        try {
            requestService.fulfillRequest(
                    requestId, fixture.assetId(), fixture.areaId(), "GOOD", null, manager.getEmail());
            return FULFILLED + requestId;
        } catch (IllegalArgumentException | IllegalStateException ex) {
            return ex.getMessage();
        }
    }

    /** Runs two allocations side by side and returns both outcomes in order. */
    private List<String> runTogether(Callable<String> first, Callable<String> second) throws Exception {
        CyclicBarrier bothReady = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<String> one = pool.submit(() -> {
                bothReady.await(30, TimeUnit.SECONDS);
                return first.call();
            });
            Future<String> two = pool.submit(() -> {
                bothReady.await(30, TimeUnit.SECONDS);
                return second.call();
            });
            return List.of(one.get(60, TimeUnit.SECONDS), two.get(60, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    private int countAssignmentsOf(long assetId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM property_assignments WHERE property_id = ?", Integer.class, assetId);
    }

    private int countHistoryRowsOf(long assetId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM property_status_history WHERE property_id = ?", Integer.class, assetId);
    }

    private String assetStatus(long assetId) {
        return jdbc.queryForObject("SELECT status FROM properties WHERE property_id = ?", String.class, assetId);
    }

    private String requestStatus(long requestId) {
        return jdbc.queryForObject(
                "SELECT status FROM property_requests WHERE request_id = ?", String.class, requestId);
    }

    /**
     * An asset only this test can see, alone in its own category and type, so the
     * stock warning its allocation raises names a type no other data refers to.
     */
    private Fixture newFixture() {
        PropertyCategory category = new PropertyCategory();
        category.setName("CONC-CAT-" + UUID.randomUUID().toString().substring(0, 10));
        category.setActive(true);
        PropertyCategory savedCategory = propertyCategoryRepository.saveAndFlush(category);
        categoryIds.add(savedCategory.getCategoryId());

        PropertyType type = new PropertyType();
        type.setCategory(savedCategory);
        type.setName("CONC-TYPE-" + UUID.randomUUID().toString().substring(0, 10));
        type.setUsageType("COMMON");
        PropertyType savedType = propertyTypeRepository.saveAndFlush(type);
        typeIds.add(savedType.getPropertyTypeId());

        PhysicalArea area = anyActiveArea();

        Property asset = new Property();
        asset.setPropertyCode("CONC-" + UUID.randomUUID());
        asset.setPropertyType(savedType);
        asset.setArea(area);
        asset.setCondition("GOOD");
        asset.setStatus("AVAILABLE");
        asset.setPurchaseDate(LocalDate.of(2024, 1, 1));
        Property savedAsset = propertyRepository.saveAndFlush(asset);
        assetIds.add(savedAsset.getPropertyId());

        return new Fixture(
                savedAsset.getPropertyId(),
                savedType.getPropertyTypeId(),
                area.getAreaId(),
                newManager("ISSUER").getEmail());
    }

    private long approvedRequest(AppUser requester, Long typeId) {
        PropertyRequest request = new PropertyRequest();
        request.setRequester(requester);
        request.setStatus("APPROVED");
        request.setNotes("Concurrency fixture request");

        PropertyRequestItem item = new PropertyRequestItem();
        item.setPropertyType(propertyTypeRepository.findById(typeId).orElseThrow());
        item.setQuantity(1);
        request.addItem(item);

        PropertyRequest saved = requestRepository.saveAndFlush(request);
        requestIds.add(saved.getRequestId());
        return saved.getRequestId();
    }

    private AppUser newUser(String tag) {
        return saveUser(tag, anyRole("OFFICE_STAFF"));
    }

    private AppUser newStaff(String tag) {
        return saveUser(tag, anyRole("OFFICE_STAFF"));
    }

    private AppUser newManager(String tag) {
        return saveUser(tag, anyRole("INVENTORY_MANAGER"));
    }

    private AppUser saveUser(String tag, Role role) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        AppUser user = new AppUser();
        user.setEmployeeCode(tag + suffix);
        user.setFirstName(tag);
        user.setLastName("Concurrency" + suffix);
        user.setEmail(tag.toLowerCase() + suffix + "@example.com");
        user.setPasswordHash("not-a-real-credential");
        user.setDepartmentId(newDepartment().getDepartmentId());
        user.setPosition("Test Officer");
        user.setActive(true);
        user.setRoles(Set.of(role));
        AppUser saved = appUserRepository.saveAndFlush(user);
        userIds.add(saved.getUserId());
        return saved;
    }

    private Department newDepartment() {
        Department department = new Department();
        department.setName("CONC-DEPT-" + UUID.randomUUID().toString().substring(0, 8));
        department.setActive(true);
        Department saved = departmentRepository.saveAndFlush(department);
        departmentIds.add(saved.getDepartmentId());
        return saved;
    }

    private Role anyRole(String name) {
        Role role = roleRepository.findByName(name).orElse(null);
        assertNotNull(role, "schema.sql must seed the " + name + " role");
        return role;
    }

    private PhysicalArea anyActiveArea() {
        List<PhysicalArea> areas = physicalAreaRepository.findAllByActiveTrueOrderByNameAsc();
        assertFalse(areas.isEmpty(), "schema.sql must seed at least one physical area");
        return areas.get(0);
    }

    /** Identifiers of a seeded fixture, all of which this class cleans up. */
    private record Fixture(long assetId, long typeId, long areaId, String issuerEmail) {
    }
}
