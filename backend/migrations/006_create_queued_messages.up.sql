-- Queued messages for native app message queue
CREATE TABLE queued_messages (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    conversation_id UUID NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    content TEXT NOT NULL,
    queued_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_queued_messages_conversation ON queued_messages(conversation_id);
CREATE INDEX idx_queued_messages_user ON queued_messages(user_id);
CREATE INDEX idx_queued_messages_queued_at ON queued_messages(conversation_id, queued_at ASC);

-- Images attached to queued messages
CREATE TABLE queued_message_images (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    queued_message_id UUID NOT NULL REFERENCES queued_messages(id) ON DELETE CASCADE,
    storage_path TEXT NOT NULL,
    media_type TEXT NOT NULL,
    file_name TEXT,
    width INT,
    height INT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_queued_message_images_message ON queued_message_images(queued_message_id);
