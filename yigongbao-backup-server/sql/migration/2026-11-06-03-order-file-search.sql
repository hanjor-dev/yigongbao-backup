-- 订单文件页统一关键词检索：持久化重建项目名称，避免列表查询时重复解析 JSON。
SET NAMES utf8mb4 COLLATE utf8mb4_0900_ai_ci;

DROP PROCEDURE IF EXISTS add_rebuild_project_search_column;
DELIMITER $$
CREATE PROCEDURE add_rebuild_project_search_column()
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'bk_order_snapshot'
      AND column_name = 'rebuild_project_names'
  ) THEN
    ALTER TABLE bk_order_snapshot
      ADD COLUMN rebuild_project_names TEXT NULL COMMENT '重建项目名称检索文本'
      AFTER rebuild_project_list_json;
  END IF;
END$$
DELIMITER ;
CALL add_rebuild_project_search_column();
DROP PROCEDURE IF EXISTS add_rebuild_project_search_column;

-- 回填历史快照；新备份订单由应用在写入快照时同步生成该字段。
UPDATE bk_order_snapshot snapshot
SET rebuild_project_names = (
  SELECT GROUP_CONCAT(DISTINCT item.project_name ORDER BY item.project_name SEPARATOR ' ')
  FROM JSON_TABLE(
    COALESCE(snapshot.rebuild_project_list_json, JSON_ARRAY()),
    '$[*]' COLUMNS (project_name VARCHAR(512) PATH '$.projectName')
  ) AS item
  WHERE item.project_name IS NOT NULL AND CHAR_LENGTH(TRIM(item.project_name)) > 0
)
WHERE snapshot.rebuild_project_list_json IS NOT NULL
  AND COALESCE(CHAR_LENGTH(snapshot.rebuild_project_names), 0) = 0;
