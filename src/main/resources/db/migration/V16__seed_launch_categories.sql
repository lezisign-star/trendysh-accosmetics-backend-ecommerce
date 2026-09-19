-- The six launch categories. The storefront maps these exact names to their
-- fallback photos. Idempotent: an existing slug (e.g. made by hand in the admin) is left alone.
INSERT INTO category (id, slug, name, sort_order, is_published) VALUES
    (gen_random_uuid(), 'nails',           'Nails',           1, true),
    (gen_random_uuid(), 'lashes',          'Lashes',          2, true),
    (gen_random_uuid(), 'make-up',         'Make up',         3, true),
    (gen_random_uuid(), 'brows',           'Brows',           4, true),
    (gen_random_uuid(), 'spa',             'Spa',             5, true),
    (gen_random_uuid(), 'salon-equipment', 'Salon Equipment', 6, true)
ON CONFLICT (slug) DO NOTHING;
