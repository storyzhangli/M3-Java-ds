package com.zl.d26.knowledge.document;

import com.zl.d26.common.ResultCode;
import com.zl.d26.knowledge.document.repository.KnowledgeDocumentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeDocumentFileReadFailureTest {

    @Test
    void readExceptionShouldMarkDocumentFailed() {
        KnowledgeDocumentRepository repository = mock(KnowledgeDocumentRepository.class);
        KnowledgeDocument document = new KnowledgeDocument();
        document.setDocumentId("read-failure-id");
        document.setStatus(KnowledgeDocumentStatus.PENDING);
        when(repository.findByDocumentId("read-failure-id")).thenReturn(Optional.of(document));

        MockMultipartFile file = new MockMultipartFile(
                "file", "read.txt", "text/plain", "placeholder".getBytes()) {
            @Override
            public byte[] getBytes() throws IOException {
                throw new IOException("simulated read failure");
            }
        };
        KnowledgeDocumentService service = new KnowledgeDocumentService(
                repository, new KnowledgeDocumentParser(new KnowledgeImportProperties()));

        assertThatThrownBy(() -> service.importFile("read-failure-id", file))
                .isInstanceOf(KnowledgeDocumentException.class)
                .extracting(ex -> ((KnowledgeDocumentException) ex).getResultCode())
                .isEqualTo(ResultCode.DOCUMENT_FILE_INVALID);
        verify(repository).fail(eq("read-failure-id"),
                eq(ResultCode.DOCUMENT_FILE_INVALID.name()), any(), any());
    }
}
