/*
 * Copyright (c) 2014-2019 by The Monix Project Developers.
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
final class Right128Java7BoxedObject extends Right128Java7BoxedObjectImpl {
  public volatile long p1, p2, p3, p4, p5, p6, p7, p8 = 7;
  public volatile long p9, p10, p11, p12, p13, p14, p15 = 7;
  public long sum() {
    return p1 + p2 + p3 + p4 + p5 + p6 + p7 + p8 +
      p9 + p10 + p11 + p12 + p13 + p14 + p15;
  }

  Right128Java7BoxedObject(Object initialValue) {
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
abstract class Right128Java7BoxedObjectImpl implements BoxedObject {
  public volatile Object value;
  private static final long OFFSET;
  private static final Unsafe UNSAFE = (Unsafe) UnsafeAccess.getInstance();

  static {
    try {
      Field field = Right128Java7BoxedObjectImpl.class.getDeclaredField("value");
      OFFSET = UNSAFE.objectFieldOffset(field);
    } catch (Exception ex) {
      throw new RuntimeException(ex);
    }
  }

  Right128Java7BoxedObjectImpl(Object initialValue) {
    this.value = initialValue;
  }

  public Object volatileGet() {
    return value;
  }

  public void volatileSet(Object update) {
    value = update;
  }

  public void lazySet(Object update) {
    UNSAFE.putOrderedObject(this, OFFSET, update);
  }

  public boolean compareAndSet(Object current, Object update) {
    return UNSAFE.compareAndSwapObject(this, OFFSET, current, update);
  }

  public Object getAndSet(Object update) {
    Object current = value;
    while (!UNSAFE.compareAndSwapObject(this, OFFSET, current, update))
      current = value;
    return current;
  }
}