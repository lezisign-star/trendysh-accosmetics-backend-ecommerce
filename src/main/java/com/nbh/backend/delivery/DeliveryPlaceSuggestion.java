package com.nbh.backend.delivery;

import com.nbh.backend.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "delivery_place_suggestion")
public class DeliveryPlaceSuggestion extends BaseEntity {

    public enum Status { PENDING, APPROVED, REJECTED }

    @Column(nullable = false, length = 150)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String details;

    @Column(length = 100)
    private String contact;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Status status = Status.PENDING;

    @Column(name = "resolved_note", length = 300)
    private String resolvedNote;

    @Column(name = "place_id")
    private UUID placeId;
}
