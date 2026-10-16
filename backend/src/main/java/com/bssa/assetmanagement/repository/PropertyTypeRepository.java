package com.bssa.assetmanagement.repository;

import com.bssa.assetmanagement.entity.PropertyType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PropertyTypeRepository extends JpaRepository<PropertyType, Long> {

    boolean existsByCategory_CategoryIdAndNameIgnoreCaseAndUsageType(
            Long categoryId, String name, String usageType);

    boolean existsByCategory_CategoryIdAndNameIgnoreCaseAndUsageTypeAndPropertyTypeIdNot(
            Long categoryId, String name, String usageType, Long propertyTypeId);

    @EntityGraph(attributePaths = "category")
    List<PropertyType> findAllByOrderByCategory_NameAscNameAsc();
}