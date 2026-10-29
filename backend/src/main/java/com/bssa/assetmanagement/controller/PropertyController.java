package com.bssa.assetmanagement.controller;

import com.bssa.assetmanagement.entity.AppUser;
import com.bssa.assetmanagement.entity.Property;
import com.bssa.assetmanagement.repository.AppUserRepository;
import com.bssa.assetmanagement.repository.PhysicalAreaRepository;
import com.bssa.assetmanagement.repository.PropertyCategoryRepository;
import com.bssa.assetmanagement.repository.PropertyRepository;
import com.bssa.assetmanagement.repository.PropertyTypeRepository;
import com.bssa.assetmanagement.service.CsvEncoder;
import com.bssa.assetmanagement.service.PagingSupport;
import com.bssa.assetmanagement.service.PropertyService;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Controller
@RequestMapping("/properties")
public class PropertyController {

    /** Columns written to the CSV export: the register's own columns. */
    private static final List<String> EXPORT_HEADERS = List.of(
            "Asset Code", "Property Type", "Category", "Physical Area",
            "Status", "Condition", "Serial Number", "Purchase Date", "Description");

    private final PropertyRepository propertyRepository;
    private final PropertyTypeRepository propertyTypeRepository;
    private final PropertyCategoryRepository propertyCategoryRepository;
    private final PhysicalAreaRepository physicalAreaRepository;
    private final PropertyService propertyService;
    private final AppUserRepository appUserRepository;

    public PropertyController(
            PropertyRepository propertyRepository,
            PropertyTypeRepository propertyTypeRepository,
            PropertyCategoryRepository propertyCategoryRepository,
            PhysicalAreaRepository physicalAreaRepository,
            PropertyService propertyService,
            AppUserRepository appUserRepository) {
        this.propertyRepository = propertyRepository;
        this.propertyTypeRepository = propertyTypeRepository;
        this.propertyCategoryRepository = propertyCategoryRepository;
        this.physicalAreaRepository = physicalAreaRepository;
        this.propertyService = propertyService;
        this.appUserRepository = appUserRepository;
    }

