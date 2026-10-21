
package com.bssa.assetmanagement.repository;

import com.bssa.assetmanagement.entity.Role;
import org.springframework.data.jpa.repository.JpaRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface RoleRepository extends JpaRepository<Role, Long> {

    Optional<Role> findByName(String name);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM Role r WHERE r.name = :name")
    Optional<Role> findByNameForUpdate(@Param("name") String name);
}
