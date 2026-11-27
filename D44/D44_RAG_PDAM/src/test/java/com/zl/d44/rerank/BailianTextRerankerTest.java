package com.zl.d44.rerank;

import com.alibaba.dashscope.common.Status;
import com.alibaba.dashscope.exception.ApiException;
import com.alibaba.dashscope.rerank.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 远程适配器全失败分支（§6.4/§6.6，fake client 注入，零真实网络，C09/C14）。 */
class BailianTextRerankerTest {

    @TempDir
    Path tempDir;

    private TextReRank client;
    private RerankProperties props;
    private RerankCallLedger ledger;
    private Path ledgerFile;

    @BeforeEach
    void setUp() {
        client = mock(TextReRank.class);
        props = new RerankProperties();
        props.getRemote().setRetryBackoffMillis(1);
        ledgerFile = tempDir.resolve("rerank-ledger.jsonl");
        ledger = new RerankCallLedger(ledgerFile);
    }

    private BailianTextReranker adapter(String apiKey) {
        return new BailianTextReranker(props, client, ledger, () -> apiKey);
    }

    private static RerankCandidate cand(int rank) {
        return new RerankCandidate("c" + rank, "doc" + rank, "text " + rank, rank, 0.5);
    }

    private static RerankRequest request(int size, int topN) {
        List<RerankCandidate> cands = new ArrayList<>();
        for (int i = 1; i <= size; i++) {
            cands.add(cand(i));
        }
        return new RerankRequest("Q01", "alpha query", cands, topN);
    }

    private static TextReRankResult ok(List<TextReRankOutput.Result> results,
                                       Integer tokens, String requestId) {
        TextReRankResult res = mock(TextReRankResult.class);
        when(res.getStatusCode()).thenReturn(200);
        when(res.getRequestId()).thenReturn(requestId);
        TextReRankOutput output = new TextReRankOutput();
        output.setResults(results);
        when(res.getOutput()).thenReturn(output);
        if (tokens != null) {
            TextReRankUsage usage = new TextReRankUsage();
            usage.setTotalTokens(tokens);
            when(res.getUsage()).thenReturn(usage);
        } else {
            when(res.getUsage()).thenReturn(null);
        }
        return res;
    }

    private static TextReRankOutput.Result item(int index, double score) {
        TextReRankOutput.Result r = new TextReRankOutput.Result();
        r.setIndex(index);
        r.setRelevanceScore(score);
        return r;
    }

    private static ApiException apiEx(int status) {
        return new ApiException(Status.builder().statusCode(status)
                .message("err-" + status).code("E").build());
    }

    private long ledgerLines() throws IOException {
        return Files.exists(ledgerFile) ? Files.lines(ledgerFile).count() : 0;
    }

    @Test
    @DisplayName("成功路径：返回序映射 + usage/requestId 透传 + 台账 1 行 SUCCESS")
    void successMappingAndLedger() throws Exception {
        TextReRankResult res = ok(List.of(item(1, 0.9), item(0, 0.5)), 123, "rq-1");
        when(client.call(any(TextReRankParam.class))).thenReturn(res);
        var outcome = adapter("sk-test").rerank(request(3, 2));
        assertEquals(List.of("c2", "c1"),
                outcome.hits().stream().map(RerankedHit::chunkId).toList());
        assertEquals(List.of(1, 2),
                outcome.hits().stream().map(RerankedHit::newRank).toList());
        assertEquals(123, outcome.stats().totalTokens());
        assertEquals("gte-rerank-v2", outcome.stats().model());
        assertEquals("bailian", outcome.stats().provider());
        assertFalse(outcome.failed());
        assertEquals(1, ledgerLines());
        assertTrue(Files.readString(ledgerFile).contains("\"SUCCESS\""));
    }

    @Test
    @DisplayName("score 相同时按候选原序（index ASC）保序")
    void stableTieBreak() throws Exception {
        TextReRankResult res = ok(List.of(item(2, 0.7), item(0, 0.7), item(1, 0.7)), 9, "rq-t");
        when(client.call(any(TextReRankParam.class))).thenReturn(res);
        var outcome = adapter("sk-test").rerank(request(3, 3));
        assertEquals(List.of("c1", "c2", "c3"),
                outcome.hits().stream().map(RerankedHit::chunkId).toList());
    }

    @Test
    @DisplayName("无 Key：KEY_MISSING，零调用，台账 FAIL 行")
    void keyMissingNoCall() throws Exception {
        var outcome = adapter(null).rerank(request(3, 2));
        assertEquals(RerankReasonCode.RERANK_KEY_MISSING.code(), outcome.reasonCode());
        assertTrue(outcome.failed());
        verify(client, never()).call(any(TextReRankParam.class));
        assertEquals(1, ledgerLines());
    }

    @Test
    @DisplayName("空候选：EMPTY_CANDIDATES 零调用")
    void emptyCandidates() throws Exception {
        var outcome = adapter("sk-test").rerank(request(0, 5));
        assertEquals(RerankReasonCode.RERANK_EMPTY_CANDIDATES.code(), outcome.reasonCode());
        assertFalse(outcome.failed());
        verify(client, never()).call(any(TextReRankParam.class));
    }

