package com.nbh.backend.catalog.service;

import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Realistic product/profession seed content for {@link MockDataSeederService} — split into its own
 * class purely so the generator service isn't buried under a hundred lines of static data. Names,
 * price ranges and phrasing are grounded in this catalogue's own real products (seen live via the
 * public API throughout this project's build — e.g. "Chrome Mirror Gel Polish", "Acrylic Powder —
 * Crystal Clear 100g", "Lash Extension Glue — Fast Dry 1s", "Brow Lamination Kit — Full Size",
 * "Facial Steamer — Ozone"), not invented from scratch, so mock and real products read as one
 * catalogue rather than two different registers.
 */
final class CategoryTemplates {

    private CategoryTemplates() {}

    record ProductTemplate(String name, String description, int priceMin, int priceMax, String imageKeyword) {}

    record ProfessionSeed(String label, String slug) {}

    private static final List<String> REVIEWER_FIRST_NAMES = List.of(
            "Faith", "Grace", "Mercy", "Joy", "Sharon", "Winnie", "Brenda", "Esther", "Diana", "Caroline",
            "Njeri", "Wanjiru", "Achieng", "Amina", "Zawadi", "Mumbi", "Akinyi", "Nyambura", "Chebet", "Naliaka");
    private static final List<String> REVIEWER_LAST_INITIALS = List.of(
            "K.", "M.", "W.", "N.", "O.", "A.", "S.", "T.");
    private static final List<String> REVIEW_COMMENTS = List.of(
            "Exactly what my salon needed, great quality for the price.",
            "Fast delivery and the product is genuine. Will reorder.",
            "My clients love the results. Restocking every month now.",
            "Good value for money, works as expected.",
            "Quality is solid but delivery took a bit longer than I hoped.",
            "One of the better suppliers I've used in Nairobi — consistent stock.",
            "Does the job well, packaging was neat and professional.",
            "Bought this for my shop, clients keep asking where I got it.",
            "Decent quality, will compare with other brands next time.",
            "Impressed — this has become a staple in my kit.");

    static String randomReviewerName(Random random) {
        return REVIEWER_FIRST_NAMES.get(random.nextInt(REVIEWER_FIRST_NAMES.size())) + " "
                + REVIEWER_LAST_INITIALS.get(random.nextInt(REVIEWER_LAST_INITIALS.size()));
    }

    static String randomReviewComment(Random random) {
        return REVIEW_COMMENTS.get(random.nextInt(REVIEW_COMMENTS.size()));
    }

    static ProfessionSeed professionFor(String categorySlug) {
        return PROFESSIONS.get(categorySlug);
    }

    static List<ProductTemplate> templatesFor(String categorySlug) {
        return TEMPLATES.getOrDefault(categorySlug, List.of());
    }

    private static final Map<String, ProfessionSeed> PROFESSIONS = Map.of(
            "nails", new ProfessionSeed("Nail Technician", "nail-technician"),
            "lashes", new ProfessionSeed("Lash Technician", "lash-technician"),
            "brows", new ProfessionSeed("Brow Specialist", "brow-specialist"),
            "make-up", new ProfessionSeed("Makeup Artist", "makeup-artist"),
            "spa", new ProfessionSeed("Spa Therapist", "spa-therapist")
            // salon-equipment intentionally has no natural profession — MockDataSeederService
            // already skips kit creation for a category with a null ProfessionSeed.
    );

