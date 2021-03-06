package cats
package free

import scala.annotation.tailrec

import cats.data.Xor, Xor.{Left, Right}
import cats.arrow.FunctionK

/**
 * A free operational monad for some functor `S`. Binding is done
 * using the heap instead of the stack, allowing tail-call
 * elimination.
 */
sealed abstract class Free[S[_], A] extends Product with Serializable {

  import Free.{ Pure, Suspend, Gosub }

  final def map[B](f: A => B): Free[S, B] =
    flatMap(a => Pure(f(a)))

  /**
   * Bind the given continuation to the result of this computation.
   * All left-associated binds are reassociated to the right.
   */
  final def flatMap[B](f: A => Free[S, B]): Free[S, B] =
    Gosub(this, f)

  /**
   * Catamorphism. Run the first given function if Pure, otherwise,
   * the second given function.
   */
  final def fold[B](r: A => B, s: S[Free[S, A]] => B)(implicit S: Functor[S]): B =
    resume.fold(s, r)

  /** Takes one evaluation step in the Free monad, re-associating left-nested binds in the process. */
  @tailrec
  final def step: Free[S, A] = this match {
    case Gosub(Gosub(c, f), g) => c.flatMap(cc => f(cc).flatMap(g)).step
    case Gosub(Pure(a), f) => f(a).step
    case x => x
  }

  /**
   * Evaluate a single layer of the free monad.
   */
  @tailrec
  final def resume(implicit S: Functor[S]): S[Free[S, A]] Xor A = this match {
    case Pure(a) => Right(a)
    case Suspend(t) => Left(S.map(t)(Pure(_)))
    case Gosub(c, f) =>
      c match {
        case Pure(a) => f(a).resume
        case Suspend(t) => Left(S.map(t)(f))
        case Gosub(d, g) => d.flatMap(dd => g(dd).flatMap(f)).resume
      }
  }

  /**
   * Run to completion, using a function that extracts the resumption
   * from its suspension functor.
   */
  final def go(f: S[Free[S, A]] => Free[S, A])(implicit S: Functor[S]): A = {
    @tailrec def loop(t: Free[S, A]): A =
      t.resume match {
        case Left(s) => loop(f(s))
        case Right(r) => r
      }
    loop(this)
  }

  /**
   * Run to completion, using the given comonad to extract the
   * resumption.
   */
  final def run(implicit S: Comonad[S]): A =
    go(S.extract)

  /**
   * Run to completion, using a function that maps the resumption
   * from `S` to a monad `M`.
   */
  final def runM[M[_]](f: S[Free[S, A]] => M[Free[S, A]])(implicit S: Functor[S], M: Monad[M]): M[A] = {
    def runM2(t: Free[S, A]): M[A] = t.resume match {
      case Left(s) => Monad[M].flatMap(f(s))(runM2)
      case Right(r) => Monad[M].pure(r)
    }
    runM2(this)
  }

  /**
   * Run to completion, using monadic recursion to evaluate the
   * resumption in the context of `S`.
   */
  final def runTailRec(implicit S: MonadRec[S]): S[A] = {
    def step(rma: Free[S, A]): S[Xor[Free[S, A], A]] =
      rma match {
        case Pure(a) =>
          S.pure(Xor.right(a))
        case Suspend(ma) =>
          S.map(ma)(Xor.right(_))
        case Gosub(curr, f) =>
          curr match {
            case Pure(x) =>
              S.pure(Xor.left(f(x)))
            case Suspend(mx) =>
              S.map(mx)(x => Xor.left(f(x)))
            case Gosub(prev, g) =>
              S.pure(Xor.left(prev.flatMap(w => g(w).flatMap(f))))
          }
      }
    S.tailRecM(this)(step)
  }

  /**
   * Catamorphism for `Free`.
   *
   * Run to completion, mapping the suspension with the given
   * transformation at each step and accumulating into the monad `M`.
   *
   * This method uses `MonadRec[M]` to provide stack-safety.
   */
  final def foldMap[M[_]](f: FunctionK[S, M])(implicit M: MonadRec[M]): M[A] =
    M.tailRecM(this)(_.step match {
      case Pure(a) => M.pure(Xor.right(a))
      case Suspend(sa) => M.map(f(sa))(Xor.right)
      case Gosub(c, g) => M.map(c.foldMap(f))(cc => Xor.left(g(cc)))
    })

