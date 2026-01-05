-- Phase 5: Transactional Outbox Pattern
-- This migration creates the outbox_events table for reliable event publishing.
--
-- The outbox pattern ensures:
-- 1. Events are written atomically with business data (same DB transaction)
-- 2. Events are eventually published to Kafka (at-least-once delivery)
-- 3. System can recover from crashes without losing events
-- 4. No distributed transactions needed between DB and Kafka

-- Step 1: Create the outbox_events table
CREATE TABLE outbox_events (
    id UUID PRIMARY KEY,

    -- Aggregate information (what entity this event is about)
    aggregate_type VARCHAR(100) NOT NULL,  -- e.g., 'Payment'
    aggregate_id UUID NOT NULL,             -- e.g., payment ID

    -- Event information
    event_type VARCHAR(100) NOT NULL,       -- e.g., 'PaymentCreated', 'PaymentSettled'
    payload JSONB NOT NULL,                 -- Event data as JSON

    -- Publishing metadata
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    published_at TIMESTAMP WITH TIME ZONE,  -- NULL means not yet published

    -- Retry tracking for failed publishes
    retry_count INTEGER NOT NULL DEFAULT 0,
    last_error TEXT,                        -- Last error message if publish failed

    -- Ordering guarantee: events for same aggregate should be processed in order
    sequence_number BIGSERIAL NOT NULL
);

-- Step 2: Create indexes for efficient querying

-- Index for finding unpublished events (the publisher's main query)
-- Partial index only includes unpublished events for efficiency
CREATE INDEX idx_outbox_events_unpublished
ON outbox_events(created_at)
WHERE published_at IS NULL;

-- Index for finding events by aggregate (useful for debugging/auditing)
CREATE INDEX idx_outbox_events_aggregate
ON outbox_events(aggregate_type, aggregate_id);

-- Index for ordering events within an aggregate
CREATE INDEX idx_outbox_events_sequence
ON outbox_events(aggregate_id, sequence_number);

-- Step 3: Add comment explaining the table's purpose
COMMENT ON TABLE outbox_events IS
'Transactional outbox for reliable event publishing. Events are written atomically with business data and published to Kafka by a background process.';

COMMENT ON COLUMN outbox_events.published_at IS
'Timestamp when event was successfully published to Kafka. NULL means pending publication.';

COMMENT ON COLUMN outbox_events.sequence_number IS
'Auto-incrementing sequence for ordering events. Events for the same aggregate should be published in sequence order.';
