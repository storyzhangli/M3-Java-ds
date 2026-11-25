package com.zl.d42.knowledge.vectorstore;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 受控故障注入装饰器（R01，§3.5 KP3/H5 冻结）。
 *
 * <p>语义：包装真实 {@link VectorStore} Bean（非 JdbcTemplate——PgVectorAccess 非通用
 * Bean，包装 JdbcTemplate 会触发自动配置退让）；按方法独立计数，第 N 次（从 1 起）
 * add/delete 调用抛受控 RuntimeException，消息含 "simulated connection loss"
 * 使状态机归因 PG_UNREACHABLE；其余调用透传。PG 本体真实不动，失败点在客户端
 * 边界≡连接丢失。</p>
 *
 * <p>装配门禁：仅 {@code app.vector-index.fault-injection.enabled=true}（默认 false，
 * 主 profile 永不装配，仅测试显式开启）；经 @Primary 覆盖注入点，真实 Bean 以
 * @Qualifier("pgVectorStore") 显式取，避免多候选装配歧义（D38 教训）。</p>
 */
public class FaultInjectingVectorStore implements VectorStore {

    private final VectorStore delegate;

    private final long failAddCall;

    private final long failDeleteCall;

    private final AtomicLong addCalls = new AtomicLong();

    private final AtomicLong deleteCalls = new AtomicLong();

    public FaultInjectingVectorStore(VectorStore delegate, long failAddCall, long failDeleteCall) {
        this.delegate = delegate;
        this.failAddCall = failAddCall;
        this.failDeleteCall = failDeleteCall;
    }

    @Override
    public void add(List<Document> documents) {
        long call = addCalls.incrementAndGet();
        if (failAddCall > 0 && call == failAddCall) {
            throw new RuntimeException(
                    "INJECTED simulated connection loss on vectorStore.add call #" + call
                            + " (fault-injection, R01)");
        }
        delegate.add(documents);
    }

    @Override
    public void delete(List<String> idList) {
        long call = deleteCalls.incrementAndGet();
        if (failDeleteCall > 0 && call == failDeleteCall) {
            throw new RuntimeException(
                    "INJECTED simulated connection loss on vectorStore.delete call #" + call
                            + " (fault-injection, R01)");
        }
        delegate.delete(idList);
    }

    @Override
    public void delete(Filter.Expression filterExpression) {
        delegate.delete(filterExpression);
    }

    @Override
    public List<Document> similaritySearch(SearchRequest request) {
        return delegate.similaritySearch(request);
    }

    @Override
    public String getName() {
        return delegate.getName();
    }

    /** 测试与证据脚本用：当前累计调用数（add/delete 分离）。 */
    public long addCallCount() {
        return addCalls.get();
    }

    public long deleteCallCount() {
        return deleteCalls.get();
    }
}
