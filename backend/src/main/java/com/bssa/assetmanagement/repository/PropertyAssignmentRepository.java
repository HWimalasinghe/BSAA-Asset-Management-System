
package com.bssa.assetmanagement.repository;

import com.bssa.assetmanagement.entity.PropertyAssignment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PropertyAssignmentRepository
        extends JpaRepository<PropertyAssignment, Long> {

    @EntityGraph(attributePaths = {
            "property",
            "property.propertyType",
            "employee",
            "issuedBy",
            "area"
    })
    List<PropertyAssignment> findAllByOrderByAssignmentIdDesc();

    @EntityGraph(attributePaths = {
            "property",
            "property.propertyType",
            "employee",
            "issuedBy",
            "area"
    })
    List<PropertyAssignment> findByEmployee_UserIdOrderByAssignmentIdDesc(
            Long employeeId
    );

    boolean existsByProperty_PropertyId(Long propertyId);

    @org.springframework.data.jpa.repository.Query("SELECT COUNT(a) > 0 FROM PropertyAssignment a WHERE a.property.propertyId = :propertyId AND NOT EXISTS (SELECT r FROM PropertyReturn r WHERE r.assignment.assignmentId = a.assignmentId)")
    boolean hasActiveAssignment(@org.springframework.data.repository.query.Param("propertyId") Long propertyId);

    long countByEmployee_UserIdAndProperty_Status(Long employeeId, String status);

    @EntityGraph(attributePaths = {
            "property",
            "property.propertyType",
            "employee",
            "issuedBy",
            "area"
    })
    @org.springframework.data.jpa.repository.Query(
        "SELECT a FROM PropertyAssignment a " +
        "WHERE (:employeeId IS NULL OR a.employee.userId = :employeeId) " +
        "AND (:areaId IS NULL OR a.area.areaId = :areaId) " +
        "AND (:activeOnly IS FALSE OR NOT EXISTS (SELECT r FROM PropertyReturn r WHERE r.assignment.assignmentId = a.assignmentId)) " +
        "ORDER BY a.assignmentId DESC"
    )
    List<PropertyAssignment> findFilteredAssignments(
            @org.springframework.data.repository.query.Param("employeeId") Long employeeId,
            @org.springframework.data.repository.query.Param("areaId") Long areaId,
            @org.springframework.data.repository.query.Param("activeOnly") boolean activeOnly
    );

    /**
     * One page of the assignment log, with the same optional search and filters
     * the page's own controls offer. "holding" is the vocabulary declared in
     * PropertyAssignmentService: HELD means no return row exists, RETURNED the
     * opposite, and anything else (including null) means no condition.
     *
     * Every clause is written as "no value means no condition", so a blank
     * search box returns the whole log rather than nothing. The typed term is
     * matched as literal text (ESCAPE '!'), and only values from the columns
     * the log already display are searched, so the result cannot reveal more
     * than the page itself shows.
     *
     * An explicit count query is required because the content query joins the
     * holder, issuer and area to filter and sort on them.
     */
    @EntityGraph(attributePaths = {
            "property",
            "property.propertyType",
            "employee",
            "issuedBy",
            "area"
    })
    @org.springframework.data.jpa.repository.Query(
        value = "SELECT a FROM PropertyAssignment a " +
            "WHERE (:search IS NULL " +
            "   OR LOWER(a.property.propertyCode) LIKE :pattern ESCAPE '!' " +
            "   OR LOWER(a.property.propertyType.name) LIKE :pattern ESCAPE '!' " +
            "   OR LOWER(a.employee.employeeCode) LIKE :pattern ESCAPE '!' " +
            "   OR LOWER(a.employee.firstName) LIKE :pattern ESCAPE '!' " +
            "   OR LOWER(a.employee.lastName) LIKE :pattern ESCAPE '!' " +
            "   OR LOWER(a.issuedBy.firstName) LIKE :pattern ESCAPE '!' " +
            "   OR LOWER(a.issuedBy.lastName) LIKE :pattern ESCAPE '!') " +
            "AND (:areaId IS NULL OR a.area.areaId = :areaId) " +
            "AND (:holding IS NULL " +
            "   OR (:holding = 'HELD' AND NOT EXISTS " +
            "        (SELECT r FROM PropertyReturn r WHERE r.assignment.assignmentId = a.assignmentId)) " +
            "   OR (:holding = 'RETURNED' AND EXISTS " +
            "        (SELECT r FROM PropertyReturn r WHERE r.assignment.assignmentId = a.assignmentId)))",
        countQuery = "SELECT COUNT(a) FROM PropertyAssignment a " +
            "WHERE (:search IS NULL " +
            "   OR LOWER(a.property.propertyCode) LIKE :pattern ESCAPE '!' " +
            "   OR LOWER(a.property.propertyType.name) LIKE :pattern ESCAPE '!' " +
            "   OR LOWER(a.employee.employeeCode) LIKE :pattern ESCAPE '!' " +
            "   OR LOWER(a.employee.firstName) LIKE :pattern ESCAPE '!' " +
            "   OR LOWER(a.employee.lastName) LIKE :pattern ESCAPE '!' " +
            "   OR LOWER(a.issuedBy.firstName) LIKE :pattern ESCAPE '!' " +
            "   OR LOWER(a.issuedBy.lastName) LIKE :pattern ESCAPE '!') " +
            "AND (:areaId IS NULL OR a.area.areaId = :areaId) " +
            "AND (:holding IS NULL " +
            "   OR (:holding = 'HELD' AND NOT EXISTS " +
            "        (SELECT r FROM PropertyReturn r WHERE r.assignment.assignmentId = a.assignmentId)) " +
            "   OR (:holding = 'RETURNED' AND EXISTS " +
            "        (SELECT r FROM PropertyReturn r WHERE r.assignment.assignmentId = a.assignmentId)))"
    )
    Page<PropertyAssignment> findAssignmentPage(
            @org.springframework.data.repository.query.Param("search") String search,
            @org.springframework.data.repository.query.Param("pattern") String pattern,
            @org.springframework.data.repository.query.Param("areaId") Long areaId,
            @org.springframework.data.repository.query.Param("holding") String holding,
            Pageable pageable
    );

    /**
     * Assets still held by somebody, grouped by the holder's department.
     * COUNT(DISTINCT property) keeps an asset counted once even if the same
     * asset and holder have several assignment rows.
     * Assets with no holder are not attributed to a department.
     */
    @org.springframework.data.jpa.repository.Query(
        "SELECT d.name AS name, COUNT(DISTINCT a.property.propertyId) AS total " +
        "FROM PropertyAssignment a " +
        "JOIN a.employee e " +
        "JOIN Department d ON d.departmentId = e.departmentId " +
        "WHERE NOT EXISTS (SELECT r FROM PropertyReturn r WHERE r.assignment.assignmentId = a.assignmentId) " +
        "GROUP BY d.name " +
        "ORDER BY d.name ASC"
    )
    java.util.List<PropertyRepository.GroupedCount> countActiveAssetsByDepartment();

    /** Number of assignments that have never been returned. */
    @org.springframework.data.jpa.repository.Query(
        "SELECT COUNT(a.assignmentId) FROM PropertyAssignment a " +
        "WHERE NOT EXISTS (SELECT r FROM PropertyReturn r WHERE r.assignment.assignmentId = a.assignmentId)"
    )
    long countOutstanding();
}
