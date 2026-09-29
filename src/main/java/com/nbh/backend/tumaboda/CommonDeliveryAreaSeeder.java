package com.nbh.backend.tumaboda;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Seeds the autocomplete fallback list — see {@link CommonDeliveryArea}'s javadoc for why. Coordinates
 * are approximate estate-centre values from general knowledge, not survey-grade — good enough to get a
 * quote/rider roughly to the right neighbourhood, not for precise pin accuracy. Nairobi-metro only,
 * matching TumaBoda's stated coverage on the storefront's own /delivery page; adapted from Moments
 * Packaging's own seed list (which also covers Thika/Ruiru/Juja — outside what Trendysh ships by
 * TumaBoda). Replace/retire once TumaBoda provides real coverage-area data.
 */
@Slf4j
@Component
@Order(50)
public class CommonDeliveryAreaSeeder implements ApplicationRunner {

    private final CommonDeliveryAreaRepository repository;

    public CommonDeliveryAreaSeeder(CommonDeliveryAreaRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (repository.count() > 0) {
            log.info("Common delivery areas already seeded, skipping.");
            return;
        }

        List<CommonDeliveryArea> areas = List.of(
                area("Nairobi CBD", "Nairobi", -1.2864, 36.8172),
                area("Westlands", "Nairobi", -1.2673, 36.8062),
                area("Parklands", "Nairobi", -1.2610, 36.8151),
                area("Highridge", "Nairobi", -1.2600, 36.8100),
                area("Muthaiga", "Nairobi", -1.2500, 36.8167),
                area("Runda", "Nairobi", -1.2167, 36.8000),
                area("Gigiri", "Nairobi", -1.2333, 36.8000),
                area("Kilimani", "Nairobi", -1.2921, 36.7820),
                area("Kileleshwa", "Nairobi", -1.2793, 36.7793),
                area("Lavington", "Nairobi", -1.2762, 36.7666),
                area("Hurlingham", "Nairobi", -1.2950, 36.7900),
                area("Kawangware", "Nairobi", -1.2998, 36.7502),
                area("Kangemi", "Nairobi", -1.2661, 36.7382),
                area("Karen", "Nairobi", -1.3197, 36.7076),
                area("Langata", "Nairobi", -1.3667, 36.7500),
                area("Ngong Road", "Nairobi", -1.3008, 36.7830),
                area("Kibra", "Nairobi", -1.3133, 36.7833),
                area("Upper Hill", "Nairobi", -1.2963, 36.8172),
                area("South B", "Nairobi", -1.3167, 36.8333),
                area("South C", "Nairobi", -1.3200, 36.8180),
                area("Industrial Area", "Nairobi", -1.3056, 36.8508),
                area("Eastleigh", "Nairobi", -1.2762, 36.8461),
                area("Ngara", "Nairobi", -1.2764, 36.8267),
                area("Pangani", "Nairobi", -1.2667, 36.8333),
                area("Buruburu", "Nairobi", -1.2833, 36.8833),
                area("Donholm", "Nairobi", -1.2942, 36.8869),
                area("Umoja", "Nairobi", -1.2833, 36.9000),
                area("Embakasi", "Nairobi", -1.3197, 36.8944),
                area("Ruaraka", "Nairobi", -1.2333, 36.8667),
                area("Kasarani", "Nairobi", -1.2231, 36.8968),
                area("Roysambu", "Nairobi", -1.2194, 36.8901),
                area("Zimmerman", "Nairobi", -1.2100, 36.8900),
                area("Githurai", "Nairobi", -1.1833, 36.9000),
                area("Ruaka", "Kiambu", -1.2039, 36.7797)
        );

        repository.saveAll(areas);
        log.info("Seeded {} common delivery areas for TumaBoda address search fallback.", areas.size());
    }

    private static CommonDeliveryArea area(String name, String county, double lat, double lng) {
        CommonDeliveryArea a = new CommonDeliveryArea();
        a.setName(name);
        a.setCounty(county);
        a.setLatitude(lat);
        a.setLongitude(lng);
        a.setActive(true);
        return a;
    }
}
