package com.loadtest.app.testsupport;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;

public final class ReflectionTestSupport {

    private ReflectionTestSupport() {
    }

    public static Object newInstance(Class<?> type) {
        try {
            Constructor<?> ctor = type.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("Failed to construct " + type.getName(), ex);
        }
    }

    public static void setField(Field field, Object target, Object value) {
        try {
            field.set(target, value);
        } catch (IllegalAccessException ex) {
            throw new AssertionError("Failed to set " + field.getName(), ex);
        }
    }

    public static Object getField(Field field, Object target) {
        try {
            return field.get(target);
        } catch (IllegalAccessException ex) {
            throw new AssertionError("Failed to read " + field.getName(), ex);
        }
    }
}
