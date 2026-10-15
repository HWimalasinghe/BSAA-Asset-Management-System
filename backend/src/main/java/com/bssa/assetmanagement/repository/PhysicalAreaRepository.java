package com.bssa.assetmanagement.repository;

import com.bssa.assetmanagement.entity.PhysicalArea;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PhysicalAreaRepository
        extends JpaRepository<PhysicalArea, Long> {

    boolean existsByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCaseAndAreaIdNot(
            String name,
            Long areaId
    );

    List<PhysicalArea> findAllByOrderByNameAsc();
}
