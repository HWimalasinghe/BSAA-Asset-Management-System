package com.bssa.assetmanagement.repository;

import com.bssa.assetmanagement.entity.PropertyCategory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PropertyCategoryRepository
        extends JpaRepository<PropertyCategory, Long> {

    boolean existsByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCaseAndCategoryIdNot(
            String name,
            Long categoryId
    );

    List<PropertyCategory> findAllByOrderByNameAsc();
}
