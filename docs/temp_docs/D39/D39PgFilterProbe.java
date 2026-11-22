import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.ai.vectorstore.filter.FilterExpressionTextParser;
import org.springframework.ai.vectorstore.pgvector.PgVectorFilterExpressionConverter;

import java.util.List;
import java.util.Map;

/** D39 探针：实证 PgVectorFilterExpressionConverter 对权限表达式树的 SQL 渲染。 */
public class D39PgFilterProbe {
    public static void main(String[] args) {
        FilterExpressionBuilder b = new FilterExpressionBuilder();
        // userA 表达式（与 AccessPolicyService.vectorFilterOp 同构）
        FilterExpressionBuilder.Op tenantEq = b.eq("tenantId", "T1");
        FilterExpressionBuilder.Op deptPart = b.group(b.or(b.eq("deptId", "D1"), b.eq("deptId", "ALL")));
        FilterExpressionBuilder.Op devicePart = b.group(b.or(b.in("deviceId", new Object[]{"DEV-A"}), b.eq("deviceId", "ALL")));
        Filter.Expression userA = b.and(b.and(tenantEq, deptPart), devicePart).build();

        PgVectorFilterExpressionConverter conv = new PgVectorFilterExpressionConverter();
        System.out.println("USERA_SQL   = " + conv.convertExpression(userA));

        // ADMIN 表达式
        Filter.Expression admin = b.eq("tenantId", "T1").build();
        System.out.println("ADMIN_SQL   = " + conv.convertExpression(admin));

        // 文本解析路径对照（Spring AI 文本 DSL 同款表达式）
        FilterExpressionTextParser tp = new FilterExpressionTextParser();
        Filter.Expression parsed = tp.parse("tenantId == 'T1' && (deptId == 'D1' || deptId == 'ALL') && (deviceId IN ['DEV-A','ALL'] || deviceId == 'ALL')");
        System.out.println("PARSED_TREE = " + parsed);
        System.out.println("PARSED_SQL  = " + conv.convertExpression(parsed));

        // 单独 OR（无外层 AND 包裹）
        Filter.Expression alone = b.or(b.eq("deptId", "D1"), b.eq("deptId", "ALL")).build();
        System.out.println("OR_ALONE    = " + conv.convertExpression(alone));
    }
}



