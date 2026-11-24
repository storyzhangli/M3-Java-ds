package com.zl.d41.knowledge.table;

import com.zl.d41.knowledge.table.model.ExtractedTable;

import java.util.List;

/**
 * OCR 禁用占位实现（唯一装配实现：无引擎，engineAvailable=false）。
 */
public final class DisabledOcrTablePort implements OcrTablePort {

    @Override
    public List<ExtractedTable> recognizeImageTable(byte[] imageBytes, int pageNumber) {
        return List.of();
    }

    @Override
    public boolean engineAvailable() {
        return false;
    }
}
