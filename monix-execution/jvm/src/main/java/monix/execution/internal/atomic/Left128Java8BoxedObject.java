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
final class Left128Java8BoxedObject extends LeftPadding120 implements BoxedObject {
  public volatile Object value;
  private static final long OFFSET;
  private static final Unsafe UNSAFE = (Unsafe) UnsafeAccess.getInstance();

  static {
    try {
      Field field = Left128Java8BoxedObject.class.getDeclaredField("value");
      OFFSET = UNSAFE.objectFieldOffset(field);
    } catch (Exception ex) {
      throw new RuntimeException(ex);
    }
  }

  Left128Java8BoxedObject(Object initialValue) {
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
    return UNSAFE.getAndSetObject(this, OFFSET, update);
  }
}