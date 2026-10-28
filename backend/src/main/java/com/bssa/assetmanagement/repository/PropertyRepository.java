
package com.bssa.assetmanagement.repository;

import com.bssa.assetmanagement.entity.Property;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PropertyRepository extends JpaRepository<Property, Long> {

    boolean existsByPropertyCodeIgnoreCase(String propertyCode);

    boolean existsByPropertyCodeIgnoreCaseAndPropertyIdNot(
            String propertyCode, Long propertyId);

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
