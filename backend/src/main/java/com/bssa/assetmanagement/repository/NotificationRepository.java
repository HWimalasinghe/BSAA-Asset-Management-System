package com.bssa.assetmanagement.repository;

import com.bssa.assetmanagement.entity.Notification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    List<Notification> findByUser_UserIdOrderByCreatedAtDesc(Long userId);

    List<Notification> findByUser_UserIdAndReadFalseOrderByCreatedAtDesc(Long userId);

    long countByUser_UserIdAndReadFalse(Long userId);

    Optional<Notification> findByNotificationIdAndUser_UserId(Long notificationId, Long userId);

    boolean existsByUser_UserIdAndNotificationTypeAndEntityTypeAndEntityId(
            Long userId, String notificationType, String entityType, Long entityId);

    @Modifying
    @Query("UPDATE Notification n SET n.read = true WHERE n.user.userId = :userId AND n.read = false")
    int markAllAsReadForUser(@Param("userId") Long userId);
}