    private static final Map<String, List<ProductTemplate>> TEMPLATES = Map.of(
            "nails", List.of(
                    new ProductTemplate("Chrome Mirror Gel Polish — Silver", "High-shine chrome finish gel polish, cures under UV/LED lamp, long-lasting wear.", 1100, 1400, "chrome nail polish"),
                    new ProductTemplate("Chrome Mirror Gel Polish — Rose Gold", "High-shine chrome finish gel polish, cures under UV/LED lamp, long-lasting wear.", 1100, 1400, "rose gold nail polish"),
                    new ProductTemplate("Acrylic Powder — Crystal Clear 100g", "Professional-grade acrylic powder for sculpting and overlays, smooth self-leveling finish.", 900, 1300, "acrylic nail powder"),
                    new ProductTemplate("Acrylic Powder — Natural Pink 100g", "Professional-grade acrylic powder for sculpting and overlays, smooth self-leveling finish.", 900, 1300, "pink acrylic powder"),
                    new ProductTemplate("Builder Gel — Clear 15ml", "Self-leveling builder gel for nail extensions and structural strength.", 750, 1050, "builder gel nails"),
                    new ProductTemplate("Nail Drill Machine — Portable 20000RPM", "Rechargeable portable nail drill with variable speed control for prep and removal.", 3800, 5200, "nail drill machine"),
                    new ProductTemplate("Nail Drill Machine — Desktop 35000RPM", "Professional desktop nail drill, low-vibration motor, salon-grade durability.", 4500, 6200, "nail drill desktop"),
                    new ProductTemplate("UV LED Nail Lamp 48W", "Fast-cure UV/LED lamp with auto-sensor and timer settings for gel and builder gel.", 2800, 3600, "uv nail lamp"),
                    new ProductTemplate("Cuticle Oil Pen — Lavender", "Nourishing cuticle oil in a precision pen applicator, absorbs quickly.", 300, 420, "cuticle oil"),
                    new ProductTemplate("Nail Art Brush Kit — 12 Piece", "Assorted detail brushes for line work, ombre and nail art design.", 550, 780, "nail art brushes"),
                    new ProductTemplate("Rhinestone Kit — Mixed Sizes", "Assorted crystal rhinestones for nail art embellishment, mixed sizes and colours.", 450, 650, "nail rhinestones"),
                    new ProductTemplate("Nail Tips — Clear Full Cover 500pc", "Full-cover clear nail tips, box of 500 in assorted sizes for extensions.", 600, 850, "nail tips"),
                    new ProductTemplate("Base & Top Coat Duo", "Long-wear base and top coat set for extended manicure durability.", 600, 850, "nail top coat"),
                    new ProductTemplate("Nail Buffer Block — 4 Way", "Four-sided buffing block for shaping, smoothing and shine finishing.", 200, 320, "nail buffer"),
                    new ProductTemplate("Gel Polish Remover — 500ml", "Fast-acting gel polish remover, non-drying formula for salon use.", 550, 750, "nail polish remover"),
                    new ProductTemplate("Polygel Kit — Starter Set", "Complete polygel system with dual-form tips and application tools.", 2200, 3200, "polygel nails"),
                    new ProductTemplate("French Tip Guide Stickers — 24 Sheets", "Adhesive guide stickers for precise French manicure application.", 350, 500, "french manicure"),
                    new ProductTemplate("Nail Dust Collector — Desktop", "Quiet desktop dust collector with washable filter for cleaner filing and drilling.", 3200, 4200, "nail dust collector"),
                    new ProductTemplate("Cat Eye Gel Polish — Magnetic Set", "Magnetic cat eye gel polish with wand, creates a shimmering 3D line effect.", 1300, 1700, "cat eye nails"),
                    new ProductTemplate("Nail Form Stickers — Dual Form 100pc", "Reusable-shape dual forms for sculpting acrylic and polygel extensions.", 500, 720, "nail forms"),
                    new ProductTemplate("Manicure Tool Set — Stainless Steel 8 Piece", "Cuticle pusher, nipper and file set in a travel case, salon-grade steel.", 900, 1300, "manicure tools")
            ),
            "lashes", List.of(
                    new ProductTemplate("Lash Extension Glue — Fast Dry 1s", "Professional-grade lash adhesive, 1-second dry time, strong retention.", 1300, 1700, "lash extension glue"),
                    new ProductTemplate("Lash Extension Glue — Sensitive 3s", "Low-fume formula for sensitive clients, 3-second dry time.", 1400, 1800, "eyelash glue"),
                    new ProductTemplate("Classic Lash Trays — 0.15mm D Curl", "Premium synthetic lash tray, D curl, mixed lengths for classic sets.", 500, 700, "eyelash extensions tray"),
                    new ProductTemplate("Volume Lash Fan Tweezers — Curved", "Precision curved tweezers for volume fan isolation and pickup.", 900, 1300, "lash tweezers"),
                    new ProductTemplate("Volume Lash Fan Tweezers — Straight", "Precision straight tweezers for classic lash application.", 900, 1300, "eyelash tweezers"),
                    new ProductTemplate("Lash Lift Kit — Salon Size", "Complete lash lift and tint kit for salon-scale use, multiple treatments per kit.", 2500, 3200, "lash lift kit"),
                    new ProductTemplate("Lash Shampoo Foam Cleanser", "Gentle foaming cleanser safe for lash extensions, oil-free formula.", 750, 1000, "lash cleanser"),
                    new ProductTemplate("Mink Strip Lashes — Wispy", "Reusable wispy strip lashes, natural mink-style finish.", 550, 750, "strip lashes"),
                    new ProductTemplate("Mink Strip Lashes — Dramatic", "Reusable dramatic volume strip lashes for special occasions.", 550, 750, "false eyelashes"),
                    new ProductTemplate("Individual Lash Clusters — 10mm", "Pre-fanned individual lash clusters for quick natural application.", 400, 550, "lash clusters"),
                    new ProductTemplate("Waterproof Eyeliner Pen — Black", "Precision felt-tip eyeliner, waterproof and smudge-resistant.", 300, 450, "waterproof eyeliner"),
                    new ProductTemplate("Under Eye Gel Pads — 100 Pairs", "Hydrogel under-eye pads for lash and brow treatments, box of 100.", 600, 850, "under eye pads"),
                    new ProductTemplate("Lash Extension Primer", "Pre-treatment primer to remove oils and improve adhesive bonding.", 550, 750, "lash primer"),
                    new ProductTemplate("Lash Adhesive Remover — Gel 15g", "Gentle gel remover for safe, quick lash extension removal.", 650, 900, "lash remover"),
                    new ProductTemplate("Lash Mapping Stickers — 100 Pack", "Adhesive mapping stickers for consistent lash length placement.", 300, 450, "lash mapping"),
                    new ProductTemplate("Lash Sealant & Coating — 10ml", "Seals fresh extensions and extends retention between refills.", 700, 950, "lash coating"),
                    new ProductTemplate("Lash Cleansing Brush Set — 50pc", "Disposable mascara wands and micro brushes for lash prep and aftercare.", 350, 520, "lash brush"),
                    new ProductTemplate("Lash Tile & Pallet Set", "Ceramic glue pallet and lash tile for adhesive control during application.", 800, 1150, "lash pallet")
            ),
            "brows", List.of(
                    new ProductTemplate("Brow Lamination Kit — Full Size", "Complete brow lamination system for salon use, multiple treatments.", 2000, 2600, "brow lamination kit"),
                    new ProductTemplate("Henna Brow Tint — Medium Brown", "Semi-permanent henna brow tint, natural-looking, long-lasting colour.", 700, 950, "henna eyebrow tint"),
                    new ProductTemplate("Henna Brow Tint — Black", "Semi-permanent henna brow tint for bold definition.", 700, 950, "eyebrow henna"),
                    new ProductTemplate("Tinted Brow Gel — Clear", "Setting gel for brow lamination and everyday grooming.", 400, 550, "brow gel"),
                    new ProductTemplate("Tinted Brow Gel — Ash Brown", "Tinted setting gel for fuller-looking, defined brows.", 450, 600, "tinted brow gel"),
                    new ProductTemplate("Microblading Pigment Set — 12 Colors", "Professional microblading pigment set, highly pigmented, long-lasting.", 3000, 3900, "microblading pigment"),
                    new ProductTemplate("Microblading Pen Set — Fine Tip", "Disposable microblading pens with fine-tip precision blades.", 1500, 1900, "microblading tool"),
                    new ProductTemplate("Brow Stencil Kit", "Reusable brow shaping stencils in assorted natural shapes.", 450, 650, "eyebrow stencil"),
                    new ProductTemplate("Brow Razor 3-Pack", "Precision dermaplaning razors for brow shaping and dermaplaning.", 250, 380, "brow razor"),
                    new ProductTemplate("Precision Slant Tweezers — Gold", "Stainless steel slant tweezers for precise brow shaping.", 550, 750, "eyebrow tweezers"),
                    new ProductTemplate("Brow Mapping String — Pre-Inked", "Pre-inked mapping thread for symmetrical brow measurement.", 400, 580, "brow mapping"),
                    new ProductTemplate("Brow Pencil — Waterproof Dual End", "Waterproof pencil with spoolie, fine tip for hair-stroke definition.", 350, 520, "eyebrow pencil"),
                    new ProductTemplate("Brow Wax Strips — 60 Count", "Pre-cut wax strips for quick brow cleanup, suits sensitive skin.", 450, 650, "brow wax"),
                    new ProductTemplate("Brow Soap Styler — Laminated Look", "Clear styling soap for feathered, lifted brows with all-day hold.", 500, 720, "brow soap"),
                    new ProductTemplate("Brow Aftercare Serum — 10ml", "Nourishing peptide serum to support brow regrowth and recovery after treatments.", 900, 1250, "eyebrow serum"),
                    new ProductTemplate("Microblading Practice Skin — 5 Pack", "Latex practice skins for training strokes before working on clients.", 700, 1000, "microblading practice")
            ),
            "make-up", List.of(
                    new ProductTemplate("Eyeshadow Palette — Neutrals", "18-shade neutral eyeshadow palette, blendable matte and shimmer finishes.", 1400, 1900, "eyeshadow palette"),
                    new ProductTemplate("HD Foundation — Fair", "Full-coverage HD foundation, long-wear, suits fair skin tones.", 1200, 1600, "hd foundation makeup"),
                    new ProductTemplate("HD Foundation — Medium", "Full-coverage HD foundation, long-wear, suits medium skin tones.", 1200, 1600, "foundation makeup"),
                    new ProductTemplate("HD Foundation — Deep", "Full-coverage HD foundation, long-wear, suits deep skin tones.", 1200, 1600, "makeup foundation"),
                    new ProductTemplate("Matte Liquid Lipstick Set — 6 Shades", "Long-wear matte liquid lipstick set, transfer-resistant formula.", 1600, 2100, "liquid lipstick set"),
                    new ProductTemplate("Makeup Brush Set — 12 Piece", "Professional synthetic bristle brush set with case, full face coverage.", 1700, 2300, "makeup brush set"),
                    new ProductTemplate("Beauty Blender Sponge — Twin Pack", "Latex-free blending sponges for seamless foundation application.", 380, 520, "beauty sponge"),
                    new ProductTemplate("Setting Spray — Matte Finish", "Long-lasting matte setting spray, locks makeup in place all day.", 800, 1050, "makeup setting spray"),
                    new ProductTemplate("Highlighter Compact — Rose Gold Glow", "Finely-milled pressed highlighter for a natural luminous glow.", 700, 950, "highlighter makeup"),
                    new ProductTemplate("Contour & Bronzer Duo", "Two-tone contour and bronzer compact for sculpting and warmth.", 950, 1300, "contour palette"),
                    new ProductTemplate("Press-On Nail Set — Almond Medium", "Reusable press-on nails, salon-quality finish, easy application.", 1100, 1500, "press on nails"),
                    new ProductTemplate("Ring Light with Tripod — 10 inch", "Adjustable LED ring light with phone holder, ideal for makeup application video.", 2200, 2800, "ring light tripod"),
                    new ProductTemplate("Loose Setting Powder — Translucent 30g", "Finely-milled translucent powder for a soft-focus, long-wear finish.", 900, 1250, "setting powder"),
                    new ProductTemplate("Primer — Pore Filling 30ml", "Silky pore-filling primer that smooths skin and extends foundation wear.", 1100, 1500, "makeup primer"),
                    new ProductTemplate("Concealer Palette — Correcting 6 Shades", "Cream concealer palette with colour correctors for every skin tone.", 1300, 1750, "concealer palette"),
                    new ProductTemplate("Blush Trio — Soft Peach", "Three buildable blush shades in a slim compact, natural flush.", 850, 1150, "blush makeup"),
                    new ProductTemplate("Makeup Remover Balm — 100g", "Melting cleansing balm that removes long-wear makeup without stripping skin.", 1000, 1400, "makeup remover"),
                    new ProductTemplate("Eyeliner Gel Pot with Brush — Black", "Smudge-proof gel eyeliner pot with angled brush for precise wings.", 650, 900, "gel eyeliner")
            ),
            "spa", List.of(
                    new ProductTemplate("Facial Steamer — Ozone", "Professional ozone facial steamer for deep pore cleansing treatments.", 6000, 7200, "facial steamer"),
                    new ProductTemplate("Facial Bed — Electric", "Electric adjustable facial and massage bed, salon-grade upholstery.", 20000, 24000, "spa facial bed"),
                    new ProductTemplate("Hot Stone Massage Set", "Basalt hot stone set with heater for therapeutic massage treatments.", 3800, 4600, "hot stone massage"),
                    new ProductTemplate("Aromatherapy Diffuser", "Ultrasonic essential oil diffuser with ambient LED lighting.", 2300, 2900, "aromatherapy diffuser"),
                    new ProductTemplate("Clay Mask Jar — Green Tea", "Purifying green tea clay mask for oily and combination skin.", 950, 1250, "clay face mask"),
                    new ProductTemplate("Clay Mask Jar — Charcoal", "Detoxifying charcoal clay mask for deep pore cleansing.", 850, 1150, "charcoal face mask"),
                    new ProductTemplate("Body Scrub — Coffee & Coconut — 500g", "Exfoliating body scrub with coffee grounds and coconut oil.", 800, 1050, "body scrub"),
                    new ProductTemplate("Jade Roller & Gua Sha Set — Rose Quartz", "Facial massage tool set for lymphatic drainage and skin tightening.", 1000, 1350, "jade roller gua sha"),
                    new ProductTemplate("Foot Spa Basin — Massage Jets", "Electric foot spa with massage jets and heat function.", 5200, 6400, "foot spa basin"),
                    new ProductTemplate("Paraffin Wax — Lavender 500g", "Therapeutic paraffin wax for hand and foot spa treatments.", 1200, 1550, "paraffin wax spa"),
                    new ProductTemplate("Massage Oil — Sweet Almond 500ml", "Cold-pressed sweet almond massage oil, unscented, suits all skin types.", 1100, 1500, "massage oil"),
                    new ProductTemplate("Essential Oil Set — 6 Bottles", "Lavender, eucalyptus, peppermint, tea tree, lemon and rose oils for spa use.", 1800, 2400, "essential oils"),
                    new ProductTemplate("Sheet Mask Box — Hydrating 20 Pack", "Individually-sealed hydrating sheet masks for quick facial treatments.", 1300, 1750, "sheet mask"),
                    new ProductTemplate("Heated Towel Steamer Cabinet", "Compact steamer cabinet that keeps treatment towels warm and ready.", 5800, 7000, "towel warmer spa"),
                    new ProductTemplate("Bath Salts — Eucalyptus 1kg", "Mineral-rich bath salts with eucalyptus for relaxing foot and body soaks.", 700, 1000, "bath salts"),
                    new ProductTemplate("Spa Headband & Wrist Set — 12 Pack", "Soft terry headbands and wrist bands to keep clients tidy during facials.", 650, 950, "spa headband")
            ),
            "salon-equipment", List.of(
                    new ProductTemplate("Adjustable Salon Stool — Hydraulic — Black", "Hydraulic height-adjustable salon stool with 360-degree swivel.", 5300, 6200, "salon stool"),
                    new ProductTemplate("Adjustable Salon Stool — Hydraulic — White", "Hydraulic height-adjustable salon stool with 360-degree swivel.", 5900, 6800, "hydraulic stool"),
                    new ProductTemplate("Wall Mirror — LED Frame", "Full-length salon mirror with dimmable LED frame lighting.", 7800, 9200, "salon mirror led"),
                    new ProductTemplate("Manicure Table — Ventilated", "Ventilated manicure table with built-in dust extraction fan.", 11800, 13200, "manicure table"),
                    new ProductTemplate("Sterilizer Cabinet UV", "UV sterilizer cabinet for salon tool sanitation, timer-controlled.", 6200, 7400, "uv sterilizer cabinet"),
                    new ProductTemplate("Salon Trolley Cart", "Multi-tier rolling trolley cart for tool and product organisation.", 3900, 4700, "salon trolley cart"),
                    new ProductTemplate("Disposable Salon Capes — 50 Pack", "Waterproof disposable capes for client protection during services.", 1800, 2300, "salon cape"),
                    new ProductTemplate("Shampoo Basin Chair — Ceramic", "Ceramic shampoo bowl with adjustable neck rest and hot/cold mixer.", 24000, 29000, "shampoo basin salon"),
                    new ProductTemplate("Styling Chair — Reclining Classic", "Heavy-duty reclining styling chair with footrest and padded armrests.", 14000, 17500, "salon styling chair"),
                    new ProductTemplate("Hair Dryer Stand — Hooded", "Hooded hair dryer with adjustable timer and temperature settings.", 8500, 10500, "hooded hair dryer"),
                    new ProductTemplate("Pedicure Chair — Massage Spa", "Full-body massage pedicure chair with integrated footbath and controls.", 32000, 38000, "pedicure chair"),
                    new ProductTemplate("Reception Desk — Compact White", "Compact white reception desk with lockable drawer and cable port.", 15000, 19000, "reception desk salon"),
                    new ProductTemplate("Towel Steriliser & Warmer", "Countertop towel steriliser and warmer, keeps towels hot and hygienic.", 4800, 6000, "towel warmer"),
                    new ProductTemplate("Waiting Area Sofa — 2 Seater", "Two-seater upholstered waiting area sofa, wipe-clean finish.", 9500, 12000, "salon waiting sofa"),
                    new ProductTemplate("Wall Shelf Unit — Product Display", "Wall-mounted display shelving for retail products and tools.", 4200, 5600, "salon shelf display")
            )
    );
}
