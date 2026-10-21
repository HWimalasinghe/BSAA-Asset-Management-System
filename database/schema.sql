
-- =====================================================
-- BSAA Asset Management System
-- Database Schema
-- Section A: Organization and User Management
-- MySQL 8.0+
-- =====================================================

-- 1. Departments
CREATE TABLE departments (
    department_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL UNIQUE,
    description VARCHAR(255) NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP
);

-- 2. Users
CREATE TABLE users (
    user_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    employee_code VARCHAR(50) NOT NULL UNIQUE,
    first_name VARCHAR(100) NOT NULL,
    last_name VARCHAR(100) NOT NULL,
    email VARCHAR(150) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    phone VARCHAR(30) NULL,
    department_id BIGINT NOT NULL,
    position VARCHAR(100) NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP,

    CONSTRAINT fk_users_department
        FOREIGN KEY (department_id)
        REFERENCES departments(department_id)
        ON DELETE RESTRICT
        ON UPDATE RESTRICT,

    INDEX idx_users_department_id (department_id),
    INDEX idx_users_is_active (is_active)
);

-- 3. Roles
CREATE TABLE roles (
    role_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(50) NOT NULL UNIQUE,
    description VARCHAR(255) NULL
);

-- 4. User Roles
-- Allows one user to have multiple roles.
CREATE TABLE user_roles (
    user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    assigned_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (user_id, role_id),

    CONSTRAINT fk_user_roles_user
        FOREIGN KEY (user_id)
        REFERENCES users(user_id)
        ON DELETE RESTRICT
        ON UPDATE RESTRICT,

    CONSTRAINT fk_user_roles_role
        FOREIGN KEY (role_id)
        REFERENCES roles(role_id)
        ON DELETE RESTRICT
        ON UPDATE RESTRICT,

    INDEX idx_user_roles_role_id (role_id)
);

-- 5. Physical Areas
CREATE TABLE physical_areas (
    area_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL UNIQUE,
    description VARCHAR(255) NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP
);

-- =====================================================
-- Section B: Property Master
-- =====================================================

-- 6. Property Categories
CREATE TABLE property_categories (
    category_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL UNIQUE,
    description VARCHAR(255) NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP
);

-- 7. Property Types
CREATE TABLE property_types (
    property_type_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    category_id BIGINT NOT NULL,
    name VARCHAR(100) NOT NULL,
    usage_type VARCHAR(20) NOT NULL,
    description VARCHAR(255) NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP,

    CONSTRAINT fk_property_types_category
        FOREIGN KEY (category_id)
        REFERENCES property_categories(category_id)
        ON DELETE RESTRICT
        ON UPDATE RESTRICT,

    CONSTRAINT chk_property_types_usage_type
        CHECK (usage_type IN ('COMMON', 'INDIVIDUAL')),

    CONSTRAINT uq_property_types_category_name_usage
        UNIQUE (category_id, name, usage_type),

    INDEX idx_property_types_category_id (category_id),
    INDEX idx_property_types_usage_type (usage_type)
);

-- 8. Properties
CREATE TABLE properties (
    property_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    property_code VARCHAR(50) NOT NULL UNIQUE,
    property_type_id BIGINT NOT NULL,
    area_id BIGINT NOT NULL,
    serial_number VARCHAR(100) NULL,
    description VARCHAR(255) NULL,

    -- Date the physical item was added/registered
    -- into BSSA inventory; NOT the supplier purchase date.
    purchase_date DATE NOT NULL,

    `condition` VARCHAR(20) NOT NULL DEFAULT 'GOOD',
    status VARCHAR(30) NOT NULL DEFAULT 'AVAILABLE',

    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP,

    CONSTRAINT fk_properties_property_type
        FOREIGN KEY (property_type_id)
        REFERENCES property_types(property_type_id)
        ON DELETE RESTRICT
        ON UPDATE RESTRICT,

    CONSTRAINT fk_properties_area
        FOREIGN KEY (area_id)
        REFERENCES physical_areas(area_id)
        ON DELETE RESTRICT
        ON UPDATE RESTRICT,

    CONSTRAINT chk_properties_condition
        CHECK (`condition` IN ('GOOD', 'DAMAGED', 'BROKEN')),

    CONSTRAINT chk_properties_status
        CHECK (status IN (
            'AVAILABLE',
            'ASSIGNED',
            'UNDER_INSPECTION',
            'DAMAGED',
            'UNDER_REPAIR',
            'LOST',
            'RETIRED'
        )),

    INDEX idx_properties_property_type_id (property_type_id),
    INDEX idx_properties_area_id (area_id),
    INDEX idx_properties_status (status),
    INDEX idx_properties_condition (`condition`)
);

