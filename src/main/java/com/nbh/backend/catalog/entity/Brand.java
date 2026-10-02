package com.nbh.backend.catalog.entity;

import com.nbh.backend.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@jakarta.persistence.EntityListeners(com.nbh.backend.live.EntityChangeListener.class)
@Entity
@Table(name = "brand")
public class Brand extends BaseEntity {

    @Column(nullable = false, unique = true, length = 120)
    private String slug;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(length = 500)
    private String logoUrl;
}
