-- Project icon (an uploaded image, stored as WebP like avatars and the workspace logo).
-- Null: the default box icon is shown.
ALTER TABLE projects ADD COLUMN icon_key text;
