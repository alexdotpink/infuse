package dev.fumaz.infuse.reflection;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.*;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.Consumer;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

@SuppressWarnings({"unchecked"})
public final class Reflections {

    private Reflections() {
        throw new UnsupportedOperationException("This class cannot be instantiated");
    }

    public static <T> T construct(Class<T> clazz, Object... parameters) {
        return construct(clazz, Arrays.stream(parameters).map(Object::getClass).toArray(Class<?>[]::new), parameters);
    }

    public static <T> T construct(Class<T> clazz, Map<Class<?>, Object> arguments) {
        return construct(clazz, arguments.keySet().toArray(new Class<?>[0]), arguments.values().toArray());
    }

    private static <T> T construct(Class<T> clazz, Class<?>[] parameterTypes, Object... parameters) {
        try {
            Constructor<T> constructor = getSuitableConstructor(clazz, parameterTypes);
            checkConstructor(constructor);
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

    public static Set<Class<?>> getClassesInPackage(ClassLoader classLoader, String pkgName, boolean recursive) {
        String path = pkgName.replace('.', '/');
        Enumeration<URL> resources;

        try {
            resources = classLoader.getResources(path);
        } catch (IOException e) {
            throw new RuntimeException("Could not read package: " + pkgName, e);
        }

        Set<Class<?>> classes = new HashSet<>();

        while (resources.hasMoreElements()) {
            URL resource = resources.nextElement();

            try {
                if (resource.getProtocol().equalsIgnoreCase("file")) {
                    classes.addAll(findClassesInPath(new File(resource.toURI()).getAbsolutePath(), pkgName, recursive));
                } else if (resource.getProtocol().equalsIgnoreCase("jar")) {
                    String jarPath = resource.getPath().substring(5, resource.getPath().indexOf("!"));
                    classes.addAll(findClassesInJar(jarPath, path, pkgName, recursive));
                }
            } catch (Exception e) {
                throw new RuntimeException("Could not get classes for package: " + pkgName, e);
            }
        }

        return classes;
    }

    private static Set<Class<?>> findClassesInPath(String pkgPath, String packageName, boolean recursive)
            throws ClassNotFoundException {
        Path directory = Paths.get(pkgPath);

        if (!Files.exists(directory)) {
            return Collections.emptySet();
        }

        Set<Class<?>> classes = new HashSet<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path file : stream) {
                if (recursive && Files.isDirectory(file)) {
                    assert !Files.isSymbolicLink(file);
                    classes.addAll(findClassesInPath(file.toString(), packageName + "." + file.getFileName(), recursive));
                } else if (file.toString().endsWith(".class")) {
                    String relativePath = directory.relativize(file).toString();
                    String className = relativePath.substring(0, relativePath.lastIndexOf('.')).replace('/', '.');

                    try {
                        Class<?> clazz = Class.forName(packageName + "." + className);
                        classes.add(clazz);
                    } catch (NoClassDefFoundError e) {
                    }
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(packageName + ": unable to read classes", e);
        }
        return classes;
    }

    private static Set<Class<?>> findClassesInJar(String jarPath, String pkgPath, String packageName, boolean recursive)
            throws ClassNotFoundException, IOException {
        try (JarFile jarFile = new JarFile(URLDecoder.decode(jarPath, StandardCharsets.UTF_8.name()))) {
            Set<Class<?>> classes = new HashSet<>();
            Enumeration<JarEntry> entries = jarFile.entries();

            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String entryName = entry.getName();
                if (entryName.endsWith(".class") && entryName.startsWith(pkgPath) && entryName.length() > pkgPath.length()) {
                    if (!recursive && entryName.lastIndexOf('/') > pkgPath.length()) {
                        continue;
                    }

                    String className = entryName.replace('/', '.').substring(0, entryName.length() - ".class".length());

                    try {
                        Class<?> clazz = Class.forName(className);
                        classes.add(clazz);
                    } catch (NoClassDefFoundError e) {
                    }
                }
            }

            return classes;
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

    private static <T> Constructor<T> getSuitableConstructor(Class<T> clazz, Class<?>[] parameterTypes) throws NoSuchMethodException {
        return (Constructor<T>) Arrays.stream(clazz.getDeclaredConstructors())
                .filter(constructor -> checkParameters(constructor, parameterTypes))
                .findFirst()
                .orElseThrow(() -> new NoSuchMethodException("Couldn't find a suitable constructor for given parameters."));
    }

    private static boolean checkParameters(Executable executable, Class<?>[] parameterTypes) {
        Class<?>[] executableParameterTypes = executable.getParameterTypes();

        if (executable.isVarArgs()) {
            // Almost all parameters should match
            if (executableParameterTypes.length - 1 > parameterTypes.length) {
                return false;
            }

            for (int i = 0; i < executableParameterTypes.length - 1; i++) {
                if (!parameterTypes[i].isAssignableFrom(executableParameterTypes[i])) {
                    return false;
                }
            }

            // The last parameter (varargs) should be an array that is assignable from the remaining parameterTypes
            Class<?> varargsType = executableParameterTypes[executableParameterTypes.length - 1].getComponentType();
            for (int i = executableParameterTypes.length - 1; i < parameterTypes.length; i++) {
                if (!varargsType.isAssignableFrom(parameterTypes[i])) {
                    return false;
                }
            }
        } else {
            if (executableParameterTypes.length != parameterTypes.length) {
                return false;
            }

            for (int i = 0; i < parameterTypes.length; i++) {
                if (!parameterTypes[i].isAssignableFrom(executableParameterTypes[i])) {
                    return false;
                }
            }
        }

        return true;
    }

    private static void checkConstructor(Constructor<?> constructor) throws NoSuchMethodException {
        if (constructor == null) {
            throw new NoSuchMethodException("Couldn't find constructor for given parameters");
        }
    }

}