-- =====================================================
-- Section C: Requests, Assignments, and Returns
-- =====================================================

-- 9. Property Requests
CREATE TABLE property_requests (
    request_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    requester_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    requested_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    reviewed_by BIGINT NULL,
    reviewed_at DATETIME NULL,
    rejection_reason VARCHAR(500) NULL,
    notes VARCHAR(500) NULL,

    CONSTRAINT fk_property_requests_requester
        FOREIGN KEY (requester_id)
        REFERENCES users(user_id)
        ON DELETE RESTRICT
        ON UPDATE RESTRICT,

    CONSTRAINT fk_property_requests_reviewer
        FOREIGN KEY (reviewed_by)
        REFERENCES users(user_id)
        ON DELETE RESTRICT
        ON UPDATE RESTRICT,

    CONSTRAINT chk_property_requests_status
        CHECK (status IN (
            'PENDING',
            'APPROVED',
            'REJECTED',
            'FULFILLED',
            'CANCELLED'
        )),

    INDEX idx_property_requests_requester_id (requester_id),
    INDEX idx_property_requests_status (status),
    INDEX idx_property_requests_reviewed_by (reviewed_by),
    INDEX idx_property_requests_requested_at (requested_at)
);

-- 10. Property Request Items
CREATE TABLE property_request_items (
    request_item_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id BIGINT NOT NULL,
    property_type_id BIGINT NOT NULL,
    quantity INT NOT NULL,
    notes VARCHAR(255) NULL,

    CONSTRAINT fk_request_items_request
        FOREIGN KEY (request_id)
        REFERENCES property_requests(request_id)
        ON DELETE RESTRICT
        ON UPDATE RESTRICT,

    CONSTRAINT fk_request_items_property_type
        FOREIGN KEY (property_type_id)
        REFERENCES property_types(property_type_id)
        ON DELETE RESTRICT
        ON UPDATE RESTRICT,

    CONSTRAINT chk_request_items_quantity
        CHECK (quantity > 0),

    INDEX idx_request_items_request_id (request_id),
    INDEX idx_request_items_property_type_id (property_type_id)
);

-- 11. Property Assignments
CREATE TABLE property_assignments (
    assignment_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    property_id BIGINT NOT NULL,
    employee_id BIGINT NOT NULL,
    issued_by BIGINT NOT NULL,
    area_id BIGINT NOT NULL,
    issued_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    condition_at_issue VARCHAR(20) NOT NULL,
    notes VARCHAR(500) NULL,

    CONSTRAINT fk_assignments_property
        FOREIGN KEY (property_id)
        REFERENCES properties(property_id)
        ON DELETE RESTRICT
        ON UPDATE RESTRICT,

    CONSTRAINT fk_assignments_employee
        FOREIGN KEY (employee_id)
        REFERENCES users(user_id)
        ON DELETE RESTRICT
        ON UPDATE RESTRICT,

    CONSTRAINT fk_assignments_issuer
        FOREIGN KEY (issued_by)
        REFERENCES users(user_id)
        ON DELETE RESTRICT
        ON UPDATE RESTRICT,

    CONSTRAINT fk_assignments_area
        FOREIGN KEY (area_id)
        REFERENCES physical_areas(area_id)
        ON DELETE RESTRICT
        ON UPDATE RESTRICT,

    CONSTRAINT chk_assignments_condition
        CHECK (condition_at_issue IN ('GOOD', 'DAMAGED', 'BROKEN')),

    INDEX idx_assignments_property_id (property_id),
    INDEX idx_assignments_employee_id (employee_id),
    INDEX idx_assignments_issued_by (issued_by),
    INDEX idx_assignments_area_id (area_id),
    INDEX idx_assignments_issued_at (issued_at)
);

