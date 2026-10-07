package com.github.lechandonga.openapiaccessservicejava.support;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Prints a readable, business-grouped test log to stdout:
 * <pre>
 * [业务] 鉴权与密钥状态
 *   ✓ missing_key_is_rejected (12 ms)
 * </pre>
 * so that `mvn test` output shows which business areas and cases are covered.
 */
public class BusinessLoggingExtension
        implements BeforeAllCallback, AfterAllCallback, BeforeEachCallback, AfterEachCallback {

    @Override
    public void beforeAll(ExtensionContext context) {
        String business = businessName(context);
        System.out.println();
        System.out.println("====== [业务] " + business + " ======");
    }

    @Override
    public void afterAll(ExtensionContext context) {
        System.out.println("------ [业务] " + businessName(context) + " 结束 ------");
        System.out.println();
    }

    @Override
    public void beforeEach(ExtensionContext context) {
        context.getStore(ExtensionContext.Namespace.GLOBAL).put("start", System.currentTimeMillis());
        System.out.println("  -> 用例开始: " + caseName(context) + " — " + displayCase(context));
    }

    @Override
    public void afterEach(ExtensionContext context) {
        boolean failed = context.getExecutionException().isPresent();
        long elapsed = context.getStore(ExtensionContext.Namespace.GLOBAL)
                .getOrDefault("start", long.class, System.currentTimeMillis());
        long took = System.currentTimeMillis() - elapsed;
        System.out.printf("    %s %s (%d ms)%n",
                failed ? "✗ 失败:" : "✓ 通过:", caseName(context), took);
    }

    private String businessName(ExtensionContext context) {
        Class<?> testClass = context.getRequiredTestClass();
        BusinessArea area = testClass.getAnnotation(BusinessArea.class);
        return area == null ? testClass.getSimpleName() : area.value();
    }

    private String caseName(ExtensionContext context) {
        return context.getRequiredTestMethod().getName();
    }

    private String displayCase(ExtensionContext context) {
        BusinessCase annotation = context.getRequiredTestMethod().getAnnotation(BusinessCase.class);
        return annotation == null ? "" : annotation.value();
    }
}
