package com.zl.d43.knowledge.table.dto;

import java.util.List;

/** 抽取/重抽取执行摘要（结构化轨计数与退化标注，H8 诚实）。 */
public record TableExtractSummary(String documentId,
                                  String version,
                                  int tables,
                                  int rows,
                                  int linkageHitTables,
                                  int linkageMissingTables,
                                  List<String> degradedCodes) {
}
