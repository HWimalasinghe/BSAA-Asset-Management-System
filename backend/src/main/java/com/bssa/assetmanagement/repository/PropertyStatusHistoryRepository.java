package com.bssa.assetmanagement.repository;

import com.bssa.assetmanagement.entity.PropertyStatusHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PropertyStatusHistoryRepository
        extends JpaRepository<PropertyStatusHistory, Long> {

    List<PropertyStatusHistory> findAllByProperty_PropertyIdOrderByHistoryIdDesc(Long propertyId);

    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {"property", "property.propertyType", "changedBy"})
    @org.springframework.data.jpa.repository.Query("SELECT h FROM PropertyStatusHistory h ORDER BY h.historyId DESC")
    List<PropertyStatusHistory> findRecentActivity(org.springframework.data.domain.Pageable pageable);
}
