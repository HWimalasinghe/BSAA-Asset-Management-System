package com.bssa.assetmanagement.repository;

import com.bssa.assetmanagement.entity.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    Optional<AppUser> findByEmail(String email);

    List<AppUser> findByActiveTrueOrderByEmployeeCodeAsc();

    boolean existsByEmployeeCodeIgnoreCase(String employeeCode);

    boolean existsByEmailIgnoreCase(String email);
}