-- 12. Property Returns
CREATE TABLE property_returns (
    return_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    assignment_id BIGINT NOT NULL,
    returned_by BIGINT NOT NULL,
    received_by BIGINT NOT NULL,
    returned_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `condition` VARCHAR(20) NOT NULL,
    notes VARCHAR(500) NULL,

    CONSTRAINT fk_property_returns_assignment
        FOREIGN KEY (assignment_id)
        REFERENCES property_assignments(assignment_id)
        ON DELETE RESTRICT
        ON UPDATE RESTRICT,

    CONSTRAINT fk_property_returns_returned_by
        FOREIGN KEY (returned_by)
        REFERENCES users(user_id)
        ON DELETE RESTRICT
        ON UPDATE RESTRICT,

    CONSTRAINT fk_property_returns_received_by
        FOREIGN KEY (received_by)
        REFERENCES users(user_id)
        ON DELETE RESTRICT
        ON UPDATE RESTRICT,

    CONSTRAINT uq_property_returns_assignment
    UNIQUE (assignment_id),

    CONSTRAINT chk_property_returns_condition
        CHECK (`condition` IN ('GOOD', 'DAMAGED', 'BROKEN')),

    INDEX idx_property_returns_assignment_id (assignment_id),
    INDEX idx_property_returns_returned_by (returned_by),
    INDEX idx_property_returns_received_by (received_by),
    INDEX idx_property_returns_returned_at (returned_at)
);

-- =====================================================
-- Section D: Property Lifecycle and History
-- =====================================================

-- 13. Property Status History
CREATE TABLE property_status_history (
    history_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    property_id BIGINT NOT NULL,
    old_status VARCHAR(30) NULL,
    new_status VARCHAR(30) NOT NULL,
    changed_by BIGINT NOT NULL,
    changed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    reason VARCHAR(500) NULL,

    CONSTRAINT fk_status_history_property
        FOREIGN KEY (property_id)
        REFERENCES properties(property_id)
        ON DELETE RESTRICT
        ON UPDATE RESTRICT,

    CONSTRAINT fk_status_history_user
        FOREIGN KEY (changed_by)
        REFERENCES users(user_id)
        ON DELETE RESTRICT
        ON UPDATE RESTRICT,

    CONSTRAINT chk_status_history_old_status
        CHECK (
            old_status IS NULL OR old_status IN (
                'AVAILABLE',
                'ASSIGNED',
                'UNDER_INSPECTION',
                'DAMAGED',
                'UNDER_REPAIR',
                'LOST',
                'RETIRED'
            )
        ),

    CONSTRAINT chk_status_history_new_status
        CHECK (new_status IN (
            'AVAILABLE',
            'ASSIGNED',
            'UNDER_INSPECTION',
            'DAMAGED',
            'UNDER_REPAIR',
            'LOST',
            'RETIRED'
        )),

    INDEX idx_status_history_property_id (property_id),
    INDEX idx_status_history_changed_by (changed_by),
    INDEX idx_status_history_changed_at (changed_at)
);

