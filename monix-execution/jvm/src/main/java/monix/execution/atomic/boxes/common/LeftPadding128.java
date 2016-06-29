/*
 * Copyright (c) 2016 by its authors. Some rights reserved.
 * See the project homepage at: https://sincron.org
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

package monix.execution.atomic.boxes.common;

public abstract class LeftPadding128 {
    public volatile long p1, p2, p3, p4, p5, p6, p7, p8 = 7;
    public volatile long p9, p10, p11, p12, p13, p14, p15, p16 = 7;
    public long sum() {
        return p1 + p2 + p3 + p4 + p5 + p6 + p7 + p8 +
               p9 + p10 + p11 + p12 + p13 + p14 + p15 + p16;
    }
}