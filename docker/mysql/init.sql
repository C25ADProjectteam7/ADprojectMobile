-- ============================================================
-- Mobile database — complete DDL (12 tables)
-- Auto-executed on first MySQL container start
-- Engine: InnoDB | Charset: utf8mb4
-- ============================================================

USE mobile;

-- -----------------------------------------------------------
-- 1. users
-- -----------------------------------------------------------
CREATE TABLE IF NOT EXISTS users (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    username    VARCHAR(50)  NOT NULL UNIQUE,
    password    VARCHAR(255) NOT NULL,
    email       VARCHAR(100) UNIQUE,
    department  VARCHAR(100),
    phone       VARCHAR(20),
    role        VARCHAR(20)  NOT NULL DEFAULT 'EMPLOYEE',
    avatar_url  VARCHAR(500),
    enabled     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_users_email (email),
    INDEX idx_users_dept (department)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- -----------------------------------------------------------
-- 2. trips
-- -----------------------------------------------------------
CREATE TABLE IF NOT EXISTS trips (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id       BIGINT       NOT NULL,
    title         VARCHAR(200) NOT NULL,
    destination   VARCHAR(100),
    start_date    DATE         NOT NULL,
    end_date      DATE         NOT NULL,
    budget_total  DECIMAL(12,2),
    status        VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_trips_user FOREIGN KEY (user_id) REFERENCES users(id),
    INDEX idx_trips_user_id (user_id),
    INDEX idx_trips_status (status),
    INDEX idx_trips_dates (start_date, end_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- -----------------------------------------------------------
-- 3. itineraries
-- -----------------------------------------------------------
CREATE TABLE IF NOT EXISTS itineraries (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    trip_id            BIGINT      NOT NULL,
    day_number         INT         NOT NULL,
    date               DATE,
    notes              TEXT,
    generated_by_agent BOOLEAN     NOT NULL DEFAULT FALSE,
    CONSTRAINT fk_itineraries_trip FOREIGN KEY (trip_id) REFERENCES trips(id),
    INDEX idx_itineraries_trip (trip_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- -----------------------------------------------------------
-- 4. itinerary_items
-- -----------------------------------------------------------
CREATE TABLE IF NOT EXISTS itinerary_items (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    itinerary_id BIGINT       NOT NULL,
    type         VARCHAR(20)  NOT NULL,
    start_time   DATETIME,
    end_time     DATETIME,
    title        VARCHAR(300),
    description  TEXT,
    location     VARCHAR(500),
    booking_ref  VARCHAR(100),
    price        DECIMAL(12,2),
    currency     VARCHAR(3)   DEFAULT 'CNY',
    status       VARCHAR(20)  DEFAULT 'PENDING',
    CONSTRAINT fk_items_itinerary FOREIGN KEY (itinerary_id) REFERENCES itineraries(id),
    INDEX idx_items_itinerary (itinerary_id),
    INDEX idx_items_type (type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- -----------------------------------------------------------
-- 5. flights (Amadeus/Duffel cache)
-- -----------------------------------------------------------
CREATE TABLE IF NOT EXISTS flights (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    flight_number     VARCHAR(20),
    airline           VARCHAR(100),
    departure_airport VARCHAR(10),
    arrival_airport   VARCHAR(10),
    departure_time    DATETIME,
    arrival_time      DATETIME,
    price             DECIMAL(12,2),
    currency          VARCHAR(3)   DEFAULT 'CNY',
    cabin_class       VARCHAR(20),
    available_seats   INT,
    source            VARCHAR(20)  DEFAULT 'DUFFEL',
    cached_at         DATETIME,
    INDEX idx_flights_route (departure_airport, arrival_airport),
    INDEX idx_flights_date (departure_time),
    INDEX idx_flights_cached (cached_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- -----------------------------------------------------------
-- 6. hotels (Amadeus/LiteAPI cache)
-- -----------------------------------------------------------
CREATE TABLE IF NOT EXISTS hotels (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    name            VARCHAR(300),
    city            VARCHAR(100),
    address         VARCHAR(500),
    latitude        DECIMAL(10,7),
    longitude       DECIMAL(10,7),
    price_per_night DECIMAL(12,2),
    currency        VARCHAR(3)   DEFAULT 'CNY',
    rating          DECIMAL(3,2),
    amenities       JSON,
    description     TEXT,
    source          VARCHAR(20)  DEFAULT 'LITEAPI',
    cached_at       DATETIME,
    INDEX idx_hotels_city (city),
    INDEX idx_hotels_price (price_per_night)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- -----------------------------------------------------------
-- 7. restaurants (Google Places cache)
-- -----------------------------------------------------------
CREATE TABLE IF NOT EXISTS restaurants (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    name          VARCHAR(300),
    city          VARCHAR(100),
    address       VARCHAR(500),
    latitude      DECIMAL(10,7),
    longitude     DECIMAL(10,7),
    cuisine_type  VARCHAR(100),
    price_level   INT,
    rating        DECIMAL(3,2),
    photos        JSON,
    opening_hours JSON,
    source        VARCHAR(20) DEFAULT 'GOOGLE_PLACES',
    cached_at     DATETIME,
    INDEX idx_rest_city (city),
    INDEX idx_rest_cuisine (cuisine_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- -----------------------------------------------------------
-- 8. attractions (Google Places cache)
-- -----------------------------------------------------------
CREATE TABLE IF NOT EXISTS attractions (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    name          VARCHAR(300),
    city          VARCHAR(100),
    address       VARCHAR(500),
    latitude      DECIMAL(10,7),
    longitude     DECIMAL(10,7),
    category      VARCHAR(50),
    rating        DECIMAL(3,2),
    description   TEXT,
    photos        JSON,
    opening_hours JSON,
    ticket_price  DECIMAL(12,2),
    source        VARCHAR(20) DEFAULT 'GOOGLE_PLACES',
    cached_at     DATETIME,
    INDEX idx_attr_city (city),
    INDEX idx_attr_cat (category)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- -----------------------------------------------------------
-- 9. place_images
-- -----------------------------------------------------------
CREATE TABLE IF NOT EXISTS place_images (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    place_type    VARCHAR(20) NOT NULL,
    place_id      BIGINT,
    image_url     VARCHAR(1000),
    local_path    VARCHAR(500),
    downloaded_at DATETIME,
    INDEX idx_images_place (place_type, place_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- -----------------------------------------------------------
-- 10. bookings
-- -----------------------------------------------------------
CREATE TABLE IF NOT EXISTS bookings (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    trip_id      BIGINT      NOT NULL,
    user_id      BIGINT      NOT NULL,
    type         VARCHAR(10) NOT NULL,
    flight_id    BIGINT,
    hotel_id     BIGINT,
    booking_ref  VARCHAR(100),
    price        DECIMAL(12,2),
    currency     VARCHAR(3)  DEFAULT 'CNY',
    status       VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    booked_at    DATETIME,
    created_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_bookings_trip FOREIGN KEY (trip_id) REFERENCES trips(id),
    CONSTRAINT fk_bookings_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_bookings_flight FOREIGN KEY (flight_id) REFERENCES flights(id),
    CONSTRAINT fk_bookings_hotel FOREIGN KEY (hotel_id) REFERENCES hotels(id),
    INDEX idx_bookings_trip (trip_id),
    INDEX idx_bookings_user (user_id),
    INDEX idx_bookings_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- -----------------------------------------------------------
-- 11. expenses
-- -----------------------------------------------------------
CREATE TABLE IF NOT EXISTS expenses (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    trip_id      BIGINT       NOT NULL,
    user_id      BIGINT       NOT NULL,
    category     VARCHAR(20)  NOT NULL,
    amount       DECIMAL(12,2) NOT NULL,
    currency     VARCHAR(3)   DEFAULT 'CNY',
    description  TEXT,
    receipt_url  VARCHAR(500),
    status       VARCHAR(20)  NOT NULL DEFAULT 'SUBMITTED',
    submitted_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    reviewed_at  DATETIME,
    review_note  TEXT,
    CONSTRAINT fk_expenses_trip FOREIGN KEY (trip_id) REFERENCES trips(id),
    CONSTRAINT fk_expenses_user FOREIGN KEY (user_id) REFERENCES users(id),
    INDEX idx_expenses_trip (trip_id),
    INDEX idx_expenses_user (user_id),
    INDEX idx_expenses_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- -----------------------------------------------------------
-- 12. agent_conversations
-- -----------------------------------------------------------
CREATE TABLE IF NOT EXISTS agent_conversations (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id      BIGINT NOT NULL,
    trip_id      BIGINT,
    role         VARCHAR(20) NOT NULL,
    content      TEXT,
    tool_calls   JSON,
    token_count  INT,
    created_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_conv_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_conv_trip FOREIGN KEY (trip_id) REFERENCES trips(id),
    INDEX idx_conv_user_trip (user_id, trip_id),
    INDEX idx_conv_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- -----------------------------------------------------------
-- Initial demo data
-- -----------------------------------------------------------
INSERT INTO users (username, password, email, department, role)
VALUES ('admin', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', 'admin@team7.com', 'IT', 'ADMIN')
ON DUPLICATE KEY UPDATE username = username;
