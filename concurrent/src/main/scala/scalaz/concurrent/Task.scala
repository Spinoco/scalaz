package scalaz.concurrent

import java.util.concurrent.{TimeoutException, ConcurrentLinkedQueue, ExecutorService}
import java.util.concurrent.atomic.{AtomicInteger, AtomicBoolean}

import scalaz.{Catchable, Nondeterminism, Traverse, \/, -\/, \/-}
import scalaz.syntax.monad._
import scalaz.syntax.id.ToIdOps
import scalaz.std.list._

import collection.JavaConversions._

import scala.concurrent.SyncVar


/** 
 * `Task[A]` is a `scalaz.concurrent.Future[Throwable \/ A]`,
 * with some convenience functions for handling exceptions. Its 
 * `Monad` and `Nondeterminism` instances are derived from `Future`.
 * 
 * `Task` (and `Future`) differ in several key ways from 
 * `scalaz.concurrent.Promise` and the `Future` implementation in 
 * Scala 2.10 , and have a number of advantages. See the documentation
 * for `scalaz.concurrent.Future` for more information. 
 * 
 * `Task` is exception-safe when constructed using the primitives
 * in the companion object, but when calling the constructor, you
 * are responsible for ensuring the exception safety of the provided
 * `Future`. 
 */
class Task[+A](val get: Future[Throwable \/ A]) {
  
  def flatMap[B](f: A => Task[B]): Task[B] = 
    new Task(get flatMap {
      case -\/(e) => Future.now(-\/(e))
      case \/-(a) => f(a).get
    })

  def map[B](f: A => B): Task[B] = 
    new Task(get map { _ map f })
  
  /** 'Catches' exceptions in the given task and returns them as values. */
  def attempt: Task[Throwable \/ A] =
    new Task(get map { 
      case -\/(e) => \/-(-\/(e))
      case \/-(a) => \/-(\/-(a))
    })

  /** 
   * Returns a new `Task` in which `f` is scheduled to be run on completion. 
   * This would typically be used to release any resources acquired by this
   * `Task`.
   */
  def onFinish(f: Option[Throwable] => Task[Unit]): Task[A] =
    new Task(get flatMap {
      case -\/(e) => f(Some(e)).get *> Future.now(-\/(e))
      case r => f(None).get *> Future.now(r)
    })
  
  /** 
   * Calls `attempt` and handles some exceptions using the given partial
   * function. Any nonmatching exceptions are reraised. 
   */
  def handle[B>:A](f: PartialFunction[Throwable,B]): Task[B] = 
    attempt flatMap {
      case -\/(e) => f.lift(e) map (Task.now) getOrElse Task.fail(e)
      case \/-(a) => Task.now(a)
    }

  /** 
   * Runs this `Task`, and if it fails with an exception, runs `t2`. 
   * This is rather coarse-grained. Use `attempt`, `handle`, and
   * `flatMap` for more fine grained control of exception handling. 
   */
  def or[B>:A](t2: Task[B]): Task[B] = 
    new Task(this.get flatMap { 
      case -\/(e) => t2.get
      case a => Future.now(a)
    })
  
  /** 
   * Run this `Task` and block until its result is available. This will
   * throw any exceptions generated by the `Task`. To return exceptions
   * in an `\/`, use `attemptRun`.
   */
  def run: A = get.run match {
    case -\/(e) => throw e
    case \/-(a) => a
  }

  /** Like `run`, but returns exceptions as values. */
  def attemptRun: Throwable \/ A =
    try get.run catch { case t: Throwable => -\/(t) }

  /**
   * Run this `Task` and block until its result is available. This will
   * throw any exceptions generated by the `Task`. To return exceptions
   * in an `\/`, use `attemptRunFor`.
   * If supplied timeout is exceeded the Task wil terminate early 
   * and throws the TimeoutException with all remianing work to be terminated early. 
   */
  def runFor(timeout: Long) = attemptRunFor(timeout) match {
    case -\/(e) => throw e
    case \/-(a) => a
  }

  /** Like `runFor`, but returns exceptions as values. */
  def attemptRunFor(timeout: Long) = {
    val sync = new SyncVar[Throwable \/ A]
    val interrupt = new AtomicBoolean(false)
    runAsyncInterruptibly(sync.put(_), interrupt)
    sync.get(timeout).getOrElse({ 
      interrupt.set(true)
      (new TimeoutException()).left
    })
  }


  /**
   * Run this computation to obtain an `A`, so long as `cancel` remains false. 
   * Because of trampolining, we get frequent opportunities to cancel
   * while stepping through the trampoline, this should provide a fairly 
   * robust means of cancellation.  
   */
  def runAsyncInterruptibly(f: (Throwable \/ A) => Unit, cancel: AtomicBoolean): Unit =
    get.runAsyncInterruptibly(f, cancel)

  /**
   * Run this computation to obtain either a result or an exception, then
   * invoke the given callback. Any pure, non-asynchronous computation at the 
   * head of this `Future` will be forced in the calling thread. At the first 
   * `Async` encountered, control to whatever thread backs the `Async` and 
   * this function returns immediately.
   */
  def runAsync(f: (Throwable \/ A) => Unit): Unit =
    get.runAsync(f)
}

object Task {
  
