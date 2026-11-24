-- D41 表格结构化记录 DDL（two 线，共享 ai_db）
-- 裁决（KP2/ddlAdjudication）：d41_table_extract/d41_table_row 已由 one 线预建（0 行），
-- two 线禁止 ALTER/DROP 他线资产；本脚本与预建物理结构逐列一致，
-- 目的=全新环境可独立建表 + 现环境执行零效果验证幂等（任务书 C03：执行两遍均成功且不改变结构）。
-- 注意：无 linked_chunk_ids_json 列——关联命中态持久化于 detect_flags，chunk 清单读面实时计算。

CREATE TABLE IF NOT EXISTS d41_table_extract (
  id                        BIGINT        NOT NULL AUTO_INCREMENT,
  document_id               VARCHAR(64)   NOT NULL,
  version                   INT           NOT NULL,
  page_number               INT           NOT NULL,
  table_index               INT           NOT NULL,
  header_snapshot_json      JSON          NOT NULL,
  row_count                 INT           NOT NULL DEFAULT 0,
  col_count                 INT           NOT NULL DEFAULT 0,
  source_global_start       INT           NULL,
  source_global_end         INT           NULL,
  source_line_start         INT           NULL,
  source_line_end           INT           NULL,
  detect_flags              VARCHAR(255)  NOT NULL DEFAULT '',
  confidence                VARCHAR(16)   NOT NULL DEFAULT 'HIGH',
  continuation_of_table_id  BIGINT        NULL,
  status                    VARCHAR(16)   NOT NULL DEFAULT 'ACTIVE',
  created_at                DATETIME      NOT NULL,
  updated_at                DATETIME      NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_d41_doc_ver_page_table (document_id, version, page_number, table_index),
  KEY idx_d41_table_doc_ver (document_id, version),
  KEY idx_d41_table_global (document_id, version, source_global_start, source_global_end)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS d41_table_row (
  id                BIGINT       NOT NULL AUTO_INCREMENT,
  table_id          BIGINT       NOT NULL,
  row_index         INT          NOT NULL,
  cells_json        JSON         NOT NULL,
  source_line_number INT         NULL,
  record_hash       CHAR(64)     NOT NULL,
  created_at        DATETIME     NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_d41_table_row (table_id, row_index),
  KEY idx_d41_row_hash (record_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
