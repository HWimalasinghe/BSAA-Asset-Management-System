
package com.bssa.assetmanagement.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "property_assignments")
public class PropertyAssignment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "assignment_id")
    private Long assignmentId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "property_id", nullable = false)
    private Property property;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_id", nullable = false)
    private AppUser employee;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "issued_by", nullable = false)
    private AppUser issuedBy;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "area_id", nullable = false)
    private PhysicalArea area;

    @Column(name = "issued_at", insertable = false, updatable = false)
    private LocalDateTime issuedAt;

    @Column(name = "condition_at_issue", nullable = false, length = 20)
    private String conditionAtIssue;

    @Column(name = "notes", length = 500)
    private String notes;

    public PropertyAssignment() {
    }

    public Long getAssignmentId() {
        return assignmentId;
    }

    public Property getProperty() {
        return property;
    }

    public void setProperty(Property property) {
        this.property = property;
    }

    public AppUser getEmployee() {
        return employee;
    }

    public void setEmployee(AppUser employee) {
        this.employee = employee;
    }

    public AppUser getIssuedBy() {
        return issuedBy;
    }

    public void setIssuedBy(AppUser issuedBy) {
        this.issuedBy = issuedBy;
    }

    public PhysicalArea getArea() {
        return area;
    }

    public void setArea(PhysicalArea area) {
        this.area = area;
    }

    public LocalDateTime getIssuedAt() {
        return issuedAt;
    }

    public String getConditionAtIssue() {
        return conditionAtIssue;
    }

    public void setConditionAtIssue(String conditionAtIssue) {
        this.conditionAtIssue = conditionAtIssue;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }
}
