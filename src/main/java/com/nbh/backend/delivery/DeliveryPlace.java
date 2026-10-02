package com.nbh.backend.delivery;

import com.nbh.backend.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@Entity
@Table(name = "delivery_place")
public class DeliveryPlace extends BaseEntity {

    @Column(nullable = false, length = 150)
    private String name;

    @Column(length = 100)
    private String region;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(nullable = false)
    private boolean active = true;

    @OneToMany(mappedBy = "place", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<DeliveryPlacePrice> prices = new ArrayList<>();
}
