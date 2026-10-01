-- V1__init_schema.sql
-- Schema for Seat Reservation Service under heavy concurrency

CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- Shows Table
CREATE TABLE shows (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(255) NOT NULL,
    price_paise BIGINT NOT NULL CHECK (price_paise >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_shows_created_at ON shows (created_at);

-- Reservations Table
CREATE TABLE reservations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    show_id UUID NOT NULL REFERENCES shows(id) ON DELETE RESTRICT,
    user_id VARCHAR(128) NOT NULL,
    seat_identifiers TEXT NOT NULL,
    seat_count INT NOT NULL CHECK (seat_count > 0),
    amount_paise BIGINT NOT NULL CHECK (amount_paise >= 0),
    status VARCHAR(32) NOT NULL DEFAULT 'confirmed' CHECK (LOWER(status) IN ('confirmed', 'cancelled')),
    idempotency_key VARCHAR(128) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_reservations_show_id ON reservations (show_id);
CREATE INDEX idx_reservations_user_id ON reservations (user_id);
CREATE UNIQUE INDEX uq_reservations_user_idempotency ON reservations (user_id, idempotency_key);

-- Seats Table
CREATE TABLE seats (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    show_id UUID NOT NULL REFERENCES shows(id) ON DELETE CASCADE,
    seat_number VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'available' CHECK (LOWER(status) IN ('available', 'held', 'confirmed')),
    reservation_id UUID REFERENCES reservations(id) ON DELETE SET NULL DEFERRABLE INITIALLY DEFERRED,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_show_seat_number UNIQUE (show_id, seat_number)
);

CREATE INDEX idx_seats_show_status ON seats (show_id, status);
CREATE INDEX idx_seats_reservation_id ON seats (reservation_id);

-- User Show Quota Allocations Table (Locked for per-user concurrency control)
CREATE TABLE user_show_quotas (
    show_id UUID NOT NULL REFERENCES shows(id) ON DELETE CASCADE,
    user_id VARCHAR(128) NOT NULL,
    active_seat_count INT NOT NULL DEFAULT 0 CHECK (active_seat_count >= 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (show_id, user_id)
);

-- Idempotency Records Table (Stores request hash and response payload for guaranteed replay semantics)
CREATE TABLE idempotency_records (
    user_id VARCHAR(128) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    reservation_id UUID REFERENCES reservations(id) ON DELETE CASCADE,
    response_payload TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (user_id, idempotency_key)
);

CREATE INDEX idx_idempotency_records_res_id ON idempotency_records (reservation_id);
