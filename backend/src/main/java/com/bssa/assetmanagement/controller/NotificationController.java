package com.bssa.assetmanagement.controller;

import com.bssa.assetmanagement.entity.Notification;
import com.bssa.assetmanagement.service.NotificationService;
import com.bssa.assetmanagement.service.PagingSupport;
import org.springframework.data.domain.Page;
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

@Controller
@RequestMapping("/notifications")
public class NotificationController {

    /** The only two views the inbox offers; anything else in the URL reads as "all". */
    private static final String ALL_FILTER = "all";
    private static final String UNREAD_FILTER = "unread";

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping
    public String listNotifications(
            @RequestParam(required = false, defaultValue = ALL_FILTER) String filter,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            Authentication authentication,
            Model model) {

        String userEmail = authentication.getName();
        boolean unreadOnly = UNREAD_FILTER.equalsIgnoreCase(filter);
        int pageSize = PagingSupport.safeSize(size);

        // The links on the page are rebuilt from the accepted filter, never from
        // whatever the address bar happened to contain.
        String filterQuery = ListPageView.query()
                .add("filter", unreadOnly ? UNREAD_FILTER : ALL_FILTER)
                .add("size", pageSize)
                .toString();

        ListPageView.addPagingState(model, pageSize, filterQuery, unreadOnly);
        model.addAttribute("currentFilter", unreadOnly ? UNREAD_FILTER : ALL_FILTER);
        model.addAttribute("unreadCount", notificationService.getUnreadCountForUser(userEmail));

        Page<Notification> notifications = notificationService.getNotificationsForUserPage(
                userEmail, unreadOnly, page, pageSize);
        ListPageView.addPageAttributes(model, "notifications", notifications);

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
