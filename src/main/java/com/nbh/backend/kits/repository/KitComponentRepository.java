package com.nbh.backend.kits.repository;

import com.nbh.backend.kits.entity.KitComponent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface KitComponentRepository extends JpaRepository<KitComponent, UUID> {

    List<KitComponent> findByKitIdOrderByLineNoAsc(UUID kitId);

    /** Batched — avoids an N+1 when listing several kits at once (public list, admin list). */
    List<KitComponent> findByKitIdInOrderByLineNoAsc(Collection<UUID> kitIds);
}
