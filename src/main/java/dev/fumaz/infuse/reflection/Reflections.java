package dev.fumaz.infuse.reflection;

import com.google.common.collect.ImmutableSet;
import com.google.common.reflect.ClassPath;

import java.lang.reflect.*;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@SuppressWarnings({"UnstableApiUsage", "unchecked"})
public final class Reflections {

    private Reflections() {
        throw new UnsupportedOperationException("This class cannot be instantiated");
    }

    public static <T> T construct(Class<T> clazz, Object... parameters) {
        try {
            Class<?>[] parameterTypes = Arrays.stream(parameters)
                    .map(Object::getClass)
                    .toArray(Class<?>[]::new);

            Constructor<T> constructor = Reflections.getSuitableConstructor(clazz, parameterTypes, parameters);

            if (constructor == null) {
                throw new NoSuchMethodException("Couldn't find constructor for given parameters");
            }

            constructor.setAccessible(true);
            return constructor.newInstance(parameters);
        } catch (InvocationTargetException | NoSuchMethodException e) {
            throw new ReflectionException("Exception whilst fetching the method", e);
        } catch (IllegalAccessException | InstantiationException e) {
            throw new ReflectionException("Exception whilst instantiating the object", e);
        }
    }

    public static <T> T construct(Class<T> clazz, Map<Class<?>, Object> arguments) {
        try {
            Class<?>[] parameterTypes = arguments.keySet().toArray(new Class<?>[0]);
            Object[] parameters = arguments.values().toArray();

            Constructor<T> constructor = Reflections.getSuitableConstructor(clazz, parameterTypes, parameters);

            if (constructor == null) {
                throw new NoSuchMethodException("Couldn't find constructor for given parameters");
            }

            constructor.setAccessible(true);
            return constructor.newInstance(parameters);
        } catch (InvocationTargetException | NoSuchMethodException e) {
            throw new ReflectionException("Exception whilst fetching the method", e);
        } catch (IllegalAccessException | InstantiationException e) {
            throw new ReflectionException("Exception whilst instantiating the object", e);
        }
    }

    public static <T> T construct(Class<T> clazz) {
        try {
            return clazz.newInstance();
        } catch (IllegalAccessException | InstantiationException e) {
            throw new ReflectionException("Exception whilst instantiating the object", e);
        }
    }

    public static Set<Class<?>> getClassesInPackage(ClassLoader classLoader, String pkg, boolean recursive) {
        try {
            ClassPath path = ClassPath.from(classLoader);
            ImmutableSet<ClassPath.ClassInfo> classes = recursive ? path.getTopLevelClassesRecursive(pkg) : path.getTopLevelClasses(pkg);

            return classes.stream()
                    .map(ClassPath.ClassInfo::load)
                    .collect(Collectors.toSet());
        } catch (Exception e) {
            throw new ReflectionException("Exception whilst fetching classes", e);
        }
    }

    public static <T> Set<Class<? extends T>> getMatchingClassesInPackage(ClassLoader classLoader, String pkg, Class<T> type, boolean recursive) {
        Set<Class<?>> classes = Reflections.getClassesInPackage(classLoader, pkg, recursive);

        return classes.stream()
                .filter(type::isAssignableFrom)
                .map(clazz -> (Class<? extends T>) clazz)
                .collect(Collectors.toSet());
    }

    public static <T> Set<Class<? extends T>> getConcreteClassesInPackage(ClassLoader classLoader, String pkg, Class<T> type, boolean recursive) {
        Set<Class<? extends T>> classes = Reflections.getMatchingClassesInPackage(classLoader, pkg, type, recursive);

        return classes.stream()
                .filter(clazz -> !Modifier.isAbstract(clazz.getModifiers()))
                .collect(Collectors.toSet());
    }

    public static void consume(ClassLoader classLoader, String pkg, boolean recursive, Consumer<Class<?>> consumer) {
        Reflections.getClassesInPackage(classLoader, pkg, recursive).forEach(consumer);
    }

