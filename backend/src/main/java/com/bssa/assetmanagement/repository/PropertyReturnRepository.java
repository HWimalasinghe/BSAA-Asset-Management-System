
package com.bssa.assetmanagement.repository;

import com.bssa.assetmanagement.entity.PropertyReturn;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PropertyReturnRepository
        extends JpaRepository<PropertyReturn, Long> {

    boolean existsByAssignment_AssignmentId(Long assignmentId);

    Optional<PropertyReturn> findByAssignment_AssignmentId(
            Long assignmentId
    );

    @EntityGraph(attributePaths = {
            "assignment",
            "assignment.property",
            "assignment.property.propertyType",
            "assignment.employee",
            "assignment.issuedBy",
            "assignment.area",
            "returnedBy",
            "receivedBy"
    })
    List<PropertyReturn> findAllByOrderByReturnIdDesc();

    /**
     * One page of the return log, searched by asset or holder and narrowed by
     * the condition an asset came back in.
     *
     * As with the other lists, "no value means no condition", the typed term is
     * matched as literal text, and the count query repeats the joins the filters
     * need so the total matches the rows shown.
     */
    @EntityGraph(attributePaths = {
            "assignment",
            "assignment.property",
            "assignment.property.propertyType",
            "assignment.employee",
            "assignment.issuedBy",
            "assignment.area",
            "returnedBy",
            "receivedBy"
    })
    @org.springframework.data.jpa.repository.Query(
        value = "SELECT r FROM PropertyReturn r " +
            "WHERE (:search IS NULL " +
            "   OR LOWER(r.assignment.property.propertyCode) LIKE :pattern ESCAPE '!' " +
            "   OR LOWER(r.assignment.property.propertyType.name) LIKE :pattern ESCAPE '!' " +
            "   OR LOWER(r.assignment.employee.employeeCode) LIKE :pattern ESCAPE '!' " +
            "   OR LOWER(r.assignment.employee.firstName) LIKE :pattern ESCAPE '!' " +
            "   OR LOWER(r.assignment.employee.lastName) LIKE :pattern ESCAPE '!' " +
            "   OR LOWER(r.receivedBy.firstName) LIKE :pattern ESCAPE '!' " +
            "   OR LOWER(r.receivedBy.lastName) LIKE :pattern ESCAPE '!') " +
            "AND (:condition IS NULL OR r.condition = :condition)",
        countQuery = "SELECT COUNT(r) FROM PropertyReturn r " +
            "WHERE (:search IS NULL " +
            "   OR LOWER(r.assignment.property.propertyCode) LIKE :pattern ESCAPE '!' " +
            "   OR LOWER(r.assignment.property.propertyType.name) LIKE :pattern ESCAPE '!' " +
            "   OR LOWER(r.assignment.employee.employeeCode) LIKE :pattern ESCAPE '!' " +
            "   OR LOWER(r.assignment.employee.firstName) LIKE :pattern ESCAPE '!' " +
            "   OR LOWER(r.assignment.employee.lastName) LIKE :pattern ESCAPE '!' " +
            "   OR LOWER(r.receivedBy.firstName) LIKE :pattern ESCAPE '!' " +
            "   OR LOWER(r.receivedBy.lastName) LIKE :pattern ESCAPE '!') " +
            "AND (:condition IS NULL OR r.condition = :condition)"
    )
    Page<PropertyReturn> findReturnPage(
            @org.springframework.data.repository.query.Param("search") String search,
            @org.springframework.data.repository.query.Param("pattern") String pattern,
            @org.springframework.data.repository.query.Param("condition") String condition,
            Pageable pageable
    );
}
