-- Advanced look for builder banners (owner, 2026-10-31): staff can change the words' colours, size, alignment and font,
-- the shade behind them, the background colour and the button's colours. Kept as one small JSON object (see
-- PromoBannerService.Style), so a new option needs no new column; none = the banner's automatic look.
ALTER TABLE promo_banner ADD COLUMN IF NOT EXISTS style TEXT;
