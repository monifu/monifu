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

import sun.misc.Unsafe;
import java.lang.reflect.Field;

final class LeftRight256Java8BoxedLong extends LeftRight256Java8BoxedLongImpl {
  public volatile long r01, r02, r03, r04, r05, r06, r07, r08 = 7;
  public volatile long r09, r10, r11, r12, r13, r14, r15, r16 = 8;
  @Override public long sum() {
    return
      p01 + p02 + p03 + p04 + p05 + p06 + p07 + p08 +
      p09 + p10 + p11 + p12 + p13 + p14 + p15 +
      r01 + r02 + r03 + r04 + r05 + r06 + r07 + r08 +
      r09 + r10 + r11 + r12 + r13 + r14 + r15 + r16;
  }

  LeftRight256Java8BoxedLong(long initialValue) {
    super(initialValue);
  }
}

abstract class LeftRight256Java8BoxedLongImpl extends LeftPadding120 implements BoxedLong {

  public volatile long value;
  private static final long OFFSET;
  private static final Unsafe UNSAFE = (Unsafe) UnsafeAccess.getInstance();

  static {
    try {
      Field field = LeftRight256Java8BoxedLongImpl.class.getDeclaredField("value");
      OFFSET = UNSAFE.objectFieldOffset(field);
    } catch (Exception ex) {
      throw new RuntimeException(ex);
    }
  }

  LeftRight256Java8BoxedLongImpl(long initialValue) {
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
    return UNSAFE.getAndSetLong(this, OFFSET, update);
  }

  public long getAndAdd(long delta) {
    return UNSAFE.getAndAddLong(this, OFFSET, delta);
  }
}