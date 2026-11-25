package com.zl.d42.rerank;

import com.alibaba.dashscope.exception.ApiException;
import com.alibaba.dashscope.exception.InputRequiredException;
import com.alibaba.dashscope.exception.NoApiKeyException;
import com.alibaba.dashscope.rerank.TextReRank;
import com.alibaba.dashscope.rerank.TextReRankParam;
import com.alibaba.dashscope.rerank.TextReRankResult;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * 百炼 TextReRank 远程适配器（§6.4 冻结口径；单测经 fake client/Supplier 注入，零真实网络）。
 *
 * <p>失败语义：所有失败返回显式 reasonCode 的 outcome（H8 绝不伪装成功）；每次调用
 * （成功或失败）追加一行台账（§6.6）。重试仅 429/5xx/网络类，上限 1 次、退避 500ms；
 * 4xx 与超时（总预算 10s）不重试；超限/越界交由服务端 4xx，适配器不截断（H5）。</p>
 */
public final class BailianTextReranker implements RerankPort {

    static final String PROVIDER = "bailian";

    private final RerankProperties props;
    private final TextReRank client;
    private final RerankCallLedger ledger;
    private final Supplier<String> apiKeySupplier;
    private final ExecutorService executor;

    public BailianTextReranker(RerankProperties props, TextReRank client,
                               RerankCallLedger ledger, Supplier<String> apiKeySupplier) {
        this.props = props;
        this.client = client;
        this.ledger = ledger;
        this.apiKeySupplier = apiKeySupplier;
        this.executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "d42-rerank-call");
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public String provider() {
        return PROVIDER;
    }

    private String model() {
        return props.getRemote().getModel();
    }

