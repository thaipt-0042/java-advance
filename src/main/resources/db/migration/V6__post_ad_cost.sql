-- Advertising cost of a post, in USD (#15, docs/cau-hoi-lam-ro.md Q7): converted to VND with the rate of the SOAP
-- service when shown or exported, never stored converted. Optional.
ALTER TABLE posts
    ADD COLUMN ad_cost DECIMAL(15, 2) NULL AFTER published_at,
    ADD CONSTRAINT ck_posts_ad_cost CHECK (ad_cost IS NULL OR ad_cost >= 0);