-- 14. Property Location History
CREATE TABLE property_location_history (
    location_history_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    property_id BIGINT NOT NULL,
    from_area_id BIGINT NULL,
    to_area_id BIGINT NOT NULL,
    moved_by BIGINT NOT NULL,
    moved_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    reason VARCHAR(500) NULL,

    CONSTRAINT fk_location_history_property
        FOREIGN KEY (property_id)
        REFERENCES properties(property_id)
        ON DELETE RESTRICT
        ON UPDATE RESTRICT,

    CONSTRAINT fk_location_history_from_area
        FOREIGN KEY (from_area_id)
        REFERENCES physical_areas(area_id)
        ON DELETE RESTRICT
        ON UPDATE RESTRICT,

    CONSTRAINT fk_location_history_to_area
        FOREIGN KEY (to_area_id)
        REFERENCES physical_areas(area_id)
        ON DELETE RESTRICT
        ON UPDATE RESTRICT,

    CONSTRAINT fk_location_history_user
        FOREIGN KEY (moved_by)
        REFERENCES users(user_id)
        ON DELETE RESTRICT
        ON UPDATE RESTRICT,

    INDEX idx_location_history_property_id (property_id),
    INDEX idx_location_history_from_area (from_area_id),
    INDEX idx_location_history_to_area (to_area_id),
    INDEX idx_location_history_moved_by (moved_by),
    INDEX idx_location_history_moved_at (moved_at)
);

-- 15. Property Incidents
CREATE TABLE property_incidents (
    incident_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    property_id BIGINT NOT NULL,
    reported_by BIGINT NOT NULL,
    incident_type VARCHAR(20) NOT NULL,
    description VARCHAR(1000) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    reviewed_by BIGINT NULL,
    reviewed_at DATETIME NULL,
    decision VARCHAR(500) NULL,
    action_taken VARCHAR(500) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_incidents_property
        FOREIGN KEY (property_id)
        REFERENCES properties(property_id)
        ON DELETE RESTRICT
        ON UPDATE RESTRICT,

    CONSTRAINT fk_incidents_reporter
        FOREIGN KEY (reported_by)
        REFERENCES users(user_id)
        ON DELETE RESTRICT
        ON UPDATE RESTRICT,

    CONSTRAINT fk_incidents_reviewer
        FOREIGN KEY (reviewed_by)
        REFERENCES users(user_id)
        ON DELETE RESTRICT
        ON UPDATE RESTRICT,

    CONSTRAINT chk_incidents_type
        CHECK (incident_type IN ('DAMAGE', 'BROKEN', 'LOST')),

    CONSTRAINT chk_incidents_status
        CHECK (status IN ('OPEN', 'APPROVED', 'REJECTED', 'RESOLVED')),

    INDEX idx_incidents_property_id (property_id),
    INDEX idx_incidents_reported_by (reported_by),
    INDEX idx_incidents_reviewed_by (reviewed_by),
    INDEX idx_incidents_status (status),
    INDEX idx_incidents_created_at (created_at)
);

-- =====================================================
-- Section E: Procurement
-- =====================================================

-- 16. Suppliers
CREATE TABLE suppliers (
    supplier_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(150) NOT NULL,
    contact_person VARCHAR(100) NULL,
    phone VARCHAR(30) NULL,
    email VARCHAR(150) NULL,
    address VARCHAR(500) NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP,

    INDEX idx_suppliers_name (name),
    INDEX idx_suppliers_is_active (is_active)
);

-- 17. Purchases
CREATE TABLE purchases (
    purchase_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    supplier_id BIGINT NOT NULL,
    purchase_name VARCHAR(200) NOT NULL,
    purchase_location VARCHAR(255) NULL,

    -- Actual procurement transaction date
    purchase_date DATE NOT NULL,

    reference_number VARCHAR(100) NULL,
    created_by BIGINT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_purchases_supplier
        FOREIGN KEY (supplier_id)
        REFERENCES suppliers(supplier_id)
        ON DELETE RESTRICT
        ON UPDATE RESTRICT,

    CONSTRAINT fk_purchases_created_by
        FOREIGN KEY (created_by)
        REFERENCES users(user_id)
        ON DELETE RESTRICT
        ON UPDATE RESTRICT,

    INDEX idx_purchases_supplier_id (supplier_id),
    INDEX idx_purchases_created_by (created_by),
    INDEX idx_purchases_purchase_date (purchase_date)
);

