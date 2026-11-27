package com.zl.d44.knowledge.table;

/**
 * 表格分支退化/检出标注码表（KP4 冻结，H8 诚实标注，禁静默错配）。
 */
public final class TableDegrade {

    /** docx：Tika 文本轨正常但表格行列结构不可还原（D3，POI 接线 候选）。 */
    public static final String DOCX_TABLE_NOT_STRUCTURED = "DOCX_TABLE_NOT_STRUCTURED";

    /** 图片表/含图空白页：无 OCR 引擎，主路径不崩溃、结构化轨降级（D4）。 */
    public static final String IMAGE_TABLE_NOT_PARSED = "IMAGE_TABLE_NOT_PARSED";

    /** 行单元格数与区域列数不一致且无合并覆盖解释 → 列错位。 */
    public static final String COLUMN_MISALIGNMENT = "COLUMN_MISALIGNMENT";

    /** 单元格 x 覆盖宽度跨入右邻列桶且该行右邻桶无独立起点 → 合并单元格（值归首列，余列 null）。 */
    public static final String MERGED_CELL = "MERGED_CELL";

    /** 列边界间距中位数 < 60pt：纯坐标聚类列边界置信度低（§3.1 无线表口径）。 */
    public static final String LOW_CONFIDENCE = "LOW_CONFIDENCE";

    /** 双轨关联跨文档歧义（保留码；文档级精确匹配口径下极少触发）。 */
    public static final String LINKAGE_AMBIGUOUS = "LINKAGE_AMBIGUOUS";

    /** 双轨关联零命中（页码或规范化包含匹配未命中任何 Chunk，显式标注不静默）。 */
    public static final String LINKAGE_MISSING = "LINKAGE_MISSING";

    /** 跨页续表检出（表头快照匹配，表头行不入数据行）。 */
    public static final String CONTINUATION_HEADER_SKIPPED = "CONTINUATION_HEADER_SKIPPED";

    /** HTML 表格分支已启用（KP1 裁决：既有 Jsoup，非新框架）。 */
    public static final String HTML_TABLE_ENABLED = "HTML_TABLE_ENABLED";

    /** 版本非数字串无法写入既有 int 版本列（one 线预建表结构适配，诚实跳过不落库）。 */
    public static final String VERSION_NOT_PERSISTABLE = "VERSION_NOT_PERSISTABLE";

    /** 重抽取端点对 PDF 字节源不可得时的能力边界标注（错误清单）。 */
    public static final String REEXTRACT_TEXT_TRACK_ONLY = "REEXTRACT_TEXT_TRACK_ONLY";

    /** R02-02：本行存在无法可靠映射到列锚的独立剩余单元格（原文以 EXTRA_COL_* 单元格保留，不静默拼接）。 */
    public static final String EXTRA_CELL = "EXTRA_CELL";

    /** R02-04：抽取失败（异常/不支持扩展名），本次不写不删，旧版本数据完整保留（禁止误当"无表格"清理）。 */
    public static final String PARSE_FAILED = "PARSE_FAILED";

    /** R02-03：文本轨列数异常行独立成组保留（原始行文本/行号/预期实际列数可追溯，不无痕丢弃）。 */
    public static final String MISALIGNED_LINE_GROUP = "MISALIGNED_LINE_GROUP";

    private TableDegrade() {
    }
}
