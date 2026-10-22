-- Milestones ("목표") are gone (D-189): work that needs stages is a ticket with sub-tickets. What hung
-- on a milestone goes with it. Tickets stay; they only lose the link. The files its comments held and the
-- pictures in its text stay in the file store until "쓰지 않는 파일 정리" in the admin's file management
-- finds nothing uses them.

-- Their comments, comment threads, activity and files. Replies, emoji and thread comments go with their rows.
DELETE FROM content_files WHERE target_type = 'MILESTONE';
DELETE FROM content_comments WHERE target_type = 'MILESTONE';
DELETE FROM content_threads WHERE target_type = 'MILESTONE';
DELETE FROM content_activities WHERE target_type = 'MILESTONE';
-- What the project's own activity said about them.
DELETE FROM content_activities WHERE type IN ('MILESTONE_ADDED', 'MILESTONE_REMOVED');

ALTER TABLE content_threads DROP CONSTRAINT content_threads_target_type_check;
ALTER TABLE content_threads ADD CONSTRAINT content_threads_target_type_check
    CHECK (target_type IN ('PROJECT', 'ISSUE', 'DOCUMENT'));
ALTER TABLE content_activities DROP CONSTRAINT content_activities_target_type_check;
ALTER TABLE content_activities ADD CONSTRAINT content_activities_target_type_check
    CHECK (target_type IN ('PROJECT', 'DOCUMENT'));
ALTER TABLE content_comments DROP CONSTRAINT content_comments_target_type_check;
ALTER TABLE content_comments ADD CONSTRAINT content_comments_target_type_check
    CHECK (target_type IN ('PROJECT', 'DOCUMENT'));
ALTER TABLE content_files DROP CONSTRAINT content_files_target_type_check;
ALTER TABLE content_files ADD CONSTRAINT content_files_target_type_check
    CHECK (target_type IN ('PROJECT', 'DOCUMENT'));

-- Properties people added to a milestone. The check that exactly one owner is set goes with the column.
DELETE FROM custom_properties WHERE milestone_id IS NOT NULL;
DROP INDEX custom_properties_milestone_label_idx;
ALTER TABLE custom_properties DROP COLUMN milestone_id;
ALTER TABLE custom_properties ADD CONSTRAINT custom_properties_one_owner CHECK (num_nonnulls(project_id, issue_id) = 1);

-- The ticket's link to its milestone, and the history lines that said it changed.
DELETE FROM issue_activities WHERE type = 'MILESTONE_CHANGED';
DROP INDEX issues_milestone_id_idx;
ALTER TABLE issues DROP COLUMN milestone_id;

-- An API notification that asked only for milestone changes would have an empty list left, which means
-- "every kind of change": it is turned off instead of starting to send everything.
UPDATE webhooks SET enabled = false WHERE event_types = 'MILESTONE_CHANGED';
UPDATE webhooks
SET event_types = array_to_string(array_remove(string_to_array(event_types, ','), 'MILESTONE_CHANGED'), ',')
WHERE event_types LIKE '%MILESTONE_CHANGED%';

-- Who had a milestone's window open.
DELETE FROM page_views WHERE page_key LIKE 'milestone:%';

DROP TABLE milestone_assignees;
DROP TABLE milestones;
