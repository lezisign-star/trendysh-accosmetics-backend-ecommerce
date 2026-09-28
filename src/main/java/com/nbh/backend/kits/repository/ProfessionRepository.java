package com.nbh.backend.kits.repository;

import com.nbh.backend.kits.entity.Profession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProfessionRepository extends JpaRepository<Profession, UUID> {

    Optional<Profession> findBySlug(String slug);

    boolean existsBySlug(String slug);

    List<Profession> findAllByOrderByLabelAsc();
}
