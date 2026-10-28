-- Whether a person-type common ticket field takes several people (the default) or one. A value that takes several is a JSON
-- array of user ids; one that takes a single person is a JSON string, as person values were before this column.
ALTER TABLE project_issue_fields ADD COLUMN multiple boolean NOT NULL DEFAULT true;

-- Every field that exists now takes several, so its single values become arrays of one.
UPDATE issue_field_values v
SET value = jsonb_build_array(v.value)
FROM project_issue_fields f
WHERE f.id = v.field_id AND f.type = 'PERSON' AND jsonb_typeof(v.value) = 'string';

UPDATE project_issue_fields
SET default_value = jsonb_build_array(default_value)
WHERE type = 'PERSON' AND jsonb_typeof(default_value) = 'string';
