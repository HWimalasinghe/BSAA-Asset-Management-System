package com.bssa.assetmanagement.service;

import com.bssa.assetmanagement.entity.AppUser;
import com.bssa.assetmanagement.entity.Notification;
import com.bssa.assetmanagement.repository.AppUserRepository;
import com.bssa.assetmanagement.repository.NotificationRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;

@Service
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final AppUserRepository appUserRepository;

    public NotificationService(
            NotificationRepository notificationRepository,
            AppUserRepository appUserRepository) {
        this.notificationRepository = notificationRepository;
        this.appUserRepository = appUserRepository;
    }

    @Transactional
    public Notification createNotification(
            AppUser recipient,
            String title,
            String message,
            String notificationType,
            String entityType,
            Long entityId) {

        if (recipient == null || !recipient.isActive() || recipient.getUserId() == null) {
            return null;
        }

        if (title == null || title.isBlank() || message == null || message.isBlank()) {
            return null;
        }

        // Deduplication rule: prevent creating identical notification if already exists
        if (entityType != null && entityId != null) {
            boolean exists = notificationRepository
                    .existsByUser_UserIdAndNotificationTypeAndEntityTypeAndEntityId(
                            recipient.getUserId(), notificationType, entityType, entityId);
            if (exists) {
                return null;
            }
        }

        String safeTitle = title.trim();
        if (safeTitle.length() > 200) {
            safeTitle = safeTitle.substring(0, 197) + "...";
        }

        String safeMessage = message.trim();
        if (safeMessage.length() > 1000) {
            safeMessage = safeMessage.substring(0, 997) + "...";
        }

        Notification notification = new Notification(
                recipient,
                safeTitle,
                safeMessage,
                notificationType,
                entityType,
                entityId
        );

        return notificationRepository.save(notification);
    }

    @Transactional
    public void notifyUsers(
            Collection<AppUser> recipients,
            String title,
            String message,
            String notificationType,
            String entityType,
            Long entityId) {

        if (recipients == null || recipients.isEmpty()) {
            return;
        }

        for (AppUser recipient : recipients) {
            createNotification(recipient, title, message, notificationType, entityType, entityId);
        }
    }

    @Transactional
    public void notifyInventoryManagers(
            String title,
            String message,
            String notificationType,
            String entityType,
            Long entityId,
            AppUser excludeUser) {

        List<AppUser> managers = appUserRepository.findByActiveTrueAndRoles_Name("INVENTORY_MANAGER");
        for (AppUser manager : managers) {
            if (excludeUser != null && excludeUser.getUserId() != null
                    && excludeUser.getUserId().equals(manager.getUserId())) {
                continue;
            }
            createNotification(manager, title, message, notificationType, entityType, entityId);
        }
    }

    public static final int LOW_STOCK_THRESHOLD = 2;

    @Transactional
    public void checkLowStockAndNotify(Long propertyTypeId, String propertyTypeName, long availableCount) {
        if (availableCount <= LOW_STOCK_THRESHOLD) {
            notifyInventoryManagers(
                    "Low Availability Alert: " + propertyTypeName,
                    "Only " + availableCount + " available items remaining for " + propertyTypeName + ".",
                    "LOW_STOCK_WARNING",
                    "PROPERTY_TYPE",
                    propertyTypeId,
                    null
            );
        }
    }

    @Transactional(readOnly = true)
    public List<Notification> getNotificationsForUser(String userEmail) {
        AppUser user = getActiveUser(userEmail);
        return notificationRepository.findByUser_UserIdOrderByCreatedAtDesc(user.getUserId());
    }

    @Transactional(readOnly = true)
    public List<Notification> getUnreadNotificationsForUser(String userEmail) {
        AppUser user = getActiveUser(userEmail);
        return notificationRepository.findByUser_UserIdAndReadFalseOrderByCreatedAtDesc(user.getUserId());
    }

    @Transactional(readOnly = true)
    public long getUnreadCountForUser(String userEmail) {
        if (userEmail == null || userEmail.isBlank()) {
            return 0;
        }
        return appUserRepository.findByEmail(userEmail)
                .map(user -> notificationRepository.countByUser_UserIdAndReadFalse(user.getUserId()))
                .orElse(0L);
    }

    @Transactional
    public void markAsRead(Long notificationId, String userEmail) {
        if (notificationId == null) {
            throw new IllegalArgumentException("Notification ID is required.");
        }

        AppUser user = getActiveUser(userEmail);

        Notification notification = notificationRepository.findById(notificationId)
                .orElseThrow(() -> new IllegalArgumentException("Notification not found."));

        // Privacy rule: Unrelated users cannot read another user's notifications!
        if (!notification.getUser().getUserId().equals(user.getUserId())) {
            throw new AccessDeniedException("You are not permitted to access another user's notification.");
        }

        if (!notification.isRead()) {
            notification.setRead(true);
            notificationRepository.save(notification);
        }
    }

    @Transactional
    public void markAllAsRead(String userEmail) {
        AppUser user = getActiveUser(userEmail);
        notificationRepository.markAllAsReadForUser(user.getUserId());
    }

    private AppUser getActiveUser(String email) {
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("User email cannot be empty.");
        }
        AppUser user = appUserRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("User not found for email: " + email));
        if (!user.isActive()) {
            throw new IllegalStateException("User account is inactive.");
        }
        return user;
    }
}
