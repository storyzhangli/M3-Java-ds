package com.zl.d35.rag.service;

import com.zl.d35.knowledge.vectorindex.repository.VectorChunkRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 引用存在性核验单测（D35-009）：删除竞态 + 批量核验避免 N+1。
 */
class CitationExistenceValidatorTest {

    @Test
    @DisplayName("全部存在：核验通过，无缺失")
    void allExistingPasses() {
        VectorChunkRepository repository = mock(VectorChunkRepository.class);
        when(repository.findExistingChunkIds(anyCollection()))
                .thenReturn(List.of("c1", "c2", "c3"));
        CitationExistenceValidator validator = new CitationExistenceValidator(repository);

        CitationExistenceValidator.ExistenceResult result = validator.validate(
                List.of("c1", "c2", "c3"));
        assertThat(result.hasMissing()).isFalse();
    }

    @Test
    @DisplayName("部分缺失：返回缺失 chunkId（删除竞态）")
    void partialMissingDetected() {
        VectorChunkRepository repository = mock(VectorChunkRepository.class);
        when(repository.findExistingChunkIds(anyCollection()))
                .thenReturn(List.of("c1"));
        CitationExistenceValidator validator = new CitationExistenceValidator(repository);

        CitationExistenceValidator.ExistenceResult result = validator.validate(
                List.of("c1", "c2", "c3"));
        assertThat(result.hasMissing()).isTrue();
        assertThat(result.missingChunkIds()).containsExactly("c2", "c3");
    }

    @Test
    @DisplayName("空输入：核验通过")
    void emptyInputPasses() {
        VectorChunkRepository repository = mock(VectorChunkRepository.class);
        CitationExistenceValidator validator = new CitationExistenceValidator(repository);

        assertThat(validator.validate(null).hasMissing()).isFalse();
        assertThat(validator.validate(List.of()).hasMissing()).isFalse();
    }

    @Test
    @DisplayName("去重后批量核验：重复 chunkId 只核验一次")
    void duplicatesDeduplicated() {
        VectorChunkRepository repository = mock(VectorChunkRepository.class);
        when(repository.findExistingChunkIds(anyCollection())).thenReturn(List.of("c1"));
        CitationExistenceValidator validator = new CitationExistenceValidator(repository);

        CitationExistenceValidator.ExistenceResult result = validator.validate(
                List.of("c1", "c1", "c2"));
        assertThat(result.missingChunkIds()).containsExactly("c2");
    }
}
