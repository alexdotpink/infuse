# Infuse

### Overview

Infuse is a powerful and flexible dependency injection library for Java. Designed to simplify the management and configuration of complex dependencies, Infuse offers an intuitive way to inject dependencies in your Java applications, improving code modularity and maintainability.

### Features

- **Annotation-Driven**: Utilizes custom annotations like `@Inject` for easy dependency management.
- **Support for Lifecycle Methods**: Annotations like `@PostConstruct` and `@PreDestroy` allow for lifecycle management.
- **Flexible Binding**: Supports rich binding options including singleton, instance, request, session, and custom scopes.
- **First-class Scopes**: Activate request/session lifecycles and register custom scopes via the fluent API or `Injector#openScope` utilities.
- **Eager and Lazy Initialization**: Options for both eager and lazy initialization of dependencies.
- **Nested Injection**: Supports nested dependency injection through child injectors.

### Performance Benchmarks

Microbenchmarks powered by [JMH](https://openjdk.org/projects/code-tools/jmh/) are available in `src/jmh/java` to quantify injector hot paths such as singleton resolution, graph construction, and negative lookups.

- Run the full suite with `./gradlew jmh`.
- Benchmark results are published under `build/reports/jmh` alongside the raw `.json` output in `build/jmh/results` for further analysis.
- Adjust benchmark parameters (warmup, measurement iterations, forks) via the `jmh { ... }` block in `build.gradle` as needed for deeper investigations.

### Documentation

You can find the documentation at [infusedocs.fumaz.dev](https://infusedocs.fumaz.dev).

### Community and Support

**GitHub Issues**: For reporting bugs and issues with the library.
**Discord**: Join our discord community for discussions and support: [https://discord.gg/bTwzePh7Qr](https://discord.gg/bTwzePh7Qr)

### Contributing

We welcome contributions! 

### License
Infuse is released under the [MIT License](https://opensource.org/license/mit/).
