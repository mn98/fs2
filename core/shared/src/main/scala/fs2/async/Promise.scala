package fs2.async

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

import cats.implicits.{ catsSyntaxEither => _, _ }
import cats.effect.{ Sync, Effect, IO }

import fs2.Scheduler
import fs2.internal.LinkedMap
import java.util.concurrent.atomic.{AtomicBoolean,AtomicReference}

import Promise._

// TODO shall we move Effect and EC implicits back up here
// TODO scaladoc
final case class Promise[F[_], A] private [fs2] (ref: SyncRef[F, State[A]]) {
  def get(implicit F: Effect[F], ec: ExecutionContext): F[A] =
    F.delay(new MsgId).flatMap(getOrWait)

  def setSync(a: A)(implicit F: Effect[F], ec: ExecutionContext): F[Unit] = {
    def notifyReaders(r: State.Unset[A]): Unit =
      r.waiting.values.foreach { cb =>
        ec.execute { () => cb(a) }
      }
    // TODO if double set is allowed, this can be fast-path optimised
    // to use SyncRef.set after the first `set`, however imho this pretty much
    // never happens in practice, so it isn't really worth it
    ref.modify2 {
      case State.Set(_) => State.Set(a) -> F.unit //TODO allow double set, or fail?
      case u @ State.Unset(_) => State.Set(a) -> F.delay(notifyReaders(u))
    }.flatMap(_._2)
  }
  
  /** Like [[get]] but returns an `F[Unit]` that can be used cancel the subscription. */
  def cancellableGet(implicit F: Effect[F], ec: ExecutionContext): F[(F[A], F[Unit])] = F.delay {
    val id = new MsgId
    val get = getOrWait(id)
    val cancel = ref.modify {
      case a @ State.Set(_) => a
      case State.Unset(waiting) => State.Unset(waiting - id)
    }.void

    (get, cancel)
  }

  def timedGet(timeout: FiniteDuration, scheduler: Scheduler)(implicit F: Effect[F], ec: ExecutionContext): F[Option[A]] =
    cancellableGet.flatMap { case (g, cancelGet) =>
      scheduler.effect.delayCancellable(F.unit, timeout).flatMap { case (timer, cancelTimer) =>
        fs2.async.race(g, timer).flatMap(_.fold(a => cancelTimer.as(Some(a)), _ => cancelGet.as(None)))
      }
    }

  /**
   * Runs `f1` and `f2` simultaneously, but only the winner gets to
   * `set` to this `ref`. The loser continues running but its reference
   * to this ref is severed, allowing this ref to be garbage collected
   * if it is no longer referenced by anyone other than the loser.
   *
   * If the winner fails, the returned `F` fails as well, and this `ref`
   * is not set.
   */
  def race(f1: F[A], f2: F[A])(implicit F: Effect[F], ec: ExecutionContext): F[Unit] = F.delay {
    val refToSelf = new AtomicReference(this)
    val won = new AtomicBoolean(false)
    val win = (res: Either[Throwable,A]) => {
      // important for GC: we don't reference this ref
      // or the actor directly, and the winner destroys any
      // references behind it!
      if (won.compareAndSet(false, true)) {
        res match {
          case Left(e) =>
            refToSelf.set(null)
            throw e
          case Right(v) =>
            val action = refToSelf.getAndSet(null).setSync(v)
            unsafeRunAsync(action)(_ => IO.unit)
        }
      }
    }

    unsafeRunAsync(f1)(res => IO(win(res)))
    unsafeRunAsync(f2)(res => IO(win(res)))
  }
  
  private def getOrWait(id: MsgId)(implicit F: Effect[F], ec: ExecutionContext): F[A] = {
    def registerCallBack(cb: A => Unit): Unit = {
      def go = ref.modify2 {
        case State.Set(a) => State.Set(a) -> F.delay(cb(a))
        case State.Unset(waiting) => State.Unset(waiting.updated(id, cb)) -> F.unit
      }.flatMap(_._2)

      unsafeRunAsync(go)(_ => IO.unit)
    }

    ref.get.flatMap {
      case State.Set(a) => F.pure(a)
      case State.Unset(_) => F.async(cb => registerCallBack(x => cb(Right(x))))
      }
  }
}
object Promise {
  def empty[F[_]: Sync, A]: F[Promise[F, A]] =
    SyncRef[F, State[A]](State.Unset(LinkedMap.empty)).map(r => new Promise[F, A](r))

  private final class MsgId
  private[async] sealed abstract class State[A]
  private object State {
    final case class Set[A](a: A) extends State[A]
    final case class Unset[A](waiting: LinkedMap[MsgId, A => Unit]) extends State[A]
  }
}
