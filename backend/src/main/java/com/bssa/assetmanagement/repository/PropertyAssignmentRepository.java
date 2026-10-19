
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
}
