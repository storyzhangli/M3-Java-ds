package com.zl.d43.rerank;

/** 可插拔重排端口（D1 裁决：新包新接口，主链路零感知）。 */
public interface RerankPort {

    RerankOutcome rerank(RerankRequest request);

    /** local / bailian，台账与统计的 provider 口径。 */
    String provider();
}