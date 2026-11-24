package com.zl.d41.experiment;

import com.zl.d41.experiment.ReturnedHitRecord;

import java.util.List;

/**
 * 单次重复检索的记录。
 *
 * @param runIndex       重复序号（1 开始）
 * @param elapsedMs      运行器侧用 System.nanoTime 独立计时的检索耗时（毫秒）
 * @param effectiveTopK  实际生效的 Top-K
 * @param hitCount       命中条数
 * @param hits           有序命中列表
 */
public record QuestionRunRecord(int runIndex,
                                double elapsedMs,
                                int effectiveTopK,
                                int hitCount,
                                List<ReturnedHitRecord> hits) {
}
