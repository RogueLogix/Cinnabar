package graphics.cinnabar.lib.util;

import java.lang.invoke.LambdaMetafactory;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;

public class ReflectionUtil {
    public static Runnable createRunnableForFunction(Method method) {
        try {
            final var lookup = MethodHandles.lookup();
            final var methodType = MethodType.methodType(void.class);
            final MethodHandle methodHandle;
            methodHandle = lookup.findStatic(method.getDeclaringClass(), method.getName(), methodType);
            var callSite = LambdaMetafactory.metafactory(
                    lookup,
                    "run",
                    MethodType.methodType(Runnable.class),
                    methodType,
                    methodHandle,
                    methodType
            );
            return (Runnable) callSite.getTarget().invokeExact();
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }
}
