/*
 * Copyright (c) 2016-2017 Couchbase, Inc.
 */
package com.couchbase.client.dcp.util.retry;

import java.util.concurrent.TimeUnit;

import com.couchbase.client.core.annotations.InterfaceAudience;
import com.couchbase.client.core.annotations.InterfaceStability;
import com.couchbase.client.core.lang.Tuple2;
import com.couchbase.client.core.time.Delay;
import com.couchbase.client.core.time.ExponentialDelay;

import rx.Observable;
import rx.Scheduler;
import rx.functions.Action4;
import rx.functions.Func1;

/**
 * A class that allows to produce a "retry" delay depending on the number of retry attempts.
 * The number of retries is bounded by a maximum number of attempts.
 *
 * @see Retry#wrapForRetry(Observable, RetryWithDelayHandler) how to wrap an Observable with this behavior
 * @see RetryWhenFunction how to chain this behavior into an Observable's retryWhen operation.
 * @see RetryBuilder how to construct a RetryWhenFunction in a fluent manner.
 *
 * @author Simon Baslé
 * @since 1.0.0
 */
@InterfaceStability.Committed
@InterfaceAudience.Public
public class RetryWithDelayHandler implements Func1<Tuple2<Integer, Throwable>, Observable<?>> {

    protected final int maxAttempts;
    protected final Delay retryDelay;
    protected final Func1<Throwable, Boolean> errorInterruptingPredicate;
    protected final Scheduler optionalScheduler;
    protected final Action4<Integer, Throwable, Long, TimeUnit> doOnRetry;

    /**
     * Construct a {@link RetryWithDelayHandler retry handler} that will retry on all errors.
     *
     * @param maxAttempts
     *            the maximum number of retries before a {@link CannotRetryException} is thrown. It will be
     *            capped at <code>{@link Integer#MAX_VALUE} - 1</code>.
     * @param retryDelay
     *            the {@link Delay} to apply between each retry (can grow,
     *            eg. by using {@link ExponentialDelay}).
     */
    public RetryWithDelayHandler(int maxAttempts, Delay retryDelay) {
        this(maxAttempts, retryDelay, null, null);
    }

    /**
     * Construct a {@link RetryWithDelayHandler retry handler} that will retry on most errors but will stop on specific errors.
     *
     * @param maxAttempts
     *            the maximum number of retries before a {@link CannotRetryException} is thrown. It will be
     *            capped at <code>{@link Integer#MAX_VALUE} - 1</code>.
     * @param retryDelay
     *            the {@link Delay} to apply between each retry (can grow,
     *            eg. by using {@link ExponentialDelay}).
     * @param errorInterruptingPredicate
     *            a predicate that determine if an error must stop the retry cycle (when true),
     *            in which case said error is cascaded down.
     */
    public RetryWithDelayHandler(int maxAttempts, Delay retryDelay,
            Func1<Throwable, Boolean> errorInterruptingPredicate,
            Action4<Integer, Throwable, Long, TimeUnit> doOnRetry) {
        this(maxAttempts, retryDelay, errorInterruptingPredicate, doOnRetry, null);
    }

    /**
     * Protected constructor that also allows to set a {@link Scheduler} for the delay, especially useful for tests.
     */
    protected RetryWithDelayHandler(int maxAttempts, Delay retryDelay,
            Func1<Throwable, Boolean> errorInterruptingPredicate,
            Action4<Integer, Throwable, Long, TimeUnit> doOnRetry, Scheduler scheduler) {
        this.maxAttempts = Math.min(Integer.MAX_VALUE - 1, maxAttempts);
        this.retryDelay = retryDelay;
        this.errorInterruptingPredicate = errorInterruptingPredicate;
        this.optionalScheduler = scheduler;
        this.doOnRetry = doOnRetry;
    }

    protected static String messageForMaxAttempts(long reachedAfterNRetries) {
        return "maximum number of attempts reached after " + reachedAfterNRetries + " retries";
    }

    @Override
    public Observable<?> call(Tuple2<Integer, Throwable> attemptError) {
        final int errorNumber = attemptError.value1();
        final Throwable error = attemptError.value2();

        if (errorNumber > maxAttempts) {
            return Observable.error(new CannotRetryException(messageForMaxAttempts(errorNumber - 1), error));
        } else if (errorInterruptingPredicate != null && errorInterruptingPredicate.call(error) == Boolean.TRUE) {
            return Observable.error(error);
        } else {
            final long delay = retryDelay.calculate(errorNumber);
            final TimeUnit unit = retryDelay.unit();

            if (doOnRetry != null) {
                doOnRetry.call(errorNumber, error, delay, unit);
            }

            if (this.optionalScheduler != null) {
                return Observable.timer(delay, unit, optionalScheduler);
            } else {
                return Observable.timer(delay, unit);
            }
        }
    }
}
