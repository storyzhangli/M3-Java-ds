package com.zl.d42.rerank.experiment;

/** 冻结对照矩阵 R0–R3（§6.3；finalTopK 恒 5，唯一变量为候选深度与重排器）。 */
public enum RerankExperimentGroup {

    R0(5, false, "none", "基线：候选5、无重排"),
    R1(5, true, "local", "候选5、本地词面重排"),
    R2(20, true, "local", "候选20、本地词面重排"),
    R3(20, true, "bailian", "候选20、百炼远程重排");

    private final int candidateTopK;
    private final boolean rerankApplied;
    private final String provider;
    private final String description;

    RerankExperimentGroup(int candidateTopK, boolean rerankApplied,
                          String provider, String description) {
        this.candidateTopK = candidateTopK;
        this.rerankApplied = rerankApplied;
        this.provider = provider;
        this.description = description;
    }

    public int candidateTopK() { return candidateTopK; }
    public boolean rerankApplied() { return rerankApplied; }
    public String provider() { return provider; }
    public String description() { return description; }

    /** 冻结 finalTopK=5。 */
    public int finalTopK() {
        return 5;
    }
}