  /**
   * Compile your free monad into another language by changing the
   * suspension functor using the given natural transformation `f`.
   *
   * If your natural transformation is effectful, be careful. These
   * effects will be applied by `compile`.
   */
  final def compile[T[_]](f: FunctionK[S, T]): Free[T, A] =
    foldMap[Free[T, ?]] {
      new FunctionK[S, Free[T, ?]] {
        def apply[B](fa: S[B]): Free[T, B] = Suspend(f(fa))
      }
    }(Free.freeMonad)

  override def toString(): String =
    "Free(...)"
}

object Free {

  /**
   * Return from the computation with the given value.
   */
  private[free] final case class Pure[S[_], A](a: A) extends Free[S, A]

  /** Suspend the computation with the given suspension. */
  private[free] final case class Suspend[S[_], A](a: S[A]) extends Free[S, A]

  /** Call a subroutine and continue with the given function. */
  private[free] final case class Gosub[S[_], B, C](c: Free[S, C], f: C => Free[S, B]) extends Free[S, B]

  /**
   * Lift a pure `A` value into the free monad.
   */
  def pure[S[_], A](a: A): Free[S, A] = Pure(a)

  /**
   * Lift an `F[A]` value into the free monad.
   */
  def liftF[F[_], A](value: F[A]): Free[F, A] = Suspend(value)

  /**
   * Suspend the creation of a `Free[F, A]` value.
   */
  def suspend[F[_], A](value: => Free[F, A]): Free[F, A] =
    pure(()).flatMap(_ => value)

  /**
   * This method is used to defer the application of an Inject[F, G]
   * instance. The actual work happens in
   * `FreeInjectPartiallyApplied#apply`.
   *
   * This method exists to allow the `F` and `G` parameters to be
   * bound independently of the `A` parameter below.
   */
  def inject[F[_], G[_]]: FreeInjectPartiallyApplied[F, G] =
    new FreeInjectPartiallyApplied

  /**
   * Pre-application of an injection to a `F[A]` value.
   */
  final class FreeInjectPartiallyApplied[F[_], G[_]] private[free] {
    def apply[A](fa: F[A])(implicit I: Inject[F, G]): Free[G, A] =
      Free.liftF(I.inj(fa))
  }

  /**
   * `Free[S, ?]` has a monad for any type constructor `S[_]`.
   */
  implicit def freeMonad[S[_]]: MonadRec[Free[S, ?]] =
    new MonadRec[Free[S, ?]] {
      def pure[A](a: A): Free[S, A] = Free.pure(a)
      override def map[A, B](fa: Free[S, A])(f: A => B): Free[S, B] = fa.map(f)
      def flatMap[A, B](a: Free[S, A])(f: A => Free[S, B]): Free[S, B] = a.flatMap(f)
      def tailRecM[A, B](a: A)(f: A => Free[S, A Xor B]): Free[S, B] =
        f(a).flatMap(_ match {
          case Left(a1) => tailRecM(a1)(f) // recursion OK here, since Free is lazy
          case Right(b) => pure(b)
        })
    }

  /**
   * Perform a stack-safe monadic fold from the source context `F`
   * into the target monad `G`.
   *
   * This method can express short-circuiting semantics. Even when
   * `fa` is an infinite structure, this method can potentially
   * terminate if the `foldRight` implementation for `F` and the
   * `tailRecM` implementation for `G` are sufficiently lazy.
   */
  def foldLeftM[F[_]: Foldable, G[_]: MonadRec, A, B](fa: F[A], z: B)(f: (B, A) => G[B]): G[B] =
    unsafeFoldLeftM[F, Free[G, ?], A, B](fa, z) { (b, a) =>
      Free.liftF(f(b, a))
    }.runTailRec

  private def unsafeFoldLeftM[F[_], G[_], A, B](fa: F[A], z: B)(f: (B, A) => G[B])(implicit F: Foldable[F], G: Monad[G]): G[B] =
    F.foldRight(fa, Always((w: B) => G.pure(w))) { (a, lb) =>
      Always((w: B) => G.flatMap(f(w, a))(lb.value))
    }.value.apply(z)
}
