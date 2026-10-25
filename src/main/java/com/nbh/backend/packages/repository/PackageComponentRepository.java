package com.nbh.backend.packages.repository;

import com.nbh.backend.packages.entity.PackageComponent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface PackageComponentRepository extends JpaRepository<PackageComponent, UUID> {

    List<PackageComponent> findByProductPackageIdOrderByLineNoAsc(UUID packageId);

    /** Batched — avoids an N+1 when listing several packages at once (public list, admin list). */
    List<PackageComponent> findByProductPackageIdInOrderByLineNoAsc(Collection<UUID> packageIds);
}