    @Test
    @DisplayName("4xx 不重试 → API_FAILED（times=1）")
    void clientErrorNoRetry() throws Exception {
        when(client.call(any(TextReRankParam.class))).thenThrow(apiEx(400));
        var outcome = adapter("sk-test").rerank(request(3, 2));
        assertEquals(RerankReasonCode.RERANK_API_FAILED.code(), outcome.reasonCode());
        verify(client, times(1)).call(any(TextReRankParam.class));
    }

    @Test
    @DisplayName("429 重试 1 次成功（times=2，最终 SUCCESS）")
    void rateLimitRetrySuccess() throws Exception {
        TextReRankResult res = ok(List.of(item(0, 0.9), item(1, 0.8)), 10, "rq-r");
        when(client.call(any(TextReRankParam.class)))
                .thenThrow(apiEx(429))
                .thenReturn(res);
        var outcome = adapter("sk-test").rerank(request(2, 2));
        assertFalse(outcome.failed());
        verify(client, times(2)).call(any(TextReRankParam.class));
    }

    @Test
    @DisplayName("5xx 重试耗尽 → API_RETRYABLE_FAILED（times=2）")
    void serverErrorExhausted() throws Exception {
        when(client.call(any(TextReRankParam.class))).thenThrow(apiEx(503));
        var outcome = adapter("sk-test").rerank(request(3, 2));
        assertEquals(RerankReasonCode.RERANK_API_RETRYABLE_FAILED.code(), outcome.reasonCode());
        verify(client, times(2)).call(any(TextReRankParam.class));
    }

    @Test
    @DisplayName("响应体 statusCode=429（非异常通道）：可重试分类")
    void bodyStatusCodeRetryable() throws Exception {
        TextReRankResult busy = mock(TextReRankResult.class);
        when(busy.getStatusCode()).thenReturn(429);
        when(client.call(any(TextReRankParam.class))).thenReturn(busy);
        var outcome = adapter("sk-test").rerank(request(3, 2));
        assertEquals(RerankReasonCode.RERANK_API_RETRYABLE_FAILED.code(), outcome.reasonCode());
        verify(client, times(2)).call(any(TextReRankParam.class));
    }

    @Test
    @DisplayName("结果数不符 → RESULT_COUNT_MISMATCH")
    void countMismatch() throws Exception {
        TextReRankResult res = ok(List.of(item(0, 0.9)), 5, "rq-n");
        when(client.call(any(TextReRankParam.class))).thenReturn(res);
        var outcome = adapter("sk-test").rerank(request(3, 2));
        assertEquals(RerankReasonCode.RERANK_RESULT_COUNT_MISMATCH.code(), outcome.reasonCode());
    }

    @Test
    @DisplayName("index 越界与重复 → ID_MISMATCH")
    void idMismatch() throws Exception {
        TextReRankResult resB = ok(List.of(item(9, 0.9), item(0, 0.5)), 5, "rq-b");
        when(client.call(any(TextReRankParam.class))).thenReturn(resB);
        assertEquals(RerankReasonCode.RERANK_ID_MISMATCH.code(),
                adapter("sk-test").rerank(request(3, 2)).reasonCode());
        TextReRankResult resD = ok(List.of(item(0, 0.9), item(0, 0.5)), 5, "rq-d");
        when(client.call(any(TextReRankParam.class))).thenReturn(resD);
        assertEquals(RerankReasonCode.RERANK_ID_MISMATCH.code(),
                adapter("sk-test").rerank(request(3, 2)).reasonCode());
    }

    @Test
    @DisplayName("score 越界/非有限 → SCORE_INVALID")
    void scoreInvalid() throws Exception {
        TextReRankResult resS = ok(List.of(item(0, 1.5), item(1, 0.5)), 5, "rq-s");
        when(client.call(any(TextReRankParam.class))).thenReturn(resS);
        assertEquals(RerankReasonCode.RERANK_SCORE_INVALID.code(),
                adapter("sk-test").rerank(request(3, 2)).reasonCode());
    }

    @Test
    @DisplayName("超时预算 → RERANK_TIMEOUT 不重试")
    void timeout() throws Exception {
        props.getRemote().setTimeoutMillis(60);
        when(client.call(any(TextReRankParam.class))).thenAnswer(inv -> {
            Thread.sleep(400);
            return ok(List.of(), 0, "rq-late");
        });
        var outcome = adapter("sk-test").rerank(request(3, 2));
        assertEquals(RerankReasonCode.RERANK_TIMEOUT.code(), outcome.reasonCode());
        verify(client, times(1)).call(any(TextReRankParam.class));
    }

    @Test
    @DisplayName("台账不可写：成功调用被标记 LEDGER_WRITE_FAILED（不静默）")
    void ledgerWriteFailure() throws Exception {
        Path blocker = tempDir.resolve("blocker.txt");
        Files.writeString(blocker, "x");
        RerankCallLedger badLedger = new RerankCallLedger(
                blocker.resolve("nested-ledger.jsonl"));
        BailianTextReranker adapter = new BailianTextReranker(props, client, badLedger,
                () -> "sk-test");
        TextReRankResult resW = ok(List.of(item(0, 0.9), item(1, 0.5)), 7, "rq-w");
        when(client.call(any(TextReRankParam.class))).thenReturn(resW);
        var outcome = adapter.rerank(request(2, 2));
        assertEquals(RerankReasonCode.RERANK_LEDGER_WRITE_FAILED.code(), outcome.reasonCode());
    }
}