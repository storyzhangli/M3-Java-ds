package com.zl.d30.knowledge.document;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

import java.util.ArrayList;
import java.util.List;

/**
 * 知识文档导入的业务层限制。
 */
@Data
@Validated
@ConfigurationProperties(prefix = "app.knowledge.import")
public class KnowledgeImportProperties {

    private DataSize maxFileSize = DataSize.ofMegabytes(5);

    @Min(1)
    private int maxParsedChars = 2_000_000;

    @Min(1)
    private int maxPdfPages = 2_000;

    @NotEmpty
    private List<String> allowedExtensions = new ArrayList<>(List.of(
            "txt", "md", "markdown", "html", "htm", "pdf", "docx"));

    @AssertTrue(message = "知识文档最大文件大小必须大于 0")
    public boolean isMaxFileSizePositive() {
        return maxFileSize != null && !maxFileSize.isNegative() && maxFileSize.toBytes() > 0;
    }
}
