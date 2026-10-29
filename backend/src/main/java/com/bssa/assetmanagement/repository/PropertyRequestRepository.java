package com.bssa.assetmanagement.repository;

import com.bssa.assetmanagement.entity.AppUser;
import com.bssa.assetmanagement.entity.PropertyRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PropertyRequestRepository extends JpaRepository<PropertyRequest, Long> {

    @EntityGraph(attributePaths = {"items", "items.propertyType", "items.propertyType.category", "requester", "reviewedBy"})
    List<PropertyRequest> findAllByRequesterOrderByRequestIdDesc(AppUser requester);

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

    /**
     * Identifiers of one page of requests, newest first.
     *
     * Only the identifiers are paginated, because a request is fetched together
     * with its items - a collection. Fetching a page of whole requests in one
     * statement would make Hibernate read every row's items and cut the page
     * down afterwards in memory, which is the opposite of what a paged list is
     * for. {@link #findWithDetailsByRequestIdIn} then loads this page only.
     */
    @org.springframework.data.jpa.repository.Query(
        value = "SELECT r.requestId FROM PropertyRequest r",
        countQuery = "SELECT COUNT(r) FROM PropertyRequest r"
    )
    Page<Long> findRequestIdPage(Pageable pageable);

    /** The requests behind a page of identifiers, with the rows they are listed with. */
    @EntityGraph(attributePaths = {"items", "items.propertyType", "items.propertyType.category", "requester", "reviewedBy"})
    List<PropertyRequest> findWithDetailsByRequestIdIn(Collection<Long> requestIds);
}
