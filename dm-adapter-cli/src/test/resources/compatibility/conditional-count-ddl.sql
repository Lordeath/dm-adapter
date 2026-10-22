-- Synthetic regression fixture: conditional prepared statements.
SET @sampleDdl = IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sample_records' AND COLUMN_NAME = 'verified') = 0,
  'ALTER TABLE sample_records ADD COLUMN verified INT NOT NULL DEFAULT 0 COMMENT ''是否已确认''', 'SELECT 1');
PREPARE sampleStatement FROM @sampleDdl;
EXECUTE sampleStatement;
DEALLOCATE PREPARE sampleStatement;

SET @sampleDdl = IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sample_records' AND COLUMN_NAME = 'revision') = 0,
  'ALTER TABLE sample_records ADD COLUMN revision VARCHAR(36) DEFAULT NULL COMMENT ''记录版本，更新时生成''', 'SELECT 1');
PREPARE sampleStatement FROM @sampleDdl;
EXECUTE sampleStatement;
DEALLOCATE PREPARE sampleStatement;

SET @sampleDdl = IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sample_records' AND COLUMN_NAME = 'recordState') = 0,
  'ALTER TABLE sample_records ADD COLUMN recordState VARCHAR(16) NOT NULL DEFAULT ''ACTIVE'' COMMENT ''记录状态，ACTIVE/INACTIVE''', 'SELECT 1');
PREPARE sampleStatement FROM @sampleDdl;
EXECUTE sampleStatement;
DEALLOCATE PREPARE sampleStatement;
