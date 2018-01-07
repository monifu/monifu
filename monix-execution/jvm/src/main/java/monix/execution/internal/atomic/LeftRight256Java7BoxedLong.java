/*
 * Copyright (c) 2014-2018 by The Monix Project Developers.
 * See the project homepage at: https://monix.io
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package monix.execution.internal.atomic;

import monix.execution.internal.InternalApi;
import sun.misc.Unsafe;
import java.lang.reflect.Field;

/**
 * INTERNAL API — used in the implementation of
 * `monix.execution.atomic.Atomic`.
 *
 * Being internal it can always change between minor versions,
 * providing no backwards compatibility guarantees and is only public
 * because Java does not provide the capability of marking classes as
 * "internal" to a package and all its sub-packages.
 */
@InternalApi
final class LeftRight256Java7BoxedLong extends LeftRight256Java7BoxedLongImpl {
  public volatile long r01, r02, r03, r04, r05, r06, r07, r08 = 7;
  public volatile long r09, r10, r11, r12, r13, r14, r15, r16 = 8;
  @Override public long sum() {
    return
      p01 + p02 + p03 + p04 + p05 + p06 + p07 + p08 +
      p09 + p10 + p11 + p12 + p13 + p14 + p15 +
      r01 + r02 + r03 + r04 + r05 + r06 + r07 + r08 +
      r09 + r10 + r11 + r12 + r13 + r14 + r15 + r16;
  }

  LeftRight256Java7BoxedLong(long initialValue) {
    super(initialValue);
  }
}

/**
 * INTERNAL API — used in the implementation of
 * `monix.execution.atomic.Atomic`.
 *
 * Being internal it can always change between minor versions,
 * providing no backwards compatibility guarantees and is only public
 * because Java does not provide the capability of marking classes as
 * "internal" to a package and all its sub-packages.
 */
@InternalApi
abstract class LeftRight256Java7BoxedLongImpl extends LeftPadding120 implements BoxedLong {
  public volatile long value;
  private static final long OFFSET;
  private static final Unsafe UNSAFE = (Unsafe) UnsafeAccess.getInstance();

  static {
    try {
      Field field = LeftRight256Java7BoxedLongImpl.class.getDeclaredField("value");
      OFFSET = UNSAFE.objectFieldOffset(field);
    } catch (Exception ex) {
      throw new RuntimeException(ex);
    }
  }

  LeftRight256Java7BoxedLongImpl(long initialValue) {
    this.value = initialValue;
  }

  public long volatileGet() {
    return value;
  }

  public void volatileSet(long update) {
    value = update;
  }

  public void lazySet(long update) {
    UNSAFE.putOrderedLong(this, OFFSET, update);
  }

  public boolean compareAndSet(long current, long update) {
    return UNSAFE.compareAndSwapLong(this, OFFSET, current, update);
  }

  public long getAndSet(long update) {
    long current = value;
    while (!UNSAFE.compareAndSwapLong(this, OFFSET, current, update))
      current = value;
    return current;
  }

  public long getAndAdd(long delta) {
    long current;
    do {
      current = UNSAFE.getLongVolatile(this, OFFSET);
    } while (!UNSAFE.compareAndSwapLong(this, OFFSET, current, current+delta));
    return current;
  }
}