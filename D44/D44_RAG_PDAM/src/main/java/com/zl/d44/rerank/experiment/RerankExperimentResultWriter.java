package com.zl.d44.rerank.experiment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zl.d44.rerank.experiment.RerankDecisionRecord;
import com.zl.d44.rerank.experiment.RerankExperimentGroup;
import com.zl.d44.rerank.experiment.RerankRunDetail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 实验 4-JSON 落盘（matrix / results / summary / decision；零全文零 Key）。 */
public final class RerankExperimentResultWriter {

    public static final String MATRIX_FILE = "rerank-experiment-matrix.json";
    public static final String RESULTS_FILE = "rerank-results.json";
    public static final String SUMMARY_FILE = "rerank-summary.json";
    public static final String DECISION_FILE = "rerank-decision.json";

    private final ObjectMapper mapper;

    public RerankExperimentResultWriter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public Path write(Path outputDir,
                      List<RerankExperimentGroup> groups,
                      List<RerankRunDetail> runs,
                      List<RerankGroupSummary> summaries,
                      List<RerankDecisionRecord> decisions,
                      Map<String, Object> indexInfo) throws IOException {
        Files.createDirectories(outputDir);
        List<Map<String, Object>> matrix = new ArrayList<>();
        for (RerankExperimentGroup g : groups) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("group", g.name());
            m.put("candidateTopK", g.candidateTopK());
            m.put("finalTopK", g.finalTopK());
            m.put("rerankApplied", g.rerankApplied());
            m.put("provider", g.provider());
            m.put("description", g.description());
            matrix.add(m);
        }
        writeJson(outputDir.resolve(MATRIX_FILE),
                Map.of("groups", matrix, "index", indexInfo));
        writeJson(outputDir.resolve(RESULTS_FILE), runs);
        writeJson(outputDir.resolve(SUMMARY_FILE), summaries);
        writeJson(outputDir.resolve(DECISION_FILE), decisions);
        return outputDir;
    }

    private void writeJson(Path file, Object value) throws IOException {
        String json = mapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(value);
        Files.writeString(file, json + System.lineSeparator(), StandardCharsets.UTF_8);
    }
}