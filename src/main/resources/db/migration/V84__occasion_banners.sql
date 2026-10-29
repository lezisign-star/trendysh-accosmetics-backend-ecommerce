-- Occasion banners (owner, 29 Oct 2026): the shop's moments (weekend, payday, Black November, December festive) and the
-- promotional pictures Gemini makes for them, which staff pick before they show. See OccasionService, BannerService.
CREATE TABLE IF NOT EXISTS occasion (
    id UUID PRIMARY KEY,
    -- A stable name for the code and the prompts ("black-november"); staff-added ones get one from their name.
    code VARCHAR(60) NOT NULL UNIQUE,
    name VARCHAR(80) NOT NULL,
    -- WEEKLY: from start_dow/start_time to end_dow/end_time each week (1 = Monday). MONTHLY: from start_day to end_day
    -- each month, wrapping past the month's end. YEARLY: from start_md to end_md ("11-01") each year. DATES: from
    -- starts_on to ends_on, once.
    kind VARCHAR(10) NOT NULL,
    start_dow INT,
    start_time TIME,
    end_dow INT,
    end_time TIME,
    start_day INT,
    end_day INT,
    start_md VARCHAR(5),
    end_md VARCHAR(5),
    starts_on DATE,
    ends_on DATE,
    -- When several are on, the highest wins a slot (Black November over payday over the weekend).
    priority INT NOT NULL DEFAULT 10,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    -- What Gemini is told about the moment, and the line shown when there is no offer to name.
    tone TEXT NOT NULL,
    mood_line VARCHAR(120) NOT NULL,
    -- The occasion's colours for the strip and the banner frames: "#RRGGBB,#RRGGBB".
    colors VARCHAR(40) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO occasion (id, code, name, kind, start_dow, start_time, end_dow, end_time, priority, tone, mood_line, colors)
VALUES ('7a1f0c5e-0b61-4b0c-9a51-2f6f0f1a0001', 'weekend', 'Weekend', 'WEEKLY', 5, '16:00', 7, '23:59', 10,
        'The weekend: relaxed, a treat, pampering at home or a fresh set at the salon.',
        'Weekend iko sawa: treat yourself', '#C2185B,#FFF1F5')
ON CONFLICT (code) DO NOTHING;
INSERT INTO occasion (id, code, name, kind, start_day, end_day, priority, tone, mood_line, colors)
VALUES ('7a1f0c5e-0b61-4b0c-9a51-2f6f0f1a0002', 'payday', 'Payday', 'MONTHLY', 25, 2, 20,
        'Payday at the month''s end: salons and beauty pros restock, value and buying in bulk.',
        'Payday? Restock time', '#0F7A4F,#EAF7F0')
ON CONFLICT (code) DO NOTHING;
INSERT INTO occasion (id, code, name, kind, start_md, end_md, priority, tone, mood_line, colors)
VALUES ('7a1f0c5e-0b61-4b0c-9a51-2f6f0f1a0003', 'black-november', 'Black November', 'YEARLY', '11-01', '11-30', 40,
        'Black November, the month-long Black Friday sale (Black Friday itself is the last Friday): black and gold, '
            || 'excitement, the best prices of the year.',
        'The best prices of the year', '#111111,#D4A017')
ON CONFLICT (code) DO NOTHING;
INSERT INTO occasion (id, code, name, kind, start_md, end_md, priority, tone, mood_line, colors)
VALUES ('7a1f0c5e-0b61-4b0c-9a51-2f6f0f1a0004', 'december-festive', 'Festive December', 'YEARLY', '12-01', '12-31', 40,
        'December in Kenya: weddings, Jamhuri Day (12th), Christmas, New Year; celebration, family, gifts, looking '
            || 'your best for every party.',
        'Get salon-ready for December', '#B71C1C,#E8F5E9')
ON CONFLICT (code) DO NOTHING;

CREATE TABLE IF NOT EXISTS banner (
    id UUID PRIMARY KEY,
    occasion_id UUID NOT NULL REFERENCES occasion(id),
    -- OFFER_CARD: the first card of "On offer today". GRID_TILE: one in sixteen in a product grid. CIRCLE: a trade
    -- circle (category_id set).
    slot VARCHAR(12) NOT NULL,
    category_id UUID REFERENCES category(id),
    -- FAMILY, HAPPY_PEOPLE, CARTOON_JOKE, ANIMAL, PRODUCT_OFFER, GREETING.
    style VARCHAR(16) NOT NULL,
    -- The container the fit check chose: card-words-top, card-words-bottom, card-words-over, circle.
    container VARCHAR(20),
    image_url TEXT,
    -- The one line Gemini wrote (staff may change it); offer words are added by the site from live data.
    line VARCHAR(90),
    -- Light or dark words, from the brightness of the word area.
    text_tone VARCHAR(5),
    -- The fit check's answer (where the calm area is, how busy, colours, mood), as JSON.
    fit TEXT,
    -- WAITING for staff, PICKED (shows during its occasion), REJECTED, RETIRED, FAILED.
    status VARCHAR(10) NOT NULL,
    note TEXT,
    -- The offers it was made for (which products were on deal); a new set is made when this changes.
    offer_signature VARCHAR(200) NOT NULL DEFAULT '',
    decided_by VARCHAR(200),
    decided_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ix_banner_slot ON banner (occasion_id, slot, category_id, status);
CREATE INDEX IF NOT EXISTS ix_banner_status ON banner (status, created_at DESC);
