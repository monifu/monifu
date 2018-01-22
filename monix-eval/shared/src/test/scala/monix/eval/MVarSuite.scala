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

package monix.eval

import monix.execution.CancelableFuture
import monix.execution.atomic.PaddingStrategy
import monix.execution.atomic.PaddingStrategy.LeftRight128
import monix.execution.internal.Platform

import scala.util.Success

object MVarSuite extends BaseTestSuite {
  test("empty; put; take; put; take") { implicit s =>
    val task = for {
      av <- MVar.empty[Int]
      _  <- av.put(10)
      r1 <- av.take
      _  <- av.put(20)
      r2 <- av.take
    } yield List(r1,r2)

    assertEquals(task.runSyncMaybe, Right(List(10,20)))
  }

  test("empty; take; put; take; put") { implicit s =>
    val task = for {
      av <- MVar.empty[Int]
      r1 <- Task.mapBoth(av.take, av.put(10))((r,_) => r)
      r2 <- Task.mapBoth(av.take, av.put(20))((r,_) => r)
    } yield List(r1,r2)

    assertEquals(task.runSyncMaybe, Right(List(10,20)))
  }

  test("empty; put; put; put; take; take; take") { implicit s =>
    val task = for {
      av    <- MVar.empty[Int]
      take3  = Task.zip3(av.take, av.take, av.take)
      put3   = Task.zip3(av.put(10), av.put(20), av.put(30))
      result <- Task.mapBoth(put3,take3) { case (_, (r1,r2,r3)) =>
        List(r1,r2,r3)
      }
    } yield result


    val f = task.runAsync; s.tick()
    assertEquals(f.value, Some(Success(List(10,20,30))))
  }

  test("empty; take; take; take; put; put; put") { implicit s =>
    val task = for {
      av     <- MVar.empty[Int]
      take3   = Task.zip3(av.take, av.take, av.take)
      put3    = Task.zip3(av.put(10), av.put(20), av.put(30))
      result <- Task.mapBoth(take3, put3) { case ((r1,r2,r3), _) => List(r1,r2,r3) }
    } yield result

    val f = task.runAsync; s.tick()
    assertEquals(f.value, Some(Success(List(10,20,30))))
  }

  test("initial; take; put; take") { implicit s =>
    val task = for {
      av <- MVar(10)
      r1 <- av.take
      _  <- av.put(20)
      r2 <- av.take
    } yield List(r1,r2)

    assertEquals(task.runSyncMaybe, Right(List(10,20)))
  }

  test("withPadding; put; take; put; take") { implicit s =>
    val task = for {
      av <- MVar.withPadding[Int](LeftRight128)
      _ <- av.put(10)
      r1 <- av.take
      _ <- av.put(20)
      r2 <- av.take
    } yield List(r1,r2)

    assertEquals(task.runSyncMaybe, Right(List(10,20)))
  }

  test("withPadding(initial); put; take; put; take") { implicit s =>
    val task = for {
      av <- MVar.withPadding[Int](10, LeftRight128)
      r1 <- av.take
      _ <- av.put(20)
      r2 <- av.take
    } yield List(r1,r2)

    assertEquals(task.runSyncMaybe, Right(List(10,20)))
  }

  test("initial; read; take") { implicit s =>
    val task = for {
      av <- MVar(10)
      read <- av.read
      take <- av.take
    } yield read + take

    assertEquals(task.runSyncMaybe, Right(20))
  }

  test("empty; read; put") { implicit s =>
    val task = for {
      av <- MVar.empty[Int]
      r  <- Task.mapBoth(av.read, av.put(10))((r, _) => r)
    } yield r
    assertEquals(task.runSyncMaybe, Right(10))
  }

  test("put(null) throws NullPointerException") { implicit s =>
    val task = MVar.empty[String].flatMap(_.put(null))

    intercept[NullPointerException] {
      task.runSyncMaybe
    }
  }

  test("producer-consumer parallel loop") { implicit s =>
    // Signaling option, because we need to detect completion
    type Channel[A] = MVar[Option[A]]

    def producer(ch: Channel[Int], list: List[Int]): Task[Unit] =
      list match {
        case Nil =>
          ch.put(None) // we are done!
        case head :: tail =>
          // next please
          ch.put(Some(head)).flatMap(_ => producer(ch, tail))
      }

    def consumer(ch: Channel[Int], sum: Long): Task[Long] =
      ch.take.flatMap {
        case Some(x) =>
          // next please
          consumer(ch, sum + x)
        case None =>
          Task.now(sum) // we are done!
      }

    val count = 1000000
    val sumTask = for {
      channel <- MVar(Option(0))
      producerTask = producer(channel, (0 until count).toList).executeAsync
      consumerTask = consumer(channel, 0L).executeAsync
      // Ensure they run in parallel
      sum <- Task.mapBoth(producerTask, consumerTask)((_, sum) => sum)
    } yield sum

    // Evaluate
    val f: CancelableFuture[Long] = sumTask.runAsync

    s.tick()
    assertEquals(f.value, Some(Success(count.toLong * (count - 1) / 2)))
  }

