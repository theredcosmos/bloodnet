-- Extra banks and hospitals for demos. Not a Flyway migration on purpose: demo data
-- shouldn't end up in a real deployment. Safe to run again (INSERT IGNORE on unique codes,
-- NOT EXISTS for hospitals, which have no unique key).
-- Run through seed_demo_data.py, or by hand:
--   docker compose exec -T mysql mysql -ubloodnet -pbloodnet bloodnet < scripts/demo_reference_data.sql

INSERT IGNORE INTO blood_bank (code, name, city, address, phone, latitude, longitude, created_at) VALUES
  ('BB-PUN-03', 'Kothrud Blood Centre',      'Pune',          'Karve Road, Kothrud',      '020-1111-0003', 18.5074, 73.8077, UTC_TIMESTAMP(6)),
  ('BB-PCM-01', 'Pimpri Community Blood Bank','Pimpri-Chinchwad','Pimpri Camp',          '020-1111-0004', 18.6298, 73.7997, UTC_TIMESTAMP(6)),
  ('BB-PUN-04', 'Hinjewadi Lifeline Bank',    'Pune',          'Phase 1, Hinjewadi',       '020-1111-0005', 18.5913, 73.7389, UTC_TIMESTAMP(6));

INSERT INTO hospital (name, city, latitude, longitude, created_at)
SELECT * FROM (
  SELECT 'Sahyadri Care Hospital' AS name, 'Pune' AS city, 18.5089 AS lat, 73.8325 AS lng, UTC_TIMESTAMP(6) AS ts
  UNION ALL SELECT 'Riverside Children''s Hospital', 'Pune', 18.5362, 73.8940, UTC_TIMESTAMP(6)
  UNION ALL SELECT 'Pimpri Metro Hospital', 'Pimpri-Chinchwad', 18.6186, 73.8037, UTC_TIMESTAMP(6)
) h
WHERE NOT EXISTS (SELECT 1 FROM hospital x WHERE x.name = h.name);
