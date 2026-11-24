package com.zl.d41.experiment;

import com.zl.d41.experiment.ExperimentReasonCode;

/**
 * 实验运行期异常：携带稳定原因码，未知失败不允许吞掉。
 */
public class ExperimentException extends RuntimeException {

    private final transient ExperimentReasonCode reasonCode;

    public ExperimentException(ExperimentReasonCode reasonCode, String message) {
        super(message);
        this.reasonCode = reasonCode;
    }

    public ExperimentException(ExperimentReasonCode reasonCode, String message, Throwable cause) {
        super(message, cause);
        this.reasonCode = reasonCode;
    }

    public ExperimentReasonCode reasonCode() {
        return reasonCode;
    }

    public String reasonCodeName() {
        return reasonCode.code();
    }
}
