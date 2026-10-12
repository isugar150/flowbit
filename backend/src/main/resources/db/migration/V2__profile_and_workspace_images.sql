-- File name of an uploaded image inside the storage directory (FLOWBIT_STORAGE_PATH), or null.
ALTER TABLE users
    ADD COLUMN avatar_key text CHECK (avatar_key ~ '^[0-9a-f-]{36}\.webp$');

ALTER TABLE instance_settings
    ADD COLUMN logo_key text CHECK (logo_key ~ '^[0-9a-f-]{36}\.webp$');
