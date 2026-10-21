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
}
