package com.zl.d44.knowledge.table.dto;

import java.util.List;

/** 表格记录视图（读面返回；禁无约束 Map，H1 契约）。 */
public record TableView(long id,
                        String documentId,
                        String version,
                        int pageNumber,
                        int tableIndex,
                        List<String> header,
                        int rowCount,
                        int colCount,
                        Integer sourceLineStart,
                        Integer sourceLineEnd,
                        List<String> linkedChunkIds,
                        List<String> detectFlags,
                        String confidence,
                        Long continuationOfTableId,
                        String status) {
}
