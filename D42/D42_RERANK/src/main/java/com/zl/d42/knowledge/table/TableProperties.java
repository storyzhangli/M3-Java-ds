package com.zl.d42.knowledge.table;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 表格抽取分支配置（P1，H2 冻结默认值：enabled=true 功能本体、ocr.enabled=false 可选分支）。
 *
 * <p>与既有解析/切分/向量配置完全隔离：本分支仅控制表格结构化轨，
 * 关闭时全部表格 Bean 缺席、导入钩子零差异（H1）。</p>
 */
@Component
@ConfigurationProperties(prefix = "app.knowledge.table")
public class TableProperties {

    /** 表格抽取独立分支总开关（默认 true；遗留无表格语料零影响）。 */
    private boolean enabled = true;

    /** OCR 可选分支（默认 false：无引擎，仅接口占位 + 降级标注）。 */
    private final Ocr ocr = new Ocr();

    public static class Ocr {
        private boolean enabled = false;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Ocr getOcr() {
        return ocr;
    }
}
