package com.bssa.assetmanagement.service;

import com.bssa.assetmanagement.entity.AppUser;
import com.bssa.assetmanagement.entity.Notification;
import com.bssa.assetmanagement.repository.AppUserRepository;
import com.bssa.assetmanagement.repository.NotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NotificationServiceTest {

    private final NotificationRepository notificationRepository = mock(NotificationRepository.class);
    private final AppUserRepository appUserRepository = mock(AppUserRepository.class);

    private final NotificationService service = new NotificationService(
            notificationRepository,
            appUserRepository
    );

    private AppUser activeUser;
    private AppUser inactiveUser;
    private AppUser otherUser;
    private AppUser manager1;
    private AppUser manager2;

    @BeforeEach
    void setUp() {
        reset(notificationRepository, appUserRepository);

        activeUser = new AppUser();
        activeUser.setUserId(1L);
        activeUser.setEmail("active@example.com");
        activeUser.setFirstName("Active");
        activeUser.setLastName("User");
        activeUser.setActive(true);

        inactiveUser = new AppUser();
        inactiveUser.setUserId(2L);
        inactiveUser.setEmail("inactive@example.com");
        inactiveUser.setActive(false);

        otherUser = new AppUser();
        otherUser.setUserId(3L);
        otherUser.setEmail("other@example.com");
        otherUser.setActive(true);

        manager1 = new AppUser();
        manager1.setUserId(10L);
        manager1.setEmail("manager1@example.com");
        manager1.setActive(true);

        manager2 = new AppUser();
        manager2.setUserId(11L);
        manager2.setEmail("manager2@example.com");
        manager2.setActive(true);

        when(appUserRepository.findByEmail("active@example.com")).thenReturn(Optional.of(activeUser));
        when(appUserRepository.findByEmail("other@example.com")).thenReturn(Optional.of(otherUser));
        when(appUserRepository.findByEmail("inactive@example.com")).thenReturn(Optional.of(inactiveUser));
    }

    @Test
    void createNotification_success() {
        when(notificationRepository.save(any(Notification.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Notification result = service.createNotification(
                activeUser,
                "Request Approved",
                "Your request has been approved.",
                "REQUEST_APPROVED",
                "REQUEST",
                100L
        );

        assertNotNull(result);
        assertEquals("Request Approved", result.getTitle());
        assertEquals("Your request has been approved.", result.getMessage());
        assertEquals("REQUEST_APPROVED", result.getNotificationType());
        assertEquals("REQUEST", result.getEntityType());
        assertEquals(100L, result.getEntityId());
        assertFalse(result.isRead());
        assertEquals(activeUser, result.getUser());
        verify(notificationRepository).save(any(Notification.class));
    }

    @Test
    void createNotification_inactiveRecipient_ignored() {
        Notification result = service.createNotification(
                inactiveUser,
                "Notice",
                "Message",
                "INFO",
                null,
                null
        );

        assertNull(result);
        verify(notificationRepository, never()).save(any());
    }

    @Test
    void createNotification_nullOrBlankTitle_ignored() {
        Notification result = service.createNotification(
                activeUser,
                "   ",
                "Message",
                "INFO",
                null,
                null
        );

        assertNull(result);
        verify(notificationRepository, never()).save(any());
    }

    @Test
    void createNotification_deduplication_preventsDuplicates() {
        when(notificationRepository.existsByUser_UserIdAndNotificationTypeAndEntityTypeAndEntityId(
                1L, "REQUEST_CREATED", "REQUEST", 50L)).thenReturn(true);

        Notification result = service.createNotification(
                activeUser,
                "New Request",
                "Request 50 submitted",
                "REQUEST_CREATED",
                "REQUEST",
                50L
        );

        assertNull(result);
        verify(notificationRepository, never()).save(any());
    }

    @Test
    void createNotification_truncatesLongTitleAndMessage() {
        when(notificationRepository.save(any(Notification.class))).thenAnswer(invocation -> invocation.getArgument(0));

        String longTitle = "A".repeat(250);
        String longMessage = "B".repeat(1200);

        Notification result = service.createNotification(
                activeUser,
                longTitle,
                longMessage,
                "ALERT",
                null,
                null
        );

        assertNotNull(result);
        assertTrue(result.getTitle().length() <= 200);
        assertTrue(result.getTitle().endsWith("..."));
        assertTrue(result.getMessage().length() <= 1000);
        assertTrue(result.getMessage().endsWith("..."));
    }

    @Test
    void notifyInventoryManagers_excludesSpecifiedUser() {
        when(appUserRepository.findByActiveTrueAndRoles_Name("INVENTORY_MANAGER"))
                .thenReturn(List.of(manager1, manager2));
        when(notificationRepository.save(any(Notification.class))).thenAnswer(inv -> inv.getArgument(0));

        // When manager1 is excluded (e.g. self-requester)
        service.notifyInventoryManagers(
                "New Request",
                "Requires review",
                "REQUEST_CREATED",
                "REQUEST",
                10L,
                manager1
        );

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository, times(1)).save(captor.capture());
        assertEquals(manager2.getUserId(), captor.getValue().getUser().getUserId());
    }

    @Test
    void markAsRead_success() {
        Notification notification = new Notification(activeUser, "Title", "Msg", "INFO", null, null);
        notification.setNotificationId(99L);
        notification.setRead(false);

        when(notificationRepository.findById(99L)).thenReturn(Optional.of(notification));
        when(notificationRepository.save(any(Notification.class))).thenAnswer(inv -> inv.getArgument(0));

        service.markAsRead(99L, "active@example.com");

        assertTrue(notification.isRead());
        verify(notificationRepository).save(notification);
    }

    @Test
    void markAsRead_unrelatedUser_forbidden() {
        Notification notification = new Notification(activeUser, "Title", "Msg", "INFO", null, null);
        notification.setNotificationId(99L);
        notification.setRead(false);

        when(notificationRepository.findById(99L)).thenReturn(Optional.of(notification));

        // otherUser attempts to mark activeUser's notification as read
        assertThrows(AccessDeniedException.class, () ->
                service.markAsRead(99L, "other@example.com"));

        assertFalse(notification.isRead());
        verify(notificationRepository, never()).save(any());
    }

    @Test
    void markAsRead_notFound_throwsException() {
        when(notificationRepository.findById(999L)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () ->
                service.markAsRead(999L, "active@example.com"));
    }

    @Test
    void markAllAsRead_callsRepository() {
        service.markAllAsRead("active@example.com");

        verify(notificationRepository).markAllAsReadForUser(1L);
    }

    @Test
    void checkLowStockAndNotify_triggersWhenThresholdMet() {
        when(appUserRepository.findByActiveTrueAndRoles_Name("INVENTORY_MANAGER"))
                .thenReturn(List.of(manager1));
        when(notificationRepository.save(any(Notification.class))).thenAnswer(inv -> inv.getArgument(0));

        service.checkLowStockAndNotify(5L, "Dell Latitude Laptop", 2L);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(captor.capture());
        assertEquals("LOW_STOCK_WARNING", captor.getValue().getNotificationType());
        assertTrue(captor.getValue().getMessage().contains("Dell Latitude Laptop"));
        assertTrue(captor.getValue().getMessage().contains("2"));
    }

    @Test
    void checkLowStockAndNotify_doesNotTriggerWhenAboveThreshold() {
        service.checkLowStockAndNotify(5L, "Dell Latitude Laptop", 5L);

        verify(notificationRepository, never()).save(any());
    }
}
