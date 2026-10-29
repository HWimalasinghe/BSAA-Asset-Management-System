package com.bssa.assetmanagement.repository;

import com.bssa.assetmanagement.entity.Notification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    /**
     * One page of somebody's own notifications, newest first.
     *
     * The identifier is part of the order so that notifications created in the
     * same second cannot swap places between two pages.
     */
    Page<Notification> findByUser_UserIdOrderByCreatedAtDescNotificationIdDesc(
            Long userId, Pageable pageable);

    Page<Notification> findByUser_UserIdAndReadFalseOrderByCreatedAtDescNotificationIdDesc(
            Long userId, Pageable pageable);

    long countByUser_UserIdAndReadFalse(Long userId);

    Optional<Notification> findByNotificationIdAndUser_UserId(Long notificationId, Long userId);

    boolean existsByUser_UserIdAndNotificationTypeAndEntityTypeAndEntityId(
            Long userId, String notificationType, String entityType, Long entityId);

    @Modifying
    @Query("UPDATE Notification n SET n.read = true WHERE n.user.userId = :userId AND n.read = false")
    int markAllAsReadForUser(@Param("userId") Long userId);
}
