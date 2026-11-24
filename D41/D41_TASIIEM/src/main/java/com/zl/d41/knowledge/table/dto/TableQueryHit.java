package com.zl.d41.knowledge.table.dto;

import com.zl.d41.knowledge.table.model.TableCell;

import java.util.List;

/** 精确检索命中（按列值，含来源表定位）。 */
public record TableQueryHit(long tableId,
                            String documentId,
                            String version,
                            int pageNumber,
                            int tableIndex,
                            int rowIndex,
                            List<TableCell> cells) {
}
