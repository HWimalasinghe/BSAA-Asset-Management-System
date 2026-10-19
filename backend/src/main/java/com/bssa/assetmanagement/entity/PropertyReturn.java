
package com.bssa.assetmanagement.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "property_returns")
public class PropertyReturn {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "return_id")
    private Long returnId;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "assignment_id", nullable = false, unique = true)
    private PropertyAssignment assignment;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "returned_by", nullable = false)
    private AppUser returnedBy;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "received_by", nullable = false)
    private AppUser receivedBy;

    @Column(name = "returned_at", insertable = false, updatable = false)
    private LocalDateTime returnedAt;

    @Column(name = "`condition`", nullable = false, length = 20)
    private String condition;

    @Column(name = "notes", length = 500)
    private String notes;

    public PropertyReturn() {
    }

    public Long getReturnId() {
        return returnId;
    }

    public PropertyAssignment getAssignment() {
        return assignment;
    }

    public void setAssignment(PropertyAssignment assignment) {
        this.assignment = assignment;
    }

    public AppUser getReturnedBy() {
        return returnedBy;
    }

    public void setReturnedBy(AppUser returnedBy) {
        this.returnedBy = returnedBy;
    }

    public AppUser getReceivedBy() {
        return receivedBy;
    }

    public void setReceivedBy(AppUser receivedBy) {
        this.receivedBy = receivedBy;
    }

    public LocalDateTime getReturnedAt() {
        return returnedAt;
    }

    public String getCondition() {
        return condition;
    }

    public void setCondition(String condition) {
        this.condition = condition;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }
}
