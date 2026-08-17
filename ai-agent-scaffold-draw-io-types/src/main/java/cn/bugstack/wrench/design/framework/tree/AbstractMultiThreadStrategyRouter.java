package cn.bugstack.wrench.design.framework.tree;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/** Local, dependency-free implementation of the strategy-tree router. */
public abstract class AbstractMultiThreadStrategyRouter<T, D, R>
        implements StrategyMapper<T, D, R>, StrategyHandler<T, D, R> {

    protected StrategyHandler<T, D, R> defaultStrategyHandler = StrategyHandler.defaultHandler();

    public R router(T request, D context) throws Exception {
        StrategyHandler<T, D, R> handler = get(request, context);
        return (handler != null ? handler : defaultStrategyHandler).apply(request, context);
    }

    @Override
    public R apply(T request, D context) throws Exception {
        multiThread(request, context);
        return doApply(request, context);
    }

    protected abstract void multiThread(T request, D context)
            throws ExecutionException, InterruptedException, TimeoutException;

    protected abstract R doApply(T request, D context) throws Exception;

    public StrategyHandler<T, D, R> getDefaultStrategyHandler() {
        return defaultStrategyHandler;
    }

    public void setDefaultStrategyHandler(StrategyHandler<T, D, R> defaultStrategyHandler) {
        this.defaultStrategyHandler = defaultStrategyHandler;
    }
}
