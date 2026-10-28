package com.bssa.assetmanagement.repository;

import com.bssa.assetmanagement.entity.AppUser;
import com.bssa.assetmanagement.entity.PropertyRequest;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PropertyRequestRepository extends JpaRepository<PropertyRequest, Long> {

    @EntityGraph(attributePaths = {"items", "items.propertyType", "items.propertyType.category", "requester", "reviewedBy"})
    List<PropertyRequest> findAllByRequesterOrderByRequestIdDesc(AppUser requester);

    @EntityGraph(attributePaths = {"items", "items.propertyType", "items.propertyType.category", "requester", "reviewedBy"})
    List<PropertyRequest> findAllByOrderByRequestIdDesc();

    @EntityGraph(attributePaths = {"items", "items.propertyType", "items.propertyType.category", "requester", "reviewedBy"})
    Optional<PropertyRequest> findWithDetailsByRequestId(Long requestId);

    @EntityGraph(attributePaths = {"items", "items.propertyType", "items.propertyType.category", "requester", "reviewedBy"})
    List<PropertyRequest> findAllByStatusOrderByRequestIdDesc(String status);

    long countByStatus(String status);

    long countByRequester_UserIdAndStatus(Long requesterId, String status);

    @EntityGraph(attributePaths = {"items", "items.propertyType", "items.propertyType.category", "requester", "reviewedBy"})
    @org.springframework.data.jpa.repository.Query(
        "SELECT r FROM PropertyRequest r " +
        "WHERE (:status IS NULL OR r.status = :status) " +
        "AND (:requesterId IS NULL OR r.requester.userId = :requesterId) " +
        "AND (:startDate IS NULL OR r.requestedAt >= :startDate) " +
        "AND (:endDate IS NULL OR r.requestedAt <= :endDate) " +
        "ORDER BY r.requestId DESC"
    )
    List<PropertyRequest> findFilteredRequests(
            @org.springframework.data.repository.query.Param("status") String status,
            @org.springframework.data.repository.query.Param("requesterId") Long requesterId,
            @org.springframework.data.repository.query.Param("startDate") java.time.LocalDateTime startDate,
            @org.springframework.data.repository.query.Param("endDate") java.time.LocalDateTime endDate
    );
}
