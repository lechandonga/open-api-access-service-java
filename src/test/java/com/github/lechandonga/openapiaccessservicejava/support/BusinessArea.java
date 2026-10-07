package com.github.lechandonga.openapiaccessservicejava.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.extension.ExtendWith;

/** Marks a test class as covering one business area and enables business logging. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(BusinessLoggingExtension.class)
public @interface BusinessArea {
    String value();
}
