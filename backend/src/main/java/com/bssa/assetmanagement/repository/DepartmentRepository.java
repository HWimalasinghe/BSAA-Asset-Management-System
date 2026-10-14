package com.bssa.assetmanagement.repository;

import com.bssa.assetmanagement.entity.Department;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DepartmentRepository extends JpaRepository<Department, Long> {

    boolean existsByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCaseAndDepartmentIdNot(
            String name,
            Long departmentId
    );

    List<Department> findAllByOrderByNameAsc();
}
