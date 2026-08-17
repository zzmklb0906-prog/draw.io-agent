package cn.bugstack.wrench.design.framework.tree;

/**
 * Minimal strategy contract used by the armory assembly tree.
 *
 * <p>This project keeps the tiny tree abstraction locally because the former
 * starter artifact was an uber JAR containing Spring Boot 3 classes and
 * META-INF/spring.factories entries, which cannot safely run with Boot 4.</p>
 */
@FunctionalInterface
public interface StrategyHandler<T, D, R> {

    StrategyHandler<?, ?, ?> DEFAULT = (request, context) -> null;

    R apply(T request, D context) throws Exception;

    @SuppressWarnings("unchecked")
    static <T, D, R> StrategyHandler<T, D, R> defaultHandler() {
        return (StrategyHandler<T, D, R>) DEFAULT;
    }
}
