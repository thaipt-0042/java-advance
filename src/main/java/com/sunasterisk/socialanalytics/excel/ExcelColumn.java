package com.sunasterisk.socialanalytics.excel;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Binds a record component to the Excel column with this header name (matched ignoring case and surrounding
 * spaces). Every component of a row record read by {@link ExcelReader} must have it.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.RECORD_COMPONENT, ElementType.FIELD})
public @interface ExcelColumn {

    /** Header text of the column. */
    String name();

    /**
     * The header must contain this column; otherwise the whole file is rejected. Whether a cell may be empty is
     * decided by Bean Validation on the component, not by this flag.
     */
    boolean required() default false;
}
