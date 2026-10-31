package com.nbh.backend.banners;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PromoBannerPictureRepository extends JpaRepository<PromoBannerPicture, UUID> {

    List<PromoBannerPicture> findByBannerIdOrderByCreatedAtDesc(UUID bannerId);
}
