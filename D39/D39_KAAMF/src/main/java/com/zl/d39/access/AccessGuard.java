package com.zl.d39.access;

import com.zl.d39.common.ResultCode;
import com.zl.d39.knowledge.retrieval.RetrievalException;

/**
 * 控制器/服务侧授权辅助（仅 enabled 装配）。
 *
 * <p>拒绝通道：抛 {@link RetrievalException}（4001 + 稳定 AUTH_* 细分码），
 * 由既有 GlobalExceptionHandler 统一 Result 包装——不新增 ResultCode 枚举、
 * 不改异常处理器（H1 白名单外零触碰）。</p>
 */
public class AccessGuard {

    private final AccessPolicyService policyService;

    public AccessGuard(AccessPolicyService policyService) {
        this.policyService = policyService;
    }

    /** 当前请求可信身份；缺失=未经过滤器（异常路径），fail-closed。 */
    public AuthContext require() {
        AuthContext context = AuthContextHolder.get();
        if (context == null) {
            throw new RetrievalException(ResultCode.PARAM_INVALID,
                    "缺少服务端可信身份，拒绝访问", "AUTH_CONTEXT_MISSING");
        }
        return context;
    }

    public AuthContext requireAdmin() {
        AuthContext context = require();
        if (!context.isAdmin()) {
            throw new RetrievalException(ResultCode.PARAM_INVALID,
                    "该操作仅限管理员", "AUTH_FORBIDDEN");
        }
        return context;
    }

    /** 文档级范围校验：越权与“不存在”统一提示，不泄露资源存在性（OWASP 口径）。 */
    public void requireDocumentVisible(String rowTenant, String rowDept, String rowDevice) {
        AuthContext context = require();
        if (!policyService.canReadDocument(context, rowTenant, rowDept, rowDevice)) {
            throw new RetrievalException(ResultCode.PARAM_INVALID,
                    "无权访问该文档", "AUTH_FORBIDDEN");
        }
    }
}