-- 18. Purchase Items
CREATE TABLE purchase_items (
    purchase_item_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    purchase_id BIGINT NOT NULL,
    property_id BIGINT NOT NULL,
    purchase_value DECIMAL(12,2) NOT NULL,

    CONSTRAINT fk_purchase_items_purchase
        FOREIGN KEY (purchase_id)
        REFERENCES purchases(purchase_id)
        ON DELETE RESTRICT
        ON UPDATE RESTRICT,

    CONSTRAINT fk_purchase_items_property
        FOREIGN KEY (property_id)
        REFERENCES properties(property_id)
        ON DELETE RESTRICT
        ON UPDATE RESTRICT,

    CONSTRAINT chk_purchase_items_purchase_value
        CHECK (purchase_value >= 0),

    -- A physical asset can have at most one original
    -- procurement record in this table.
    CONSTRAINT uq_purchase_items_property
        UNIQUE (property_id),

    INDEX idx_purchase_items_purchase_id (purchase_id)
);

-- =====================================================
-- Section F: Notifications and Audit Logs
-- =====================================================

-- 19. Notifications
CREATE TABLE notifications (
    notification_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    title VARCHAR(200) NOT NULL,
    message VARCHAR(1000) NOT NULL,
    notification_type VARCHAR(50) NOT NULL,
    entity_type VARCHAR(50) NULL,
    entity_id BIGINT NULL,
    is_read BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_notifications_user
        FOREIGN KEY (user_id)
        REFERENCES users(user_id)
        ON DELETE RESTRICT
        ON UPDATE RESTRICT,

    INDEX idx_notifications_user_id (user_id),
    INDEX idx_notifications_user_read (user_id, is_read),
    INDEX idx_notifications_created_at (created_at)
);

-- 20. Audit Logs
CREATE TABLE audit_logs (
    audit_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    action VARCHAR(100) NOT NULL,
    entity_type VARCHAR(50) NOT NULL,
    entity_id BIGINT NULL,
    `timestamp` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    details VARCHAR(2000) NULL,

    CONSTRAINT fk_audit_logs_user
        FOREIGN KEY (user_id)
        REFERENCES users(user_id)
        ON DELETE RESTRICT
        ON UPDATE RESTRICT,

    INDEX idx_audit_logs_user_id (user_id),
    INDEX idx_audit_logs_entity (entity_type, entity_id),
    INDEX idx_audit_logs_timestamp (`timestamp`),
    INDEX idx_audit_logs_action (action)
);


-- =====================================================
-- Section G: Initial Master Data
-- =====================================================

-- 21. Initial Roles
INSERT INTO roles (name, description) VALUES
('SYSTEM_ADMIN', 'System administration and Inventory Admin account management'),
('OFFICE_STAFF', 'Staff members who can use inventory request features'),
('INVENTORY_MANAGER', 'Manages inventory operations, assignments, returns, and reviews'),
('INVENTORY_ADMIN', 'Manages departments, physical areas, property categories, and user accounts')
AS new
ON DUPLICATE KEY UPDATE
    name = new.name;

-- 22. Initial Physical Areas
INSERT INTO physical_areas (name, description) VALUES
('Library', 'Library area'),
('Pantry 1', 'First pantry area'),
('Pantry 2', 'Second pantry area'),
('Admin Area', 'Administrative area'),
('Reception', 'Reception area'),
('HO Office', 'Head Office area'),
('Class Room', 'Classroom area'),
('Auditorium', 'Auditorium area'),
('Flight Admin', 'Flight Administration area'),
('Exterior', 'Exterior area')
AS new
ON DUPLICATE KEY UPDATE
    name = new.name;

-- 23. Initial Property Categories
INSERT INTO property_categories (name, description) VALUES
('General', 'Commonly used general properties such as tables and chairs'),
('IT', 'Commonly used IT properties and equipment'),
('Electronic', 'Commonly used electronic properties and equipment')
AS new
ON DUPLICATE KEY UPDATE
    name = new.name;
