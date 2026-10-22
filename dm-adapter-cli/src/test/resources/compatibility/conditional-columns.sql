-- Synthetic regression fixture: conditional prepared statements.
SET @sample_ddl_sql = IF(EXISTS(
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sample_record_History'
      AND COLUMN_NAME = 'categoryCode'
), 'SELECT 1', 'ALTER TABLE `sample_record_History` ADD COLUMN `categoryCode` VARCHAR(64) NULL COMMENT ''分类编码''');
PREPARE sample_ddl_stmt FROM @sample_ddl_sql;
EXECUTE sample_ddl_stmt;
DEALLOCATE PREPARE sample_ddl_stmt;

SET @sample_ddl_sql = IF(EXISTS(
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sample_record_History'
      AND COLUMN_NAME = 'categoryLabel'
), 'SELECT 1', 'ALTER TABLE `sample_record_History` ADD COLUMN `categoryLabel` VARCHAR(200) NULL COMMENT ''分类名称快照''');
PREPARE sample_ddl_stmt FROM @sample_ddl_sql;
EXECUTE sample_ddl_stmt;
DEALLOCATE PREPARE sample_ddl_stmt;
