DELETE legacy
FROM bk_artifact_copy legacy
JOIN bk_artifact_copy normalized
  ON normalized.artifact_type = 'SUPPLEMENT_FILE'
 AND normalized.artifact_id = legacy.artifact_id
 AND normalized.location_type = legacy.location_type
 AND normalized.location_ref_id = legacy.location_ref_id
WHERE legacy.artifact_type = 'SUPPLEMENT';

UPDATE bk_artifact_copy
SET artifact_type = 'SUPPLEMENT_FILE'
WHERE artifact_type = 'SUPPLEMENT';

UPDATE bk_volume_archive_item
SET artifact_type = 'SUPPLEMENT_FILE'
WHERE artifact_type = 'SUPPLEMENT';