    @GetMapping
    public String listProperties(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String condition,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) Long propertyTypeId,
            @RequestParam(required = false) Long areaId,
            @RequestParam(required = false) LocalDate purchaseFrom,
            @RequestParam(required = false) LocalDate purchaseTo,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String dir,
            Model model,
            Authentication authentication) {

        if (!isCallerActive(authentication)) {
            return "redirect:/login?logout";
        }

        String sortKey = PagingSupport.safeSortKey(
                sort, PropertyService.ASSET_REGISTER_SORT_OPTIONS, PropertyService.DEFAULT_ASSET_REGISTER_SORT);
        String direction = PagingSupport.safeDirection(dir);
        int pageSize = PagingSupport.safeSize(size);

        prepareRegisterView(model, search, status, condition, categoryId, propertyTypeId,
                areaId, purchaseFrom, purchaseTo, sortKey, direction, pageSize);

        try {
            addPageToModel(model, propertyService.searchProperties(
                    search, status, condition, categoryId, propertyTypeId, areaId,
                    purchaseFrom, purchaseTo, page, pageSize, sortKey, direction));
        } catch (IllegalArgumentException ex) {
            // A filter sent by hand cannot be applied. Say so without repeating
            // the value back, and show an empty result rather than a stack trace.
            model.addAttribute("errorMessage", "That filter is not one of the available options.");
            ListPageView.addEmptyPageAttributes(model, "properties");
        }

        return "properties/list";
    }

    /**
     * Downloads the register for the current search and filters.
     *
     * The same SecurityConfig rule that guards the register page guards this
     * download, and the same service validates the filters, so the file can
     * only ever contain rows and columns the caller is already entitled to.
     */
    @GetMapping("/export.csv")
    public ResponseEntity<byte[]> exportRegister(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String condition,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) Long propertyTypeId,
            @RequestParam(required = false) Long areaId,
            @RequestParam(required = false) LocalDate purchaseFrom,
            @RequestParam(required = false) LocalDate purchaseTo,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String dir,
            Authentication authentication) {

        if (!isCallerActive(authentication)) {
            return ResponseEntity.status(HttpStatus.FOUND)
                    .header(HttpHeaders.LOCATION, "/login?logout")
                    .build();
        }

        String sortKey = PagingSupport.safeSortKey(
                sort, PropertyService.ASSET_REGISTER_SORT_OPTIONS, PropertyService.DEFAULT_ASSET_REGISTER_SORT);
        String direction = PagingSupport.safeDirection(dir);

        List<Property> rows;
        try {
            rows = propertyService.findMatchingProperties(
                    search, status, condition, categoryId, propertyTypeId, areaId,
                    purchaseFrom, purchaseTo, sortKey, direction);
        } catch (IllegalArgumentException ex) {
            // Safe, fixed wording: the rejected value is not echoed back.
            return ResponseEntity.badRequest()
                    .contentType(MediaType.TEXT_PLAIN)
                    .body("The export could not be produced because one of the selected filters is not recognised."
                            .getBytes(StandardCharsets.UTF_8));
        }

        List<List<String>> csvRows = new ArrayList<>(rows.size());
        for (Property property : rows) {
            // Arrays.asList, not List.of: serial number, purchase date and
            // description are all optional on an asset.
            csvRows.add(java.util.Arrays.asList(
                    property.getPropertyCode(),
                    property.getPropertyType().getName(),
                    property.getPropertyType().getCategory().getName(),
                    property.getArea().getName(),
                    property.getStatus(),
                    property.getCondition(),
                    property.getSerialNumber(),
                    property.getPurchaseDate() == null ? null : property.getPurchaseDate().toString(),
                    property.getDescription()));
        }

        byte[] body = CsvEncoder.toCsvBytes(EXPORT_HEADERS, csvRows);
        String filename = "asset-register-" + LocalDate.now() + ".csv";

        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(body);
    }

    /** Shared model data for the register page: dropdown options and current query. */
    private void prepareRegisterView(
            Model model,
            String search,
            String status,
            String condition,
            Long categoryId,
            Long propertyTypeId,
            Long areaId,
            LocalDate purchaseFrom,
            LocalDate purchaseTo,
            String sortKey,
            String direction,
            int pageSize) {

        model.addAttribute("search", search);
        // The values as the register applied them, so a filter written in the
        // wrong case still shows as selected and a rejected one shows nothing.
        model.addAttribute("statusFilter", PropertyService.acceptedStatus(status));
        model.addAttribute("conditionFilter", PropertyService.acceptedCondition(condition));
        model.addAttribute("categoryId", categoryId);
        model.addAttribute("propertyTypeId", propertyTypeId);
        model.addAttribute("areaId", areaId);
        model.addAttribute("purchaseFrom", purchaseFrom);
        model.addAttribute("purchaseTo", purchaseTo);
        model.addAttribute("statusOptions", sortedOptions(PropertyService.ALLOWED_STATUSES));
        model.addAttribute("conditionOptions", sortedOptions(PropertyService.ALLOWED_CONDITIONS));
        model.addAttribute("categoryOptions", propertyCategoryRepository.findAllByActiveTrueOrderByNameAsc());
        model.addAttribute("typeOptions", propertyTypeRepository.findAllByActiveTrueOrderByNameAsc());
        model.addAttribute("areaOptions", physicalAreaRepository.findAllByActiveTrueOrderByNameAsc());

        // Everything except the page number and the order, so that the pager,
        // column-header and export links can preserve the search and filters
        // without repeating them. ListPageView adds the order back for the links
        // that need it. Only values the register actually accepts go in here, so
        // a rejected filter is never echoed back into a link.
        String baseQuery = buildBaseQuery(
                search,
                PropertyService.acceptedStatus(status),
                PropertyService.acceptedCondition(condition),
                categoryId, propertyTypeId, areaId,
                purchaseFrom, purchaseTo, pageSize);

        ListPageView.addListState(model, sortKey, direction, pageSize, baseQuery,
                hasActiveFilter(search, status, condition, categoryId, propertyTypeId, areaId,
                        purchaseFrom, purchaseTo));
    }

    private void addPageToModel(Model model, Page<Property> result) {
        ListPageView.addPageAttributes(model, "properties", result);
    }

    private static List<String> sortedOptions(java.util.Set<String> values) {
        return values.stream().sorted().toList();
    }

    /** Lets the page say "nothing matches" instead of "nothing is registered". */
    private static boolean hasActiveFilter(
            String search,
            String status,
            String condition,
            Long categoryId,
            Long propertyTypeId,
            Long areaId,
            LocalDate purchaseFrom,
            LocalDate purchaseTo) {

        return notBlank(search)
                || notBlank(status)
                || notBlank(condition)
                || categoryId != null
                || propertyTypeId != null
                || areaId != null
                || purchaseFrom != null
                || purchaseTo != null;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * Builds a URL-encoded query string from the active filters. See
     * {@link ListPageView.Query} for why only accepted values belong here.
     */
    static String buildBaseQuery(
            String search,
            String status,
            String condition,
            Long categoryId,
            Long propertyTypeId,
            Long areaId,
            LocalDate purchaseFrom,
            LocalDate purchaseTo,
            int pageSize) {

        return ListPageView.query()
                .add("search", search)
                .add("status", status)
                .add("condition", condition)
                .add("categoryId", categoryId)
                .add("propertyTypeId", propertyTypeId)
                .add("areaId", areaId)
                .add("purchaseFrom", purchaseFrom)
                .add("purchaseTo", purchaseTo)
                .add("size", pageSize)
                .toString();
    }

    @GetMapping("/new")
    public String showCreateForm(
            Model model,
            Authentication authentication) {

        if (!isCallerActive(authentication)) {
            return "redirect:/login?logout";
        }

        model.addAttribute(
                "propertyTypes",
                propertyTypeRepository.findAllByActiveTrueOrderByNameAsc()
        );
        model.addAttribute(
                "areas",
                physicalAreaRepository.findAllByActiveTrueOrderByNameAsc()
        );

        return "properties/form";
    }

    @GetMapping("/{id}/edit")
    public String showEditForm(
            @PathVariable Long id,
            Model model,
            Authentication authentication,
            RedirectAttributes redirectAttributes) {

        if (!isCallerActive(authentication)) {
            return "redirect:/login?logout";
        }

        Property property = propertyRepository.findById(id).orElse(null);

        if (property == null) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "Property not found."
            );
            return "redirect:/properties";
        }

        model.addAttribute("property", property);
        model.addAttribute(
                "propertyTypes",
                propertyTypeRepository.findAllByOrderByCategory_NameAscNameAsc()
        );
        model.addAttribute(
                "areas",
                physicalAreaRepository.findAllByOrderByNameAsc()
        );

        return "properties/form";
    }

    @PostMapping
    public String createProperty(
            @RequestParam String propertyCode,
            @RequestParam Long propertyTypeId,
            @RequestParam Long areaId,
            @RequestParam(required = false) String serialNumber,
            @RequestParam(required = false) String description,
            @RequestParam LocalDate purchaseDate,
            @RequestParam(defaultValue = "GOOD") String condition,
            Authentication authentication,
            RedirectAttributes redirectAttributes) {

        if (!isCallerActive(authentication)) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "Your account has been deactivated."
            );
            return "redirect:/login?logout";
        }

        try {
            propertyService.createProperty(
                    propertyCode,
                    propertyTypeId,
                    areaId,
                    serialNumber,
                    description,
                    purchaseDate,
                    condition,
                    authentication.getName()
            );

            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "Property registered successfully."
            );
            return "redirect:/properties";

        } catch (IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    ex.getMessage()
            );
            return "redirect:/properties/new";
        }
    }

    @PostMapping("/{id}/edit")
    public String updateProperty(
            @PathVariable Long id,
            @RequestParam String propertyCode,
            @RequestParam Long propertyTypeId,
            @RequestParam Long areaId,
            @RequestParam(required = false) String serialNumber,
            @RequestParam(required = false) String description,
            @RequestParam LocalDate purchaseDate,
            @RequestParam(defaultValue = "GOOD") String condition,
            Authentication authentication,
            RedirectAttributes redirectAttributes) {

        if (!isCallerActive(authentication)) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "Your account has been deactivated."
            );
            return "redirect:/login?logout";
        }

        try {
            propertyService.updateProperty(
                    id,
                    propertyCode,
                    propertyTypeId,
                    areaId,
                    serialNumber,
                    description,
                    purchaseDate,
                    condition,
                    authentication.getName()
            );

            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "Property updated successfully."
            );
            return "redirect:/properties";

        } catch (IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    ex.getMessage()
            );
            return "redirect:/properties/" + id + "/edit";
        }
    }

    @PostMapping("/{id}/retire")
    public String retireProperty(
            @PathVariable Long id,
            @RequestParam(required = false) String reason,
            Authentication authentication,
            RedirectAttributes redirectAttributes) {

        if (!isCallerActive(authentication)) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "Your account has been deactivated."
            );
            return "redirect:/login?logout";
        }

        try {
            propertyService.retireProperty(id, reason, authentication.getName());
            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "Asset retired successfully."
            );
        } catch (IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    ex.getMessage()
            );
        }

        return "redirect:/properties";
    }

    @PostMapping("/{id}/status")
    public String updateStatus(
            @PathVariable Long id,
            @RequestParam String newStatus,
            @RequestParam(required = false) String reason,
            Authentication authentication,
            RedirectAttributes redirectAttributes) {

        if (!isCallerActive(authentication)) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "Your account has been deactivated."
            );
            return "redirect:/login?logout";
        }

        try {
            propertyService.changeStatus(id, newStatus, reason, authentication.getName());
            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "Asset status updated successfully."
            );
        } catch (IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    ex.getMessage()
            );
        }

        return "redirect:/properties";
    }

    private boolean isCallerActive(Authentication authentication) {
        if (authentication == null) {
            return false;
        }

        return appUserRepository.findByEmail(authentication.getName())
                .map(AppUser::isActive)
                .orElse(true);
    }
}
