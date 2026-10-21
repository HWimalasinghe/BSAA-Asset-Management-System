
package com.bssa.assetmanagement.repository;

import com.bssa.assetmanagement.entity.PropertyAssignment;
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
}
