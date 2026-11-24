package com.zl.d41.knowledge.table;

import com.zl.d41.knowledge.table.model.ExtractedTable;

import java.util.List;

/**
 * OCR 表格识别可选端口（D4 裁决：R01 无引擎，仅接口占位；ocr.enabled=false 默认）。
 *
 * <p>主路径不依赖本端口：图片表/空白含图页由 {@link PdfTableExtractor} 直接落
 * IMAGE_TABLE_NOT_PARSED 退化记录。真实 OCR 引擎引入属 H3 红线（熔断请示项），
 * 评估列 D42 候选。</p>
 */
public interface OcrTablePort {

    /** 对图片字节执行表格识别；无引擎实现一律返回空列表（诚实零结果，不伪造）。 */
    List<ExtractedTable> recognizeImageTable(byte[] imageBytes, int pageNumber);

    /** 引擎可用性（置信度字段前置判据：不可用时置信度 N/A，H8）。 */
    boolean engineAvailable();
}
