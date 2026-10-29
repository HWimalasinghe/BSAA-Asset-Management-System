
package com.bssa.assetmanagement.repository;

import com.bssa.assetmanagement.entity.Property;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PropertyRepository extends JpaRepository<Property, Long> {

    boolean existsByPropertyCodeIgnoreCase(String propertyCode);

    boolean existsByPropertyCodeIgnoreCaseAndPropertyIdNot(
            String propertyCode, Long propertyId);

    /**
     * The asset exactly as the database holds it, with a write lock kept until
     * the calling transaction ends.
     *
     * Issuing an asset is read-then-write: its availability is checked and only
     * then is the assignment row created. Two requests that overlap would both
     * read the same asset as available and both allocate it, so the second one
     * must be made to wait for the first to finish. A locking read is also the
     * only read that sees what the first transaction committed - a plain read
     * may answer from a snapshot taken before it. The same pattern already
     * guards the single Inventory Admin rule in
     * {@link RoleRepository#findByNameForUpdate(String)}.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Property p WHERE p.propertyId = :propertyId")
    Optional<Property> findByPropertyIdForUpdate(@Param("propertyId") Long propertyId);

    @EntityGraph(attributePaths = {"propertyType", "area"})
    List<Property> findAllByOrderByPropertyIdDesc();

    @EntityGraph(attributePaths = {"propertyType", "area"})
    List<Property> findByStatusOrderByPropertyCodeAsc(String status);

    @EntityGraph(attributePaths = {"propertyType", "area"})
    List<Property> findByPropertyType_PropertyTypeIdAndStatusAndConditionOrderByPropertyCodeAsc(
            Long propertyTypeId, String status, String condition);

    long countByPropertyType_PropertyTypeIdAndStatusAndCondition(
            Long propertyTypeId, String status, String condition);

    long countByStatus(String status);

    long countByStatusIn(java.util.Collection<String> statuses);

    @org.springframework.data.jpa.repository.Query(
        "SELECT p FROM Property p " +
        "JOIN FETCH p.propertyType pt " +
        "JOIN FETCH pt.category c " +
        "JOIN FETCH p.area a " +
        "WHERE (:status IS NULL OR p.status = :status) " +
        "AND (:propertyTypeId IS NULL OR pt.propertyTypeId = :propertyTypeId) " +
        "AND (:categoryId IS NULL OR c.categoryId = :categoryId) " +
        "AND (:areaId IS NULL OR a.areaId = :areaId) " +
        "ORDER BY p.propertyCode ASC"
    )
    List<Property> findFilteredProperties(
            @org.springframework.data.repository.query.Param("status") String status,
            @org.springframework.data.repository.query.Param("propertyTypeId") Long propertyTypeId,
            @org.springframework.data.repository.query.Param("categoryId") Long categoryId,
            @org.springframework.data.repository.query.Param("areaId") Long areaId);

    /**
     * Single grouped count so a report does not need one query per status.
     * Counts the properties table directly, so assignment or history rows
     * cannot inflate a total.
     */
    @org.springframework.data.jpa.repository.Query(
        "SELECT p.status AS name, COUNT(p.propertyId) AS total " +
        "FROM Property p " +
        "GROUP BY p.status"
    )
    List<GroupedCount> countGroupedByStatus();

    @org.springframework.data.jpa.repository.Query(
        "SELECT c.name AS name, COUNT(p.propertyId) AS total " +
        "FROM Property p " +
        "JOIN p.propertyType pt " +
        "JOIN pt.category c " +
        "GROUP BY c.name " +
        "ORDER BY c.name ASC"
    )
    List<GroupedCount> countGroupedByCategory();

    @org.springframework.data.jpa.repository.Query(
        "SELECT c.name AS categoryName, pt.name AS name, COUNT(p.propertyId) AS total " +
        "FROM Property p " +
        "JOIN p.propertyType pt " +
        "JOIN pt.category c " +
        "GROUP BY c.name, pt.name " +
        "ORDER BY c.name ASC, pt.name ASC"
    )
    List<CategoryGroupedCount> countGroupedByType();

    @org.springframework.data.jpa.repository.Query(
        "SELECT a.name AS name, COUNT(p.propertyId) AS total " +
        "FROM Property p " +
        "JOIN p.area a " +
        "GROUP BY a.name " +
        "ORDER BY a.name ASC"
    )
    List<GroupedCount> countGroupedByArea();

    /**
     * Paged, searchable asset register. Every condition is optional, so the
     * database does the filtering and only one page of rows is returned.
     * The search term is matched case-insensitively against the fields an
     * inventory user would recognise: asset code, serial number, description,
     * property type, category and physical area.
     *
     * LIKE wildcards in the caller-supplied pattern are escaped with '!'.
     * Sorting is supplied through the Pageable so the pager can offer a stable
     * order; no ORDER BY belongs in this JPQL.
     */
    @EntityGraph(attributePaths = {"propertyType", "propertyType.category", "area"})
    @org.springframework.data.jpa.repository.Query(
        value =
        "SELECT p FROM Property p " +
        "JOIN FETCH p.propertyType pt " +
        "JOIN FETCH pt.category cat " +
        "JOIN FETCH p.area a " +
        "WHERE (:search IS NULL " +
        "   OR LOWER(p.propertyCode) LIKE :pattern ESCAPE '!' " +
        "   OR LOWER(p.serialNumber) LIKE :pattern ESCAPE '!' " +
        "   OR LOWER(p.description) LIKE :pattern ESCAPE '!' " +
        "   OR LOWER(pt.name) LIKE :pattern ESCAPE '!' " +
        "   OR LOWER(cat.name) LIKE :pattern ESCAPE '!' " +
        "   OR LOWER(a.name) LIKE :pattern ESCAPE '!') " +
        "AND (:status IS NULL OR p.status = :status) " +
        "AND (:condition IS NULL OR p.condition = :condition) " +
        "AND (:categoryId IS NULL OR cat.categoryId = :categoryId) " +
        "AND (:propertyTypeId IS NULL OR pt.propertyTypeId = :propertyTypeId) " +
        "AND (:areaId IS NULL OR a.areaId = :areaId) " +
        "AND (:purchaseFrom IS NULL OR p.purchaseDate >= :purchaseFrom) " +
        "AND (:purchaseTo IS NULL OR p.purchaseDate <= :purchaseTo)",
        countQuery =
        "SELECT COUNT(p) FROM Property p " +
        "JOIN p.propertyType pt " +
        "JOIN pt.category cat " +
        "JOIN p.area a " +
        "WHERE (:search IS NULL " +
        "   OR LOWER(p.propertyCode) LIKE :pattern ESCAPE '!' " +
        "   OR LOWER(p.serialNumber) LIKE :pattern ESCAPE '!' " +
        "   OR LOWER(p.description) LIKE :pattern ESCAPE '!' " +
        "   OR LOWER(pt.name) LIKE :pattern ESCAPE '!' " +
        "   OR LOWER(cat.name) LIKE :pattern ESCAPE '!' " +
        "   OR LOWER(a.name) LIKE :pattern ESCAPE '!') " +
        "AND (:status IS NULL OR p.status = :status) " +
        "AND (:condition IS NULL OR p.condition = :condition) " +
        "AND (:categoryId IS NULL OR cat.categoryId = :categoryId) " +
        "AND (:propertyTypeId IS NULL OR pt.propertyTypeId = :propertyTypeId) " +
        "AND (:areaId IS NULL OR a.areaId = :areaId) " +
        "AND (:purchaseFrom IS NULL OR p.purchaseDate >= :purchaseFrom) " +
        "AND (:purchaseTo IS NULL OR p.purchaseDate <= :purchaseTo)"
    )
    Page<Property> findFilterPage(
            @org.springframework.data.repository.query.Param("search") String search,
            @org.springframework.data.repository.query.Param("pattern") String pattern,
            @org.springframework.data.repository.query.Param("status") String status,
            @org.springframework.data.repository.query.Param("condition") String condition,
            @org.springframework.data.repository.query.Param("categoryId") Long categoryId,
            @org.springframework.data.repository.query.Param("propertyTypeId") Long propertyTypeId,
            @org.springframework.data.repository.query.Param("areaId") Long areaId,
            @org.springframework.data.repository.query.Param("purchaseFrom") java.time.LocalDate purchaseFrom,
            @org.springframework.data.repository.query.Param("purchaseTo") java.time.LocalDate purchaseTo,
            Pageable pageable);

    /** Projected row of a grouped database count. */
    interface GroupedCount {
        String getName();
        Long getTotal();
    }

    /** Grouped count that also carries its parent category name. */
    interface CategoryGroupedCount extends GroupedCount {
        String getCategoryName();
    }
}
