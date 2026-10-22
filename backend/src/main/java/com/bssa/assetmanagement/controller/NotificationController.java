package com.bssa.assetmanagement.controller;

import com.bssa.assetmanagement.entity.Notification;
import com.bssa.assetmanagement.service.NotificationService;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

@Controller
@RequestMapping("/notifications")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping
    public String listNotifications(
            @RequestParam(required = false, defaultValue = "all") String filter,
            Authentication authentication,
            Model model) {

        String userEmail = authentication.getName();
        List<Notification> notifications;

        if ("unread".equalsIgnoreCase(filter)) {
            notifications = notificationService.getUnreadNotificationsForUser(userEmail);
        } else {
            notifications = notificationService.getNotificationsForUser(userEmail);
        }

        long unreadCount = notificationService.getUnreadCountForUser(userEmail);

        model.addAttribute("notifications", notifications);
        model.addAttribute("unreadCount", unreadCount);
        model.addAttribute("currentFilter", filter);

        return "notifications/list";
    }

    @PostMapping("/{id}/read")
    public String markAsRead(
            @PathVariable Long id,
            Authentication authentication,
            RedirectAttributes redirectAttributes) {

        try {
            notificationService.markAsRead(id, authentication.getName());
            redirectAttributes.addFlashAttribute("successMessage", "Notification marked as read.");
        } catch (AccessDeniedException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        } catch (IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }

        return "redirect:/notifications";
    }

    @PostMapping("/read-all")
    public String markAllAsRead(
            Authentication authentication,
            RedirectAttributes redirectAttributes) {

        try {
            notificationService.markAllAsRead(authentication.getName());
            redirectAttributes.addFlashAttribute("successMessage", "All notifications marked as read.");
        } catch (Exception ex) {
            redirectAttributes.addFlashAttribute("errorMessage", "Failed to mark notifications as read.");
        }

        return "redirect:/notifications";
    }
}