    @Override
    public RerankOutcome rerank(RerankRequest request) {
        long t0 = System.nanoTime();
        List<RerankCandidate> cands = request.candidates();
        if (cands.isEmpty()) {
            RerankStageStats stats = new RerankStageStats(ms(t0), 0, 0, null, PROVIDER, model());
            writeLedger(request, stats, "FAIL",
                    RerankReasonCode.RERANK_EMPTY_CANDIDATES.code(), null, null, t0);
            return new RerankOutcome(List.of(), stats,
                    RerankReasonCode.RERANK_EMPTY_CANDIDATES.code());
        }
        String apiKey = apiKeySupplier.get();
        if (apiKey == null || apiKey.isBlank()) {
            RerankStageStats stats = new RerankStageStats(ms(t0), cands.size(), 0, null, PROVIDER, model());
            writeLedger(request, stats, "FAIL",
                    RerankReasonCode.RERANK_KEY_MISSING.code(), null, null, t0);
            return new RerankOutcome(List.of(), stats, RerankReasonCode.RERANK_KEY_MISSING.code());
        }

        TextReRankParam param = TextReRankParam.builder()
                .apiKey(apiKey)
                .model(model())
                .query(request.query())
                .documents(cands.stream().map(RerankCandidate::content).toList())
                .topN(request.topN())
                .returnDocuments(false)
                .build();

        String failCode = null;
        String requestId = null;
        TextReRankResult result = null;
        for (int attempt = 0; ; attempt++) {
            try {
                result = callOnce(param);
                failCode = null; // 重试成功后清除上一轮失败码
                break;
            } catch (RemoteCallException e) {
                requestId = e.requestId;
                failCode = e.reasonCode;
                boolean canRetry = e.retryable && attempt < props.getRemote().getRetryMaxAttempts();
                if (!canRetry) {
                    break;
                }
                try {
                    TimeUnit.MILLISECONDS.sleep(props.getRemote().getRetryBackoffMillis());
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        Integer totalTokens = null;
        List<RerankedHit> hits = List.of();
        if (result != null) {
            requestId = result.getRequestId() != null ? result.getRequestId() : requestId;
            if (result.getUsage() != null) {
                totalTokens = result.getUsage().getTotalTokens();
            }
            Parsing p = parse(result, request, cands.size());
            if (p.errorCode != null) {
                failCode = p.errorCode;
                hits = List.of();
            } else {
                hits = p.hits;
            }
        }
        RerankStageStats stats = new RerankStageStats(ms(t0), cands.size(), hits.size(),
                totalTokens, PROVIDER, model());
        boolean ledgerOk = writeLedger(request, stats,
                failCode == null ? "SUCCESS" : "FAIL", failCode, totalTokens, requestId, t0);
        if (!ledgerOk && failCode == null) {
            failCode = RerankReasonCode.RERANK_LEDGER_WRITE_FAILED.code();
        }
        if (!ledgerOk && failCode != null
                && !failCode.equals(RerankReasonCode.RERANK_LEDGER_WRITE_FAILED.code())) {
            // 调用本身已失败，台账失败仅 WARN，不覆盖主原因码
            org.slf4j.LoggerFactory.getLogger(BailianTextReranker.class)
                    .warn("rerank ledger append failed after call failure (reason={})", failCode);
        }
        return new RerankOutcome(hits, stats, failCode);
    }

    /** 解析校验（§6.4：数量一致 / index∈[0,size) 无重复 / score 有限且 [0,1]，违规显式失败）。 */
    private record Parsing(List<RerankedHit> hits, String errorCode) {
    }

    private Parsing parse(TextReRankResult result, RerankRequest request, int candidateCount) {
        var output = result.getOutput();
        var items = output == null ? null : output.getResults();
        if (items == null || items.size() != request.topN()) {
            return new Parsing(List.of(), RerankReasonCode.RERANK_RESULT_COUNT_MISMATCH.code());
        }
        record Indexed(int index, double score) {
        }
        List<Indexed> list = new ArrayList<>(items.size());
        Set<Integer> seen = new HashSet<>();
        for (var item : items) {
            Integer idx = item.getIndex();
            Double score = item.getRelevanceScore();
            if (idx == null || idx < 0 || idx >= candidateCount || !seen.add(idx)) {
                return new Parsing(List.of(), RerankReasonCode.RERANK_ID_MISMATCH.code());
            }
            if (score == null || !Double.isFinite(score) || score < 0.0 || score > 1.0) {
                return new Parsing(List.of(), RerankReasonCode.RERANK_SCORE_INVALID.code());
            }
            list.add(new Indexed(idx, score));
        }
        // 确定性：score DESC → index ASC（原候选序），与适配器输入原序契约一致
        list.sort(Comparator.comparingDouble(Indexed::score).reversed()
                .thenComparingInt(Indexed::index));
        List<RerankedHit> hits = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            Indexed e = list.get(i);
            RerankCandidate c = request.candidates().get(e.index());
            hits.add(new RerankedHit(c.chunkId(), i + 1, c.originalRank(), e.score(), c.cosineScore()));
        }
        return new Parsing(hits, null);
    }

    private static final class RemoteCallException extends Exception {
        final String reasonCode;
        final boolean retryable;
        final String requestId;

        RemoteCallException(String reasonCode, boolean retryable, String requestId, Throwable cause) {
            super(reasonCode, cause);
            this.reasonCode = reasonCode;
            this.retryable = retryable;
            this.requestId = requestId;
        }
    }

    private TextReRankResult callOnce(TextReRankParam param) throws RemoteCallException {
        CompletableFuture<TextReRankResult> fut = CompletableFuture.supplyAsync(() -> {
            try {
                return client.call(param);
            } catch (ApiException e) {
                throw e;
            } catch (NoApiKeyException | InputRequiredException e) {
                throw new IllegalStateException(e);
            }
        }, executor);
        try {
            TextReRankResult res = fut.get(props.getRemote().getTimeoutMillis(), TimeUnit.MILLISECONDS);
            Integer sc = res.getStatusCode();
            if (sc != null && sc != 200) {
                boolean retry = sc == 429 || sc >= 500;
                throw new RemoteCallException(
                        retry ? RerankReasonCode.RERANK_API_RETRYABLE_FAILED.code()
                              : RerankReasonCode.RERANK_API_FAILED.code(),
                        retry, res.getRequestId(), null);
            }
            return res;
        } catch (TimeoutException e) {
            fut.cancel(true);
            throw new RemoteCallException(RerankReasonCode.RERANK_TIMEOUT.code(), false, null, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RemoteCallException(RerankReasonCode.RERANK_TIMEOUT.code(), false, null, e);
        } catch (ExecutionException e) {
            Throwable c = e.getCause();
            if (c instanceof ApiException ae) {
                int sc = ae.getStatus() != null ? ae.getStatus().getStatusCode() : -1;
                String rq = ae.getStatus() != null ? ae.getStatus().getRequestId() : null;
                boolean retry = sc == 429 || sc >= 500 || sc == -1;
                throw new RemoteCallException(
                        retry ? RerankReasonCode.RERANK_API_RETRYABLE_FAILED.code()
                              : RerankReasonCode.RERANK_API_FAILED.code(),
                        retry, rq, ae);
            }
            if (c instanceof IllegalStateException ise
                    && ise.getCause() instanceof NoApiKeyException) {
                throw new RemoteCallException(RerankReasonCode.RERANK_KEY_MISSING.code(), false, null, ise);
            }
            if (c instanceof IllegalStateException ise
                    && ise.getCause() instanceof InputRequiredException) {
                throw new RemoteCallException(RerankReasonCode.RERANK_CONFIG_INVALID.code(), false, null, ise);
            }
            throw new RemoteCallException(RerankReasonCode.RERANK_API_FAILED.code(), false, null, e);
        }
    }

    private boolean writeLedger(RerankRequest request, RerankStageStats stats, String status,
                                String reasonCode, Integer totalTokens, String requestId, long t0) {
        return ledger.write(new RerankLedgerEntry(
                System.currentTimeMillis(),
                request.questionId(),
                null,
                stats.model(),
                status,
                reasonCode,
                totalTokens,
                ms(t0),
                requestId,
                RerankCallLedger.sha256Hex(request.query()),
                request.candidates().size()));
    }

    private static long ms(long startNano) {
        return (System.nanoTime() - startNano) / 1_000_000L;
    }
}