  test("stack overflow test") { implicit s =>
    // Signaling option, because we need to detect completion
    type Channel[A] = MVar[Option[A]]

    def consumer(ch: Channel[Int], sum: Long): Task[Long] =
      ch.take.flatMap {
        case Some(x) =>
          // next please
          consumer(ch, sum + x)
        case None =>
          Task.now(sum) // we are done!
      }

    val channel = MVar(Option(0)).memoize
    val count = 100000

    val consumerTask = channel.flatMap(consumer(_, 0L))

    val tasks = for (i <- 0 until count) yield channel.flatMap(_.put(Some(i)))
    val producerTask = Task.gather(tasks).flatMap(_ => channel.flatMap(_.put(None)))

    val pf = producerTask.runAsync
    val cf = consumerTask.runAsync

    s.tick()
    assertEquals(pf.value, Some(Success(())))
    assertEquals(cf.value, Some(Success(count.toLong * (count - 1) / 2)))
  }

  test("take/put test is stack safe") { implicit s =>
    val Right(ch) = MVar.empty[Int].runSyncMaybe

    def loop(n: Int, acc: Int): Task[Int] =
      if (n <= 0) Task.now(acc) else
        ch.take.flatMap { x =>
          ch.put(1).flatMap(_ => loop(n - 1, acc + x))
        }

    val count = if (Platform.isJVM) 100000 else 5000
    val f = loop(count, 0).runAsync
    s.tick()
    assertEquals(f.value, None)

    ch.put(1).runAsync
    s.tick()
    assertEquals(f.value, Some(Success(count)))
  }

  def testStackParallel(channel: MVar[Int]): (Int, Task[Int], Task[Unit]) = {
    val count = if (Platform.isJVM) 100000 else 5000

    val parallelRead = (0 until count).foldLeft(Task.now(0)) { (acc, _) =>
      Task.parMap2(acc, channel.take)((x, _) => x + 1)
    }
    val parallelWrite = (0 until count).foldLeft(Task.unit) { (acc, _) =>
      Task.parMap2(acc, channel.put(1))((_, _) => ())
    }

    (count, parallelRead, parallelWrite)
  }

  test("put is stack safe when repeated in parallel") { implicit s =>
    val task = for {
      ch <- MVar.empty[Int]
      (count, reads, writes) = testStackParallel(ch)
      _ <- writes.start
      r <- reads
    } yield r == count

    val f = task.runAsync

    s.tick()
    assertEquals(f.value, Some(Success(true)))
  }

  test("take is stack safe when repeated in parallel") { implicit s =>
    val task = for {
      ch <- MVar.empty[Int]
      (count, reads, writes) = testStackParallel(ch)
      fr <- reads.start
      _ <- writes
      r <- fr
    } yield r == count

    val f = task.runAsync

    s.tick()
    assertEquals(f.value, Some(Success(true)))
  }

  def testStackSequential(channel: MVar[Int]): (Int, Task[Int], Task[Unit]) = {
    val count = if (Platform.isJVM) 100000 else 5000

    def readLoop(n: Int, acc: Int): Task[Int] = {
      if (n > 0)
        channel.take.flatMap(_ => readLoop(n - 1, acc + 1))
      else
        Task.pure(acc)
    }

    def writeLoop(n: Int): Task[Unit] = {
      if (n > 0)
        channel.put(1).flatMap(_ => writeLoop(n - 1))
      else
        Task.pure(())
    }

    (count, readLoop(count, 0), writeLoop(count))
  }

  test("put is stack safe when repeated sequentially") { implicit s =>
    val task = for {
      channel <- MVar.empty[Int]
      (count, reads, writes) = testStackSequential(channel)
      _ <- writes.start
      r <- reads
    } yield r == count

    val f = task.runAsync

    s.tick()
    assertEquals(f.value, Some(Success(true)))
  }

  test("take is stack safe when repeated sequentially") { implicit s =>
    val task = for {
      channel <- MVar.empty[Int]
      (count, reads, writes) = testStackSequential(channel)
      fr <- reads.start
      _ <- writes
      r <- fr
    } yield r == count

    val f = task.runAsync

    s.tick()
    assertEquals(f.value, Some(Success(true)))
  }

  test("constructors are referentially transparent") { implicit s =>
    // Doing put or take twice on the same MVar will never terminate
    // so incoming tasks MUST produce different MVars when ran
    def putTwice(t: Task[MVar[Int]]) = {
      val putTask = t.flatMap(_.put(42))
      putTask.flatMap(_ => putTask).map(_ => "PUT")
    }
    def takeTwice(t: Task[MVar[Int]]) = {
      val takeTask = t.flatMap(_.take)
      takeTask.flatMap(_ => takeTask).map(_ => "TAKE")
    }

    val f = Task.gather(Seq(
      takeTwice(MVar(0)),
      takeTwice(MVar.withPadding(0, PaddingStrategy.LeftRight256)),
      putTwice(MVar.empty[Int]),
      putTwice(MVar.withPadding(PaddingStrategy.LeftRight256))
    )).runAsync

    s.tick()

    // Check termination
    assertEquals(f.value, Some(Success(Seq("TAKE", "TAKE", "PUT", "PUT"))))

  }

}
