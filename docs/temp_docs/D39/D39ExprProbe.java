import com.zl.d39.access.AccessPolicyService;
import com.zl.d39.access.AuthContext;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.converter.PrintFilterExpressionConverter;

import java.util.Set;

public class D39ExprProbe {
    static void dump(Filter.Operand o, int d) {
        String pad = "  ".repeat(d);
        if (o instanceof Filter.Group g) {
            System.out.println(pad + "Group type=" + g.content().type());
            dump(g.content(), d + 1);
        } else if (o instanceof Filter.Expression e) {
            System.out.println(pad + "Expression type=" + e.type());
            System.out.println(pad + "  left=" + e.left().getClass().getSimpleName() + " right="
                    + e.right().getClass().getSimpleName());
            if (e.left() instanceof Filter.Key k) {
                System.out.println(pad + "  key=" + k.key());
            }
            if (e.right() instanceof Filter.Value v) {
                System.out.println(pad + "  value=" + v.value() + " ("
                        + (v.value() == null ? "null" : v.value().getClass().getSimpleName()) + ")");
            }
            if (e.type() == Filter.ExpressionType.AND || e.type() == Filter.ExpressionType.OR) {
                dump(e.left(), d + 1);
                dump(e.right(), d + 1);
            }
        } else {
            System.out.println(pad + "Other: " + o.getClass() + " -> " + o);
        }
    }

    public static void main(String[] args) {
        AccessPolicyService policy = new AccessPolicyService();
        AuthContext user = new AuthContext("userA", "T1", "D1", Set.of("DEV-A"), AuthContext.ROLE_USER);
        Filter.Expression expr = policy.vectorFilter(user);
        System.out.println("TEXT=" + new PrintFilterExpressionConverter().convertExpression(expr));
        dump(expr, 0);
    }
}
