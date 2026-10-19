
package com.bssa.assetmanagement.repository;

import com.bssa.assetmanagement.entity.PropertyReturn;
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
}
