package com.bssa.assetmanagement.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "property_request_items")
public class PropertyRequestItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "request_item_id")
    private Long requestItemId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "request_id", nullable = false)
    private PropertyRequest request;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "property_type_id", nullable = false)
    private PropertyType propertyType;

    @Column(name = "quantity", nullable = false)
    private Integer quantity;

    @Column(name = "notes", length = 255)
    private String notes;

    public PropertyRequestItem() {
    }

    public Long getRequestItemId() {
        return requestItemId;
    }

    public void setRequestItemId(Long requestItemId) {
        this.requestItemId = requestItemId;
    }

    public PropertyRequest getRequest() {
        return request;
    }

    public void setRequest(PropertyRequest request) {
        this.request = request;
    }

    public PropertyType getPropertyType() {
        return propertyType;
    }

    public void setPropertyType(PropertyType propertyType) {
        this.propertyType = propertyType;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public void setQuantity(Integer quantity) {
        this.quantity = quantity;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }
}
