
package com.bssa.assetmanagement.repository;

import com.bssa.assetmanagement.entity.Property;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PropertyRepository extends JpaRepository<Property, Long> {

    boolean existsByPropertyCodeIgnoreCase(String propertyCode);

    boolean existsByPropertyCodeIgnoreCaseAndPropertyIdNot(
            String propertyCode, Long propertyId);

    @EntityGraph(attributePaths = {"propertyType", "area"})
    List<Property> findAllByOrderByPropertyIdDesc();

    @EntityGraph(attributePaths = {"propertyType", "area"})
    List<Property> findByStatusOrderByPropertyCodeAsc(String status);
}
