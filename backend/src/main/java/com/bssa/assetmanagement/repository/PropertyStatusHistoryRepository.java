package com.bssa.assetmanagement.repository;

import com.bssa.assetmanagement.entity.PropertyStatusHistory;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PropertyStatusHistoryRepository
        extends JpaRepository<PropertyStatusHistory, Long> {
}
