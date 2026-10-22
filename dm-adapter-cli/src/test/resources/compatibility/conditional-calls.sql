-- Synthetic regression fixture: conditional prepared statements.
call upsert_sample_item('sampleTemplate','示例链接','示例链接','1','11');

call upsert_sample_dictionary('sampleCategory','sampleCategory','示例分类','custom','示例','1','1');
call upsert_sample_dictionary('sampleRequiredOption','sampleRequiredOption','示例字段是否必填','custom','示例','1','1');
SET @sample_call_sql = IF(EXISTS(
    SELECT 1 FROM sample_dictionary_item
    WHERE DICTIONARY_ID = 'sampleRequiredOption' AND ITEM_CODE = '0'
), 'SELECT 1', 'CALL upsert_sample_item(''sampleRequiredOption'',''0'',''不必填'',''1'',''1'')');
PREPARE sample_call_stmt FROM @sample_call_sql;
EXECUTE sample_call_stmt;
DEALLOCATE PREPARE sample_call_stmt;

SET @sample_call_sql = IF(EXISTS(
    SELECT 1 FROM sample_dictionary_item
    WHERE DICTIONARY_ID = 'sampleRequiredOption' AND ITEM_CODE = '1'
), 'SELECT 1', 'CALL upsert_sample_item(''sampleRequiredOption'',''1'',''必填'',''0'',''2'')');
PREPARE sample_call_stmt FROM @sample_call_sql;
EXECUTE sample_call_stmt;
DEALLOCATE PREPARE sample_call_stmt;
