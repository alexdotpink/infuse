package dev.fumaz.infuse.injector;

import dev.fumaz.infuse.annotation.Inject;
import dev.fumaz.infuse.annotation.Named;
import dev.fumaz.infuse.exception.ProvisionException;
import dev.fumaz.infuse.module.InfuseModule;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CircularDependencyTest {

    @Test
    void throwsDescriptiveExceptionForCircularFieldInjection() {
        Injector injector = Injector.create();

        ProvisionException exception = assertThrows(ProvisionException.class,
                () -> injector.construct(FirstComponent.class));

        Throwable cycle = rootCause(exception);
        assertTrue(cycle instanceof IllegalStateException,
                "cycle detection should surface as IllegalStateException");

        String message = cycle.getMessage();
        assertNotNull(message, "cycle detection should report a message");
        assertTrue(message.contains("Dependency cycle detected while resolving"),
                "message should mention cycle detection");
        assertTrue(message.contains("Cycle path:"), "message should include the cycle path header");
        assertTrue(message.contains(FirstComponent.class.getName()),
                "message should mention the types participating in the cycle");
        assertTrue(message.contains("field 'second'"),
                "message should identify at least one injection point");
        assertTrue(message.contains("implicit construction"),
                "message should clarify how instances are produced");
    }

    @Test
    void allowsReentrantResolutionWhenQualifiersDiffer() {
        Injector injector = Injector.create(new InfuseModule() {
            @Override
            public void configure() {
                bind(QualifiedService.class).named("two").to(QualifiedSecondary.class);
                bind(QualifiedService.class).named("one").to(QualifiedPrimary.class);
                bind(QualifiedConsumer.class).to(QualifiedConsumer.class);
            }
        });

        QualifiedConsumer consumer = injector.construct(QualifiedConsumer.class);

        assertNotNull(consumer, "consumer should be constructed successfully");
        assertNotNull(consumer.primary, "primary service should be injected");
        assertTrue(consumer.primary instanceof QualifiedPrimary,
                "primary service should resolve the @Named(\"one\") binding");
        assertNotNull(((QualifiedPrimary) consumer.primary).secondary,
                "primary service should receive the @Named(\"two\") dependency");
    }

    static class FirstComponent {
        @Inject
        SecondComponent second;
    }

    static class SecondComponent {
        @Inject
        ThirdComponent third;
    }

    static class ThirdComponent {
        @Inject
        FirstComponent first;
    }

    interface QualifiedService {
    }

    static class QualifiedPrimary implements QualifiedService {
        final QualifiedService secondary;

        @Inject
        QualifiedPrimary(@Named("two") QualifiedService secondary) {
            this.secondary = secondary;
        }
    }

    static class QualifiedSecondary implements QualifiedService {
    }

    static class QualifiedConsumer {
        final QualifiedService primary;

        @Inject
        QualifiedConsumer(@Named("one") QualifiedService primary) {
            this.primary = primary;
        }
    }

    private static Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        int depth = 0;

        while (current.getCause() != null && depth < 16) {
            current = current.getCause();
            depth++;
        }

        return current;
    }
}
