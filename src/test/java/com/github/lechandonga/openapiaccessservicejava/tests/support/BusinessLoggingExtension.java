package com.github.lechandonga.openapiaccessservicejava.tests.support;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * 让单测日志明确呈现「覆盖了哪些业务域、各业务域有哪些用例、每个用例的结果」。
 * 业务域通过测试类上的 {@link BusinessArea} 声明；所有测试类执行完后由关闭钩子输出总结表。
 */
public class BusinessLoggingExtension implements BeforeAllCallback, BeforeEachCallback,
        AfterEachCallback, AfterAllCallback {

    private static final Map<String, List<String>> CASES = new LinkedHashMap<>();
    private static final Map<String, AtomicInteger> PASSED = new LinkedHashMap<>();
    private static final Map<String, AtomicInteger> FAILED = new LinkedHashMap<>();
    private static boolean hookRegistered = false;

    private String area(ExtensionContext context) {
        return context.getRequiredTestClass().getAnnotation(BusinessArea.class).value();
    }

    @Override
    public void beforeAll(ExtensionContext context) {
        registerSummaryHookOnce();
        String area = area(context);
        CASES.computeIfAbsent(area, a -> new ArrayList<>());
        PASSED.computeIfAbsent(area, a -> new AtomicInteger());
        FAILED.computeIfAbsent(area, a -> new AtomicInteger());
        System.out.println();
        System.out.println("==================== 业务域: " + area + " ====================");
    }

    @Override
    public void beforeEach(ExtensionContext context) {
        String area = area(context);
        CASES.get(area).add(context.getDisplayName());
        System.out.println("[用例] " + area + " >> " + context.getDisplayName() + " ... 开始");
    }

    @Override
    public void afterEach(ExtensionContext context) {
        String area = area(context);
        boolean ok = context.getExecutionException().isEmpty();
        if (ok) {
            PASSED.get(area).incrementAndGet();
            System.out.println("[结果] " + area + " >> " + context.getDisplayName() + " ... 通过");
        } else {
            FAILED.get(area).incrementAndGet();
            System.out.println("[结果] " + area + " >> " + context.getDisplayName() + " ... 失败");
        }
    }

    @Override
    public void afterAll(ExtensionContext context) {
        String area = area(context);
        int total = CASES.get(area).size();
        int failed = FAILED.get(area).get();
        System.out.println("-------------------- " + area + " 小计: "
                + (total - failed) + " 通过 / " + failed + " 失败，共 " + total + " 个用例");
    }

    private static synchronized void registerSummaryHookOnce() {
        if (hookRegistered) {
            return;
        }
        hookRegistered = true;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println();
            System.out.println("==================== 业务覆盖总结 ====================");
            CASES.forEach((area, cases) -> {
                int p = PASSED.get(area).get();
                int f = FAILED.get(area).get();
                System.out.println("业务域[" + area + "] 用例数=" + cases.size()
                        + " 通过=" + p + " 失败=" + f);
                cases.forEach(c -> System.out.println("    - " + c));
            });
            System.out.println("======================================================");
        }));
    }
}
