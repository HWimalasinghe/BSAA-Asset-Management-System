package com.bssa.assetmanagement.controller;

import com.bssa.assetmanagement.entity.AppUser;
import com.bssa.assetmanagement.entity.Notification;
import com.bssa.assetmanagement.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NotificationControllerTest {

    private final NotificationService notificationService = mock(NotificationService.class);
    private final NotificationController controller = new NotificationController(notificationService);
    private final Authentication authentication = mock(Authentication.class);

    @BeforeEach
    void setUp() {
        reset(notificationService, authentication);
        when(authentication.getName()).thenReturn("user@example.com");
    }

    @Test
    void listNotifications_defaultAll_returnsViewWithAttributes() {
        Model model = new ConcurrentModel();
        Notification n = new Notification(new AppUser(), "Title", "Message", "INFO", null, null);

        when(notificationService.getNotificationsForUser("user@example.com")).thenReturn(List.of(n));
        when(notificationService.getUnreadCountForUser("user@example.com")).thenReturn(1L);

        String view = controller.listNotifications("all", authentication, model);

        assertEquals("notifications/list", view);
        assertEquals(List.of(n), model.getAttribute("notifications"));
        assertEquals(1L, model.getAttribute("unreadCount"));
        assertEquals("all", model.getAttribute("currentFilter"));
        verify(notificationService).getNotificationsForUser("user@example.com");
    }

    @Test
    void listNotifications_filterUnread_callsUnreadServiceMethod() {
        Model model = new ConcurrentModel();
        when(notificationService.getUnreadNotificationsForUser("user@example.com")).thenReturn(List.of());
        when(notificationService.getUnreadCountForUser("user@example.com")).thenReturn(0L);

        String view = controller.listNotifications("unread", authentication, model);

        assertEquals("notifications/list", view);
        verify(notificationService).getUnreadNotificationsForUser("user@example.com");
    }

    @Test
    void markAsRead_success_redirectsWithFlashMessage() {
        RedirectAttributes ra = new RedirectAttributesModelMap();

        String view = controller.markAsRead(42L, authentication, ra);

        assertEquals("redirect:/notifications", view);
        assertEquals("Notification marked as read.", ra.getFlashAttributes().get("successMessage"));
        verify(notificationService).markAsRead(42L, "user@example.com");
    }

    @Test
    void markAsRead_accessDenied_redirectsWithErrorMessage() {
        RedirectAttributes ra = new RedirectAttributesModelMap();
        doThrow(new AccessDeniedException("Access denied")).when(notificationService).markAsRead(42L, "user@example.com");

        String view = controller.markAsRead(42L, authentication, ra);

        assertEquals("redirect:/notifications", view);
        assertEquals("Access denied", ra.getFlashAttributes().get("errorMessage"));
    }

    @Test
    void markAllAsRead_success_redirectsWithFlashMessage() {
        RedirectAttributes ra = new RedirectAttributesModelMap();

        String view = controller.markAllAsRead(authentication, ra);

        assertEquals("redirect:/notifications", view);
        assertEquals("All notifications marked as read.", ra.getFlashAttributes().get("successMessage"));
        verify(notificationService).markAllAsRead("user@example.com");
    }

    @Test
    void listNotifications_withUntrustedContent_preservesInputForTemplateEscaping() {
        Model model = new ConcurrentModel();
        String maliciousTitle = "<script>alert('xss')</script>";
        String maliciousMessage = "<img src=x onerror=alert(1)>";
        Notification n = new Notification(new AppUser(), maliciousTitle, maliciousMessage, "ALERT", null, null);

        when(notificationService.getNotificationsForUser("user@example.com")).thenReturn(List.of(n));

        controller.listNotifications("all", authentication, model);

        @SuppressWarnings("unchecked")
        List<Notification> modelList = (List<Notification>) model.getAttribute("notifications");
        assertNotNull(modelList);
        assertEquals(1, modelList.size());
        assertEquals(maliciousTitle, modelList.get(0).getTitle());
        assertEquals(maliciousMessage, modelList.get(0).getMessage());
    }
}
