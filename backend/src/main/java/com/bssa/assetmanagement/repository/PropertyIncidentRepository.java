package com.bssa.assetmanagement.repository;

import com.bssa.assetmanagement.entity.AppUser;
import com.bssa.assetmanagement.entity.PropertyIncident;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PropertyIncidentRepository extends JpaRepository<PropertyIncident, Long> {

    @EntityGraph(attributePaths = {"property", "property.propertyType", "property.area", "reportedBy", "reviewedBy"})
    List<PropertyIncident> findAllByReportedByOrderByIncidentIdDesc(AppUser reportedBy);

    @EntityGraph(attributePaths = {"property", "property.propertyType", "property.area", "reportedBy", "reviewedBy"})
    List<PropertyIncident> findAllByOrderByIncidentIdDesc();

    @EntityGraph(attributePaths = {"property", "property.propertyType", "property.area", "reportedBy", "reviewedBy"})
    List<PropertyIncident> findAllByStatusOrderByIncidentIdDesc(String status);

    @EntityGraph(attributePaths = {"property", "property.propertyType", "property.area", "reportedBy", "reviewedBy"})
    Optional<PropertyIncident> findWithDetailsByIncidentId(@Param("incidentId") Long incidentId);

    boolean existsByProperty_PropertyIdAndStatus(Long propertyId, String status);

    long countByStatus(String status);
}
