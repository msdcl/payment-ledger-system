-- Phase 6: Consumers & Replay Safety
-- This migration creates the processed_events table for idempotent event consumption.
--
-- The processed_events table ensures:
-- 1. Each event is processed exactly once (deduplication)
-- 2. Replayed events are safely ignored
-- 3. Consumer crashes don't cause duplicate processing
-- 4. Multiple consumer instances can run safely

-- Step 1: Create the processed_events table
CREATE TABLE processed_events (
    -- The event ID from the original event (used for deduplication)
    event_id UUID PRIMARY KEY,

    -- Event metadata for debugging/auditing
    event_type VARCHAR(100) NOT NULL,
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id UUID NOT NULL,

    -- Consumer that processed this event
    consumer_group VARCHAR(100) NOT NULL,

    -- Processing metadata
    processed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),

    -- Optional: Store result or error for debugging
    processing_result VARCHAR(50),  -- 'SUCCESS', 'SKIPPED', 'FAILED'
    error_message TEXT
);

-- Step 2: Create indexes for efficient querying

-- Index for checking if event was already processed by a specific consumer
-- This is the primary lookup pattern
CREATE INDEX idx_processed_events_consumer_event
ON processed_events(consumer_group, event_id);

-- Index for finding events by aggregate (useful for debugging)
CREATE INDEX idx_processed_events_aggregate
ON processed_events(aggregate_type, aggregate_id);

-- Index for cleanup queries (delete old processed events)
CREATE INDEX idx_processed_events_processed_at
ON processed_events(processed_at);

-- Step 3: Add comments
COMMENT ON TABLE processed_events IS
'Tracks which events have been processed by each consumer group. Used for idempotent event processing and replay safety.';

COMMENT ON COLUMN processed_events.event_id IS
'The unique ID from the original event payload. Used to deduplicate replayed events.';

COMMENT ON COLUMN processed_events.consumer_group IS
'The Kafka consumer group that processed this event. Different consumer groups can process the same event independently.';
