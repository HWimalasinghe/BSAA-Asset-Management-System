package com.bssa.assetmanagement.repository;

import com.bssa.assetmanagement.entity.AppUser;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    Optional<AppUser> findByEmail(String email);

    List<AppUser> findByActiveTrueOrderByEmployeeCodeAsc();

    boolean existsByEmployeeCodeIgnoreCase(String employeeCode);

    boolean existsByEmailIgnoreCase(String email);

    List<AppUser> findByActiveTrueAndRoles_Name(String roleName);

    /**
     * Identifiers of the accounts matching a search and the optional filters,
     * one page at a time, in the order the page asked for.
     *
     * Only the identifiers are paginated. Roles are an eager many-to-many, and
     * asking for a page of whole accounts would make Hibernate fetch every
     * account's roles and cut the page down in memory, which is the opposite of
     * what a paged list is for. {@link #findByUserIdIn} then loads just the rows
     * on this page.
     *
     * A blank search means "all accounts", and the typed term is matched as
     * literal text (ESCAPE '!').
     */
    @Query(
        value = "SELECT u.userId FROM AppUser u " +
            "WHERE (:search IS NULL " +
            "   OR LOWER(u.employeeCode) LIKE :pattern ESCAPE '!' " +
            "   OR LOWER(u.firstName) LIKE :pattern ESCAPE '!' " +
            "   OR LOWER(u.lastName) LIKE :pattern ESCAPE '!' " +
            "   OR LOWER(u.email) LIKE :pattern ESCAPE '!') " +
            "AND (:departmentId IS NULL OR u.departmentId = :departmentId) " +
            "AND (:accountStatus IS NULL " +
            "   OR (:accountStatus = 'ACTIVE' AND u.active = TRUE) " +
            "   OR (:accountStatus = 'INACTIVE' AND u.active = FALSE))",
        countQuery = "SELECT COUNT(u) FROM AppUser u " +
            "WHERE (:search IS NULL " +
            "   OR LOWER(u.employeeCode) LIKE :pattern ESCAPE '!' " +
            "   OR LOWER(u.firstName) LIKE :pattern ESCAPE '!' " +
            "   OR LOWER(u.lastName) LIKE :pattern ESCAPE '!' " +
            "   OR LOWER(u.email) LIKE :pattern ESCAPE '!') " +
            "AND (:departmentId IS NULL OR u.departmentId = :departmentId) " +
            "AND (:accountStatus IS NULL " +
            "   OR (:accountStatus = 'ACTIVE' AND u.active = TRUE) " +
            "   OR (:accountStatus = 'INACTIVE' AND u.active = FALSE))"
    )
    Page<Long> findAccountIdPage(
            @Param("search") String search,
            @Param("pattern") String pattern,
            @Param("departmentId") Long departmentId,
            @Param("accountStatus") String accountStatus,
            Pageable pageable
    );

    /** The accounts behind a page of identifiers, in the order the page was built with. */
    List<AppUser> findByUserIdIn(Collection<Long> userIds, Pageable pageable);
}
