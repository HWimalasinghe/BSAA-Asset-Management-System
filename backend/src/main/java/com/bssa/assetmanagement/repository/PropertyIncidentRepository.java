package com.bssa.assetmanagement.repository;

import com.bssa.assetmanagement.entity.AppUser;
import com.bssa.assetmanagement.entity.PropertyIncident;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PropertyIncidentRepository extends JpaRepository<PropertyIncident, Long> {

    @EntityGraph(attributePaths = {"property", "property.propertyType", "property.area", "reportedBy", "reviewedBy"})
    List<PropertyIncident> findAllByReportedByOrderByIncidentIdDesc(AppUser reportedBy);

    @EntityGraph(attributePaths = {"property", "property.propertyType", "property.area", "reportedBy", "reviewedBy"})
    List<PropertyIncident> findAllByStatusOrderByIncidentIdDesc(String status);

    @EntityGraph(attributePaths = {"property", "property.propertyType", "property.area", "reportedBy", "reviewedBy"})
    Optional<PropertyIncident> findWithDetailsByIncidentId(@Param("incidentId") Long incidentId);

    boolean existsByProperty_PropertyIdAndStatus(Long propertyId, String status);

    long countByStatus(String status);

    long countByReportedBy_UserIdAndStatus(Long reportedById, String status);

    @EntityGraph(attributePaths = {"property", "property.propertyType", "property.area", "reportedBy", "reviewedBy"})
    @org.springframework.data.jpa.repository.Query(
        "SELECT i FROM PropertyIncident i " +
        "WHERE (:incidentType IS NULL OR i.incidentType = :incidentType) " +
        "AND (:status IS NULL OR i.status = :status) " +
        "AND (:startDate IS NULL OR i.createdAt >= :startDate) " +
        "AND (:endDate IS NULL OR i.createdAt <= :endDate) " +
        "ORDER BY i.incidentId DESC"
    )
    List<PropertyIncident> findFilteredIncidents(
            @org.springframework.data.repository.query.Param("incidentType") String incidentType,
            @org.springframework.data.repository.query.Param("status") String status,
            @org.springframework.data.repository.query.Param("startDate") java.time.LocalDateTime startDate,
            @org.springframework.data.repository.query.Param("endDate") java.time.LocalDateTime endDate
    );

    /**
     * One page of the incident history, in the order the caller asked for.
     *
     * The order comes from a validated Pageable rather than from the text of the
     * query, so an unrecognised sort can never reach the database.
     */
    @EntityGraph(attributePaths = {"property", "property.propertyType", "property.area", "reportedBy", "reviewedBy"})
    @org.springframework.data.jpa.repository.Query(
        value = "SELECT i FROM PropertyIncident i",
        countQuery = "SELECT COUNT(i) FROM PropertyIncident i"
    )
    Page<PropertyIncident> findIncidentPage(Pageable pageable);
}
