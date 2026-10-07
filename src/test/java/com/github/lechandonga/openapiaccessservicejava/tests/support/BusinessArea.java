package com.github.lechandonga.openapiaccessservicejava.tests.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标注测试类覆盖的业务域，供 BusinessLoggingExtension 在测试日志中分组展示。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface BusinessArea {
    String value();
}
