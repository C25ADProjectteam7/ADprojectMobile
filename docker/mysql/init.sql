-- ============================================================
-- Mobile database — DDL (auto-executed on first MySQL container start)
-- Engine: InnoDB | Charset: utf8mb4 | 12 tables
-- ============================================================

USE mobile;

-- -----------------------------------------------------------
-- 1. users
-- TODO: id PK, username UNIQUE, password(BCrypt), email, department, phone, role ENUM, avatar_url, enabled, timestamps
-- -----------------------------------------------------------

-- -----------------------------------------------------------
-- 2. trips
-- TODO: id PK, user_id FK->users, title, destination, start/end dates, budget_total, status ENUM, timestamps
-- -----------------------------------------------------------

-- -----------------------------------------------------------
-- 3. itineraries
-- TODO: id PK, trip_id FK->trips, day_number, date, notes, generated_by_agent BOOL
-- -----------------------------------------------------------

-- -----------------------------------------------------------
-- 4. itinerary_items
-- TODO: id PK, itinerary_id FK->itineraries, type ENUM(FLIGHT/HOTEL/RESTAURANT/ATTRACTION/MEETING/TRANSPORT), times, title, location, booking_ref, price, status
-- -----------------------------------------------------------

-- -----------------------------------------------------------
-- 5. flights (Amadeus cache)
-- TODO: id PK, flight_number, airline, departure/arrival airports, times, price, currency, cabin_class, source, cached_at
-- -----------------------------------------------------------

-- -----------------------------------------------------------
-- 6. hotels (Amadeus cache)
-- TODO: id PK, name, city, address, lat/lng, price_per_night, currency, rating, amenities(JSON), description, source, cached_at
-- -----------------------------------------------------------

-- -----------------------------------------------------------
-- 7. restaurants (Google Places cache)
-- TODO: id PK, name, city, address, lat/lng, cuisine_type, price_level, rating, photos(JSON), opening_hours(JSON), source, cached_at
-- -----------------------------------------------------------

-- -----------------------------------------------------------
-- 8. attractions (Google Places cache)
-- TODO: id PK, name, city, address, lat/lng, category, rating, description, photos(JSON), opening_hours(JSON), ticket_price, source, cached_at
-- -----------------------------------------------------------

-- -----------------------------------------------------------
-- 9. place_images (agent-downloaded images)
-- TODO: id PK, place_type ENUM, place_id, image_url, local_path, downloaded_at
-- -----------------------------------------------------------

-- -----------------------------------------------------------
-- 10. bookings
-- TODO: id PK, trip_id FK, user_id FK, type ENUM(FLIGHT/HOTEL), flight_id FK, hotel_id FK, booking_ref, price, currency, status ENUM, timestamps
-- -----------------------------------------------------------

-- -----------------------------------------------------------
-- 11. expenses
-- TODO: id PK, trip_id FK, user_id FK, category ENUM, amount, currency, description, receipt_url, status ENUM(SUBMITTED/APPROVED/REJECTED), submitted_at, reviewed_at, review_note
-- -----------------------------------------------------------

-- -----------------------------------------------------------
-- 12. agent_conversations
-- TODO: id PK, user_id FK, trip_id FK, role ENUM(USER/ASSISTANT/SYSTEM), content TEXT, tool_calls JSON, token_count, created_at
-- -----------------------------------------------------------
