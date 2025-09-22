package dev.fumaz.infuse.benchmark;

import dev.fumaz.infuse.annotation.Inject;
import dev.fumaz.infuse.injector.Injector;
import dev.fumaz.infuse.module.InfuseModule;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
public class InjectorBenchmark {

    @State(Scope.Benchmark)
    public static class InjectorState {

        Injector injector;

        @Setup(Level.Trial)
        public void setUp() {
            injector = Injector.create(new BenchmarkModule());
        }
    }

    @Benchmark
    public Object provideSingleton(InjectorState state) {
        return state.injector.provide(SingletonService.class, state);
    }

    @Benchmark
    public Object provideCompositeGraph(InjectorState state) {
        return state.injector.provide(CompositeService.class, state);
    }

    @Benchmark
    public Object constructCompositeGraph(InjectorState state) {
        return state.injector.construct(CompositeService.class);
    }

    @Benchmark
    public void unresolvedBindingLookup(InjectorState state, Blackhole blackhole) {
        try {
            blackhole.consume(state.injector.getProvider(UnboundType.class));
        } catch (RuntimeException exception) {
            blackhole.consume(exception);
        }
    }

    private static class BenchmarkModule extends InfuseModule {
        @Override
        public void configure() {
            bind(SingletonService.class).toSingleton(SingletonService.class);
            bind(HeavyComputation.class).to(HeavyComputation.class);
            bind(ExpensiveDependency.class).to(ExpensiveDependency.class);
            bind(TransientService.class).to(TransientService.class);
            bind(CompositeService.class).to(CompositeService.class);
        }
    }

    public static class SingletonService {
        private final HeavyComputation heavyComputation;

        @Inject
        public SingletonService(HeavyComputation heavyComputation) {
            this.heavyComputation = heavyComputation;
        }

        public int compute() {
            return heavyComputation.compute();
        }
    }

    public static class TransientService {
        private final ExpensiveDependency dependency;

        @Inject
        public TransientService(ExpensiveDependency dependency) {
            this.dependency = dependency;
        }

        public int compute() {
            return dependency.value();
        }
    }

    public static class CompositeService {
        private final SingletonService singletonService;
        private final TransientService transientService;
        private final ExpensiveDependency expensiveDependency;

        @Inject
        public CompositeService(SingletonService singletonService,
                                TransientService transientService,
                                ExpensiveDependency expensiveDependency) {
            this.singletonService = singletonService;
            this.transientService = transientService;
            this.expensiveDependency = expensiveDependency;
        }

        public int aggregate() {
            return singletonService.compute() + transientService.compute() + expensiveDependency.value();
        }
    }

    public static class ExpensiveDependency {
        private final HeavyComputation heavyComputation;

        @Inject
        public ExpensiveDependency(HeavyComputation heavyComputation) {
            this.heavyComputation = heavyComputation;
        }

        public int value() {
            return heavyComputation.compute();
        }
    }

    public static class HeavyComputation {
        public int compute() {
            int result = 0;
            for (int i = 0; i < 16; i++) {
                result = (result * 31) ^ i;
            }
            return result;
        }
    }

    public static class UnboundType {
    }
}