    public static <T> void consume(ClassLoader classLoader, String pkg, Class<T> type, boolean recursive, Consumer<Class<? extends T>> consumer) {
        Reflections.getMatchingClassesInPackage(classLoader, pkg, type, recursive).forEach(consumer);
    }

    public static <T> void consumeConcrete(ClassLoader classLoader, String pkg, Class<T> type, boolean recursive, Consumer<Class<? extends T>> consumer) {
        Reflections.getConcreteClassesInPackage(classLoader, pkg, type, recursive).forEach(consumer);
    }

    public static <T> void consumeInstance(ClassLoader classLoader, String pkg, Class<T> type, boolean recursive, Consumer<T> consumer) {
        Set<Class<? extends T>> classes = Reflections.getConcreteClassesInPackage(classLoader, pkg, type, recursive);

        classes.forEach(clazz -> consumer.accept(Reflections.construct(clazz)));
    }

    public static <T> void consumeInstance(ClassLoader classLoader, String pkg, Class<T> type, boolean recursive, Consumer<T> consumer, Object... args) {
        Set<Class<? extends T>> classes = Reflections.getConcreteClassesInPackage(classLoader, pkg, type, recursive);

        classes.forEach(clazz -> consumer.accept(Reflections.construct(clazz, args)));
    }

    public static Field getField(Class<?> clazz, String name) {
        try {
            Field field = clazz.getDeclaredField(name);
            field.setAccessible(true);

            return field;
        } catch (NoSuchFieldException e) {
            throw new ReflectionException("Exception whilst getting the field", e);
        }
    }

    public static Field getField(Object object, String name) {
        return getField(object.getClass(), name);
    }

    public static <T> T getFieldValue(Object object, Field field) {
        try {
            return (T) field.get(object);
        } catch (IllegalAccessException e) {
            throw new ReflectionException("Exception whilst getting the field's value", e);
        }
    }

    public static <T> T getFieldValue(Object object, String name) {
        Field field = Reflections.getField(object, name);

        return getFieldValue(object, field);
    }

    public static void setField(Object object, Field field, Object value) {
        try {
            field.set(object, value);
        } catch (IllegalAccessException e) {
            throw new ReflectionException("Exception whilst setting the value of the field", e);
        }
    }

    public static void setField(Object object, String name, Object value) {
        Field field = getField(object, name);

        setField(object, field, value);
    }

    public static Method getMethod(Class<?> clazz, String name) {
        return Arrays.stream(clazz.getDeclaredMethods())
                .filter(method -> method.getName().equals(name))
                .findFirst()
                .orElse(null);
    }

    public static Method getMethod(Object instance, String name) {
        return getMethod(instance.getClass(), name);
    }

    public static void invokeMethod(Object instance, Method method, Object... arguments) {
        try {
            method.invoke(instance, arguments);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new ReflectionException("Exception whilst invoking the method", e);
        }
    }

    public static void invokeMethod(Object instance, String name, Object... arguments) {
        Method method = getMethod(instance, name);
        invokeMethod(instance, method, arguments);
    }

    private static <T> Constructor<T> getSuitableConstructor(Class<T> clazz, Class<?>[] parameterTypes, Object[] parameters) {
        for (Constructor<?> declared : clazz.getDeclaredConstructors()) {
            if (!checkParameters(declared, parameterTypes)) {
                continue;
            }

            return (Constructor<T>) declared;
        }

        return null;
    }

    private static boolean checkParameters(Executable executable, Class<?>[] parameterTypes) {
        Class<?>[] executableParameterTypes = executable.getParameterTypes();

        // TODO: Add check for varargs
        if (executableParameterTypes.length != parameterTypes.length) {
            return false;
        }

        for (int i = 0; i < parameterTypes.length; i++) {
            if (!parameterTypes[i].isAssignableFrom(executableParameterTypes[i])) {
                return false;
            }
        }

        return true;
    }

}