  implicit val taskInstance = new Nondeterminism[Task] with Catchable[Task] { 
    val F = Nondeterminism[Future]
    def point[A](a: => A) = new Task(Future.now(Try(a))) 
    def bind[A,B](a: Task[A])(f: A => Task[B]): Task[B] = 
      a flatMap f 
    def chooseAny[A](h: Task[A], t: Seq[Task[A]]): Task[(A, Seq[Task[A]])] =
      new Task ( F.map(F.chooseAny(h.get, t map (_ get))) { case (a, residuals) => 
        a.map((_, residuals.map(new Task(_))))
      })
    override def gatherUnordered[A](fs: Seq[Task[A]]): Task[List[A]] = {
      new Task (F.map(F.gatherUnordered(fs.map(_ get)))(eithers => 
        Traverse[List].sequenceU(eithers) 
      ))
    }
    def fail[A](e: Throwable): Task[A] = new Task(Future.now(-\/(e)))
    def attempt[A](a: Task[A]): Task[Throwable \/ A] = a.attempt
  }

  /**
   * Combines all tasks to be run in parallel, order non-deterministic.  
   * On last completed task will collect and return list of completed futures.
   * If any task fails with exception it terminates early propagating exception of that task to result task. 
   * However on the early exit all the tasks that were run are completing their job and may therefore use resources.
   * When desired, combinator may try to cancel any not yet run tasks by setting cancelOnEarlyExit to true, 
   * meaning that only the not scheduled tasks will not get run. 
   * Unlike the Task's [[scalaz.Nondeterminism.gatherUnordered]] it won't run the tasks, but rather combines them in non-blocking way to be later run 
   */
  def gatherUnordered[A](tasks: Seq[Task[A]], cancelOnEarlyExit: Boolean = false): Task[List[A]] = {
    // Unfortunately we cannot reuse the future's combinator 
    // due to early terminating requirement on task
    // when task fails.  This also makes implementation a bit trickier

    tasks match {
      case Seq() => Task.now(List())
      case Seq(t) => t.map(List(_))
      case _ => Task.async { cb =>
        val interrupt = new AtomicBoolean(false)
        val results = new ConcurrentLinkedQueue[A]
        val togo = new AtomicInteger(tasks.size)
      
        tasks.foreach { t =>

          val handle: (Throwable \/ A) => Unit = {
            case \/-(success) =>
              results.add(success)
              //only last completed f will hit the 0 here. 
              if (togo.decrementAndGet() == 0) {
                cb(results.toList.right)
              } 

            case -\/(failure) =>
              // togo is decremented straight to 0 to prevent any future callbacks of async. 
              // however, as other tasks may failed, the one here must prevent 
              // to call the callback in that case. Standard cmpAndSet construct is used to make
              // sure we wouldn't race
              def shouldCallBack:Boolean =  {
                val current = togo.get
                if (current > 0) {
                  if (togo.compareAndSet(current,0)) true else  shouldCallBack
                } else {
                  false
                }
              }
            
              if(shouldCallBack) {
                cb(failure.left)
                if(cancelOnEarlyExit) interrupt.set(true) //cancel any computation not running yet
              }
            
          }

          t.runAsyncInterruptibly(handle, interrupt)

        }
        
      }
        
    }


  }


  /** A `Task` which fails with the given `Throwable`. */
  def fail(e: Throwable): Task[Nothing] = new Task(Future.now(-\/(e)))

  /** Convert a strict value to a `Task`. Also see `delay`. */
  def now[A](a: A): Task[A] = new Task(Future.now(\/-(a)))

  /**
   * Promote a non-strict value to a `Task`, catching exceptions in 
   * the process. Note that since `Task` is unmemoized, this will 
   * recompute `a` each time it is sequenced into a larger computation. 
   * Memoize `a` with a lazy value before calling this function if 
   * memoization is desired.  
   */
  def delay[A](a: => A): Task[A] = suspend(now(a))

  /**
   * Produce `f` in the main trampolining loop, `Future.step`, using a fresh
   * call stack. The standard trampolining primitive, useful for avoiding
   * stack overflows. 
   */
  def suspend[A](a: => Task[A]): Task[A] = new Task(Future.suspend(
    Try(a.get) match {
      case -\/(e) => Future.now(-\/(e))
      case \/-(f) => f
    }))

  /** Create a `Future` that will evaluate `a` using the given `ExecutorService`. */
  def apply[A](a: => A)(implicit pool: ExecutorService = Strategy.DefaultExecutorService): Task[A] =
    new Task(Future(Try(a))(pool))

  /**
   * Returns a `Future` that produces the same result as the given `Future`, 
   * but forks its evaluation off into a separate (logical) thread, using
   * the given `ExecutorService`. Note that this forking is only described
   * by the returned `Future`--nothing occurs until the `Future` is run. 
   */
  def fork[A](a: => Task[A])(implicit pool: ExecutorService = Strategy.DefaultExecutorService): Task[A] = 
    apply(a).join

  /**
   * Create a `Future` from an asynchronous computation, which takes the form
   * of a function with which we can register a callback. This can be used
   * to translate from a callback-based API to a straightforward monadic
   * version. See `Task.async` for a version that allows for asynchronous
   * exceptions. 
   */
  def async[A](register: ((Throwable \/ A) => Unit) => Unit): Task[A] =
    new Task(Future.async(register))

  /** Utility function - evaluate `a` and catch and return any exceptions. */
  def Try[A](a: => A): Throwable \/ A =
    try \/-(a) catch { case e: Exception => -\/(e) }

}
