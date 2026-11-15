-- =====================================================================
-- Reference data: blood compatibility matrix + two test banks + one hospital.
-- Users are NOT seeded here because their passwords must be BCrypt-hashed
-- by the application (see auth module DevDataSeeder).
-- =====================================================================

-- Rules are derived instead of hand-typed so they cannot contain typos.
-- RED_CELLS / WHOLE_BLOOD: donor red-cell antigens must be a subset of the recipient's,
--   and an Rh-negative recipient may only receive Rh-negative cells.
-- PLASMA / PLATELETS: donor plasma must not carry antibodies against recipient antigens
--   (reverse ABO rule). Rh is ignored for plasma (simplification, documented in decisions.md).
INSERT INTO blood_compatibility (recipient_group, donor_group, component_type)
SELECT r.g, d.g, c.t
FROM (SELECT 'O_NEG' g, 'O' abo, 'NEG' rh UNION ALL SELECT 'O_POS', 'O', 'POS'
      UNION ALL SELECT 'A_NEG', 'A', 'NEG' UNION ALL SELECT 'A_POS', 'A', 'POS'
      UNION ALL SELECT 'B_NEG', 'B', 'NEG' UNION ALL SELECT 'B_POS', 'B', 'POS'
      UNION ALL SELECT 'AB_NEG', 'AB', 'NEG' UNION ALL SELECT 'AB_POS', 'AB', 'POS') r
JOIN (SELECT 'O_NEG' g, 'O' abo, 'NEG' rh UNION ALL SELECT 'O_POS', 'O', 'POS'
      UNION ALL SELECT 'A_NEG', 'A', 'NEG' UNION ALL SELECT 'A_POS', 'A', 'POS'
      UNION ALL SELECT 'B_NEG', 'B', 'NEG' UNION ALL SELECT 'B_POS', 'B', 'POS'
      UNION ALL SELECT 'AB_NEG', 'AB', 'NEG' UNION ALL SELECT 'AB_POS', 'AB', 'POS') d
JOIN (SELECT 'RED_CELLS' t UNION ALL SELECT 'WHOLE_BLOOD') c
WHERE (d.abo = 'O' OR d.abo = r.abo OR r.abo = 'AB')
  AND (d.rh = 'NEG' OR r.rh = 'POS');

INSERT INTO blood_compatibility (recipient_group, donor_group, component_type)
SELECT r.g, d.g, c.t
FROM (SELECT 'O_NEG' g, 'O' abo UNION ALL SELECT 'O_POS', 'O'
      UNION ALL SELECT 'A_NEG', 'A' UNION ALL SELECT 'A_POS', 'A'
      UNION ALL SELECT 'B_NEG', 'B' UNION ALL SELECT 'B_POS', 'B'
      UNION ALL SELECT 'AB_NEG', 'AB' UNION ALL SELECT 'AB_POS', 'AB') r
JOIN (SELECT 'O_NEG' g, 'O' abo UNION ALL SELECT 'O_POS', 'O'
      UNION ALL SELECT 'A_NEG', 'A' UNION ALL SELECT 'A_POS', 'A'
      UNION ALL SELECT 'B_NEG', 'B' UNION ALL SELECT 'B_POS', 'B'
      UNION ALL SELECT 'AB_NEG', 'AB' UNION ALL SELECT 'AB_POS', 'AB') d
JOIN (SELECT 'PLASMA' t UNION ALL SELECT 'PLATELETS') c
WHERE (r.abo = 'O' OR d.abo = r.abo OR d.abo = 'AB');

-- Two test banks ~7 km apart, so nearby search and transfers can be demoed.
INSERT INTO blood_bank (code, name, city, address, phone, latitude, longitude, created_at) VALUES
  ('BB-PUN-01', 'Central Blood Bank', 'Pune', 'Shivajinagar, Pune', '020-1111-0001', 18.5308, 73.8475, UTC_TIMESTAMP(6)),
  ('BB-PUN-02', 'Eastside Blood Bank', 'Pune', 'Hadapsar, Pune',     '020-1111-0002', 18.5089, 73.9260, UTC_TIMESTAMP(6));

INSERT INTO hospital (name, city, latitude, longitude, created_at) VALUES
  ('City General Hospital', 'Pune', 18.5204, 73.8567, UTC_TIMESTAMP(6));
