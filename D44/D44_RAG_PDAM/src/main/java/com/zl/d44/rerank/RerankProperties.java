package com.zl.d44.rerank;

import com.zl.d44.rerank.RerankReasonCode;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * app.rerank.*（H2：enabled 与 remote.enabled 默认 false；H9：无任何 Key 字段落配置值，
 * DASHSCOPE_API_KEY 仅运行期环境变量读取）。
 */
@ConfigurationProperties(prefix = "app.rerank")
public class RerankProperties {

    private boolean enabled = false;
    private final Remote remote = new Remote();

    public static class Remote {
        private boolean enabled = false;
        private String model = "gte-rerank-v2";
        private long timeoutMillis = 10000;
        private int retryMaxAttempts = 1;
        private long retryBackoffMillis = 500;
        private String ledgerPath = "docs/temp_docs/D44/rerank-ledger.jsonl";
        private double costCapPerQuestionCny = 0.02;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public long getTimeoutMillis() { return timeoutMillis; }
        public void setTimeoutMillis(long timeoutMillis) { this.timeoutMillis = timeoutMillis; }
        public int getRetryMaxAttempts() { return retryMaxAttempts; }
        public void setRetryMaxAttempts(int retryMaxAttempts) { this.retryMaxAttempts = retryMaxAttempts; }
        public long getRetryBackoffMillis() { return retryBackoffMillis; }
        public void setRetryBackoffMillis(long v) { this.retryBackoffMillis = v; }
        public String getLedgerPath() { return ledgerPath; }
        public void setLedgerPath(String ledgerPath) { this.ledgerPath = ledgerPath; }
        public double getCostCapPerQuestionCny() { return costCapPerQuestionCny; }
        public void setCostCapPerQuestionCny(double v) { this.costCapPerQuestionCny = v; }
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Remote getRemote() { return remote; }

    /** fail-fast（对齐 TwoEmbeddingProperties requireValid 先例）。 */
    public void requireValid() {
        Remote r = remote;
        if (r.getModel() == null || r.getModel().isBlank()) {
            throw new IllegalStateException(RerankReasonCode.RERANK_CONFIG_INVALID.code() + ": remote.model 空");
        }
        if (r.getTimeoutMillis() < 100 || r.getTimeoutMillis() > 60000) {
            throw new IllegalStateException(RerankReasonCode.RERANK_CONFIG_INVALID.code() + ": timeout 越界");
        }
        if (r.getRetryMaxAttempts() < 0 || r.getRetryMaxAttempts() > 1) {
            throw new IllegalStateException(RerankReasonCode.RERANK_CONFIG_INVALID.code()
                    + ": retry 仅允许 0/1（冻结口径）");
        }
        if (r.getRetryBackoffMillis() < 0 || r.getRetryBackoffMillis() > 5000) {
            throw new IllegalStateException(RerankReasonCode.RERANK_CONFIG_INVALID.code() + ": backoff 越界");
        }
    }
}