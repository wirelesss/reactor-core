/*
 * Copyright (c) 2011-2016 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package reactor.core.publisher;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import org.reactivestreams.Processor;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.flow.Producer;
import reactor.core.flow.Receiver;
import reactor.core.state.Cancellable;
import reactor.core.state.Completable;
import reactor.core.state.Failurable;
import reactor.core.state.Introspectable;
import reactor.core.timer.Timer;
import reactor.core.util.BackpressureUtils;
import reactor.core.util.EmptySubscription;
import reactor.core.util.Exceptions;
import reactor.core.util.PlatformDependent;
import reactor.core.util.ScalarSubscription;

/**
 * A {@code MonoProcessor} is a {@link Mono} extension that implements stateful semantics. Multi-subscribe is allowed.
 *
 * <p>
 * <img width="640" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/monoprocessor.png" alt="">
 * <p>
 *
 * Once a {@link MonoProcessor} has been resolved, newer subscribers will benefit from the cached result.
 *
 * @param <O> the type of the value that will be made available
 *
 * @author Stephane Maldini
 */
public final class MonoProcessor<O> extends Mono<O>
		implements Processor<O, O>, Subscription, Failurable, Completable, Cancellable, Receiver, Producer {

	/**
	 * Create a {@link MonoProcessor} that will eagerly request 1 on {@link #onSubscribe(Subscription)}, cache and emit
	 * the eventual result for 1 or N subscribers.
	 *
	 * @param <T> type of the expected value
	 *
	 * @return A {@link MonoProcessor}.
	 */
	public static <T> MonoProcessor<T> create() {
		return new MonoProcessor<>(null);
	}

	final static NoopProcessor NOOP_PROCESSOR = new NoopProcessor();

	final static AtomicIntegerFieldUpdater<MonoProcessor>              STATE     =
			AtomicIntegerFieldUpdater.newUpdater(MonoProcessor.class, "state");
	final static AtomicIntegerFieldUpdater<MonoProcessor>              WIP       =
			AtomicIntegerFieldUpdater.newUpdater(MonoProcessor.class, "wip");
	final static AtomicIntegerFieldUpdater<MonoProcessor>              REQUESTED =
			AtomicIntegerFieldUpdater.newUpdater(MonoProcessor.class, "requested");
	final static AtomicReferenceFieldUpdater<MonoProcessor, Processor> PROCESSOR =
			PlatformDependent.newAtomicReferenceFieldUpdater(MonoProcessor.class, "processor");

	final static int       STATE_CANCELLED         = -1;
	final static int       STATE_READY             = 0;
	final static int       STATE_SUBSCRIBED        = 1;
	final static int       STATE_POST_SUBSCRIBED   = 2;
	final static int       STATE_SUCCESS_VALUE     = 3;
	final static int       STATE_COMPLETE_NO_VALUE = 4;
	final static int       STATE_ERROR             = 5;

	final Publisher<? extends O> source;
	Subscription subscription;

	volatile Processor<O, O> processor;
	volatile O               value;
	volatile Throwable       error;
	volatile int             state;
	volatile int             wip;
	volatile int             requested;

	MonoProcessor(Publisher<? extends O> source) {
		this.source = source;
	}

	@Override
	public final void cancel() {
		int state = this.state;
		for (; ; ) {
			if (state != STATE_READY && state != STATE_SUBSCRIBED && state != STATE_POST_SUBSCRIBED) {
				return;
			}
			if (STATE.compareAndSet(this, state, STATE_CANCELLED)) {
				break;
			}
			state = this.state;
		}
		if (WIP.getAndIncrement(this) == 0) {
			drainLoop();
		}
	}

	@Override
	public final Subscriber downstream() {
		return processor;
	}

	/**
	 * Block the calling thread for the specified time, waiting for the completion of this {@code MonoProcessor}. If the
	 * {@link MonoProcessor} is completed with an error a RuntimeException that wraps the error is thrown.
	 *
	 * @param timeout the timeout value in milliseconds
	 *
	 * @return the value of this {@code MonoProcessor} or {@code null} if the timeout is reached and the {@code MonoProcessor} has
	 * not completed
	 */
	@Override
	public O get(long timeout) {
		try {
			request(1);
			if (!isPending()) {
				return peek();
			}

			long delay = System.currentTimeMillis() + timeout;

			for (; ; ) {
				int endState = this.state;
				switch (endState) {
					case STATE_SUCCESS_VALUE:
						return value;
					case STATE_ERROR:
						if (error instanceof RuntimeException) {
							throw (RuntimeException) error;
						}
						Exceptions.fail(error);
					case STATE_COMPLETE_NO_VALUE:
						return null;
				}
				if (delay < System.currentTimeMillis()) {
					Exceptions.failWithCancel();
				}
				Thread.sleep(1);
			}
		}
		catch (InterruptedException ie) {
			Thread.currentThread().interrupt();

			Exceptions.failWithCancel();
			return null;
		}
	}

	/**
	 * Block the calling thread for the specified time, waiting for the completion of this {@code MonoProcessor}. If the
	 * {@link MonoProcessor} is completed with an error a RuntimeException that wraps the error is thrown.
	 *
	 * @param timeout the timeout value
	 *
	 * @return the value of this {@code MonoProcessor} or {@code null} if the timeout is reached and the {@code MonoProcessor} has
	 * not completed
	 */
	@Override
	public O get(Duration timeout) {
		return get(timeout.toMillis());
	}

	@Override
	public final Throwable getError() {
		return error;
	}

	/**
	 * Indicates whether this {@code MonoProcessor} has been completed with an error.
	 *
	 * @return {@code true} if this {@code MonoProcessor} was completed with an error, {@code false} otherwise.
	 */
	public final boolean isError() {
		return state == STATE_ERROR;
	}

	/**
	 * Indicates whether this {@code MonoProcessor} has yet to be completed with a value or an error.
	 *
	 * @return {@code true} if this {@code MonoProcessor} is still pending, {@code false} otherwise.
	 *
	 * @see #isTerminated()
	 */
	public final boolean isPending() {
		return !isTerminated() && !isCancelled();
	}

	@Override
	public final boolean isStarted() {
		return state > STATE_READY && !isTerminated();
	}

	/**
	 * Indicates whether this {@code MonoProcessor} has been successfully completed a value.
	 *
	 * @return {@code true} if this {@code MonoProcessor} is successful, {@code false} otherwise.
	 */
	public final boolean isSuccess() {
		return state == STATE_COMPLETE_NO_VALUE || state == STATE_SUCCESS_VALUE;
	}

	@Override
	public final boolean isTerminated() {
		return state > STATE_POST_SUBSCRIBED;
	}

	@Override
	public final void onComplete() {
		onNext(null);
	}

	@Override
	public boolean isCancelled() {
		return state == STATE_CANCELLED;
	}

	@Override
	public final void onError(Throwable cause) {
		Subscription s = subscription;

		if ((source != null && s == null) || this.error != null) {
			Exceptions.onErrorDropped(cause);
			return;
		}

		this.error = cause;
		subscription = null;

		int state = this.state;
		for (; ; ) {
			if (state != STATE_READY && state != STATE_SUBSCRIBED && state != STATE_POST_SUBSCRIBED) {
				Exceptions.onErrorDropped(cause);
				return;
			}
			if (STATE.compareAndSet(this, state, STATE_ERROR)) {
				break;
			}
			state = this.state;
		}
		if (WIP.getAndIncrement(this) == 0) {
			drainLoop();
		}
	}

	@Override
	public final void onNext(O value) {
		Subscription s = subscription;

		if (value != null && ((source != null && s == null) || this.value != null)) {
			Exceptions.onNextDropped(value);
			return;
		}
		subscription = null;

		final int finalState;
		if(value != null) {
			finalState = STATE_SUCCESS_VALUE;
			this.value = value;
			if (s != null) {
				s.cancel();
			}
		}
		else {
			finalState = STATE_COMPLETE_NO_VALUE;
		}
		int state = this.state;
		for (; ; ) {
			if (state != STATE_READY && state != STATE_SUBSCRIBED && state != STATE_POST_SUBSCRIBED) {
				if(value != null) {
					Exceptions.onNextDropped(value);
				}
				return;
			}
			if (STATE.compareAndSet(this, state, finalState)) {
				break;
			}
			state = this.state;
		}


		if (WIP.getAndIncrement(this) == 0) {
			drainLoop();
		}
	}

	@Override
	public final void onSubscribe(Subscription subscription) {
		if (BackpressureUtils.validate(this.subscription, subscription)) {
			this.subscription = subscription;
			if (STATE.compareAndSet(this, STATE_READY, STATE_SUBSCRIBED) && REQUESTED.getAndSet(this, 2) != 2){
				subscription.request(1L);
			}

			if (WIP.getAndIncrement(this) == 0) {
				drainLoop();
			}
		}
	}

	/**
	 * Returns the value that completed this {@link MonoProcessor}. Returns {@code null} if the {@link MonoProcessor} has not been completed. If the
	 * {@link MonoProcessor} is completed with an error a RuntimeException that wraps the error is thrown.
	 *
	 * @return the value that completed the {@link MonoProcessor}, or {@code null} if it has not been completed
	 *
	 * @throws RuntimeException if the {@link MonoProcessor} was completed with an error
	 */
	public O peek() {
		request(1);
		int endState = this.state;

		if (endState == STATE_SUCCESS_VALUE) {
			return value;
		}
		else if (endState == STATE_ERROR) {
			if (RuntimeException.class.isInstance(error)) {
				throw (RuntimeException) error;
			}
			else {
				Exceptions.onErrorDropped(error);
				return null;
			}
		}
		else {
			return null;
		}
	}

	@Override
	public final void request(long n) {
		try {
			BackpressureUtils.checkRequest(n);
			Subscription s = subscription;
			if(!REQUESTED.compareAndSet(this, 0, 1) &&
				s != null && REQUESTED.compareAndSet(this, 1, 2)){
				s.request(1L);
			}
		}
		catch (Throwable e) {
			Exceptions.throwIfFatal(e);
			onError(e);
		}
		if (WIP.getAndIncrement(this) == 0) {
			drainLoop();
		}
	}

	@Override
	@SuppressWarnings("unchecked")
	public void subscribe(final Subscriber<? super O> subscriber) {
		int endState = this.state;
		if (endState == STATE_COMPLETE_NO_VALUE) {
			EmptySubscription.complete(subscriber);
			return;
		}
		else if (endState == STATE_SUCCESS_VALUE) {
			subscriber.onSubscribe(new ScalarSubscription<>(subscriber, value));
			return;
		}
		else if (endState == STATE_ERROR) {
			EmptySubscription.error(subscriber, error);
			return;
		}
		Processor<O, O> out = processor;
		if (out == null) {
			out = EmitterProcessor.replayLastOrDefault(value);
			if (PROCESSOR.compareAndSet(this, null, out)) {
				if (source != null) {
					source.subscribe(this);
				}
			}
			else {
				out = (Processor<O, O>) PROCESSOR.get(this);
			}
		}
		out.subscribe(subscriber);
		if (WIP.getAndIncrement(this) == 0) {
			drainLoop();
		}
	}

	@SuppressWarnings("unchecked")
	final void drainLoop() {
		int missed = 1;

		int state;
		for (; ; ) {
			state = this.state;
			if (state > STATE_POST_SUBSCRIBED) {
				Processor<O, O> p = (Processor<O, O>) PROCESSOR.getAndSet(this, NOOP_PROCESSOR);
				if (p != NOOP_PROCESSOR && p != null) {
					switch (state) {
						case STATE_COMPLETE_NO_VALUE:
							p.onComplete();
							break;
						case STATE_SUCCESS_VALUE:
							p.onNext(value);
							p.onComplete();
							break;
						case STATE_ERROR:
							p.onError(error);
							break;
					}
					return;
				}
			}
			Subscription subscription = this.subscription;

			if(subscription != null) {
				if (state == STATE_CANCELLED && PROCESSOR.getAndSet(this, NOOP_PROCESSOR) != NOOP_PROCESSOR) {
					this.subscription = null;
					subscription.cancel();
					return;
				}

				if (REQUESTED.get(this) == 1 && REQUESTED.compareAndSet(this, 1, 2)) {
					subscription.request(1L);
				}
			}

			if (state == STATE_SUBSCRIBED && STATE.compareAndSet(this, STATE_SUBSCRIBED, STATE_POST_SUBSCRIBED)) {
				Processor<O, O> p = (Processor<O, O>) PROCESSOR.get(this);
				if (p != null && p != NOOP_PROCESSOR) {
					p.onSubscribe(this);
				}
			}

			missed = WIP.addAndGet(this, -missed);
			if (missed == 0) {
				break;
			}
		}
	}

	@Override
	public int getMode() {
		return 0;
	}

	@Override
	public final Object upstream() {
		return subscription;
	}

	final static class NoopProcessor implements Processor, Introspectable {

		@Override
		public void subscribe(Subscriber s) {

		}

		@Override
		public void onSubscribe(Subscription s) {

		}

		@Override
		public void onNext(Object o) {

		}

		@Override
		public void onError(Throwable t) {

		}

		@Override
		public void onComplete() {

		}

		@Override
		public int getMode() {
			return TRACE_ONLY;
		}
	}
}