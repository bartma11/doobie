// Copyright (c) 2013-2018 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.util

import doobie.free.connection.{ ConnectionIO, ConnectionOp, setAutoCommit, commit, rollback, close, unit, delay, raiseError }
import doobie.free.KleisliInterpreter
import doobie.util.lens._
import doobie.util.yolo.Yolo

import cats.{ Monad, MonadError, ~> }
import cats.data.Kleisli
import cats.implicits._
import cats.effect.{ Bracket, Sync, Async, ContextShift }
import cats.effect.syntax.bracket._
import fs2.Stream
import fs2.Stream.{ eval, eval_ }

import java.sql.{ Connection, DriverManager }
import javax.sql.DataSource
import java.util.concurrent.{ Executors, ThreadFactory }

import scala.concurrent.ExecutionContext
import scala.util.control.NonFatal

object transactor  {

  import doobie.free.connection.AsyncConnectionIO

  /** @group Type Aliases */
  type Interpreter[M[_]] = ConnectionOp ~> Kleisli[M, Connection, ?]

  /**
   * Data type representing the common setup, error-handling, and cleanup strategy associated with
   * an SQL transaction. A `Transactor` uses a `Strategy` to wrap programs prior to execution.
   * @param before a program to prepare the connection for use
   * @param after  a program to run on success
   * @param oops   a program to run on failure (catch)
   * @param always a programe to run in all cases (finally)
   * @group Data Types
   */
  final case class Strategy(
    before: ConnectionIO[Unit],
    after:  ConnectionIO[Unit],
    oops:   ConnectionIO[Unit],
    always: ConnectionIO[Unit]
  ) {

    /** Natural transformation that wraps a `ConnectionIO` program. */
    @deprecated("Use Transactor.transB", "0.7.0")
    val wrap = λ[ConnectionIO ~> ConnectionIO] { ma =>
      (before *> ma <* after)
        .onError { case NonFatal(_) => oops }
        .guarantee(always)
    }

    /** Natural transformation that wraps a `ConnectionIO` stream. */
    @deprecated("Use Transactor.transP", "0.7.0")
    val wrapP = λ[Stream[ConnectionIO, ?] ~> Stream[ConnectionIO, ?]] { pa =>
      (eval_(before) ++ pa ++ eval_(after))
        .onError { case NonFatal(e) => eval_(oops) ++ eval_(delay(throw e)) }
        .onFinalize(always)
    }

    /**
     * Natural transformation that wraps a `Kleisli` program, using the provided interpreter to
     * interpret the `before`/`after`/`oops`/`always` strategy.
     */
    @deprecated("Use Transactor.execB", "0.7.0")
    def wrapK[M[_]: MonadError[?[_], Throwable]](interp: Interpreter[M]) =
      λ[Kleisli[M, Connection, ?] ~> Kleisli[M, Connection, ?]] { ka =>
        import doobie.syntax.monaderror._

        val beforeʹ = before foldMap interp
        val afterʹ  = after  foldMap interp
        val oopsʹ   = oops   foldMap interp
        val alwaysʹ = always foldMap interp
        (beforeʹ *> ka <* afterʹ)
          .onError { case NonFatal(_) => oopsʹ }
          .guarantee(alwaysʹ)
      }

  }
  object Strategy {

    /** @group Lenses */ val before: Strategy @> ConnectionIO[Unit] = Lens(_.before, (a, b) => a.copy(before = b))
    /** @group Lenses */ val after:  Strategy @> ConnectionIO[Unit] = Lens(_.after,  (a, b) => a.copy(after  = b))
    /** @group Lenses */ val oops:   Strategy @> ConnectionIO[Unit] = Lens(_.oops,   (a, b) => a.copy(oops   = b))
    /** @group Lenses */ val always: Strategy @> ConnectionIO[Unit] = Lens(_.always, (a, b) => a.copy(always = b))

    /**
     * A default `Strategy` with the following properties:
     * - Auto-commit will be set to `false`;
     * - the transaction will `commit` on success and `rollback` on failure;
     * - and finally the connection will be closed in all cases.
     * @group Constructors
     */
    val default = Strategy(setAutoCommit(false), commit, rollback, close)

    /**
     * A no-op `Strategy`. All actions simply return `()`.
     * @group Constructors
     */
    val void = Strategy(unit, unit, unit, unit)

  }

  /**
   * A thin wrapper around a source of database connections, an interpreter, and a strategy for
   * running programs, parameterized over a target monad `M` and an arbitrary wrapped value `A`.
   * Given a stream or program in `ConnectionIO` or a program in `Kleisli`, a `Transactor` can
   * discharge the doobie machinery and yield an effectful stream or program in `M`.
   * @tparam M a target effect type; typically `IO`
   * @group Data Types
   */
  sealed abstract class Transactor[M[_]] { self =>
    /** An arbitrary value that will be handed back to `connect` **/
    type A

    /** An arbitrary value, meaningful to the instance **/
    def kernel: A

    /** A program in `M` that can provide a database connection, given the kernel **/
    def connect: A => M[Connection]

    /** A natural transformation for interpreting `ConnectionIO` **/
    def interpret: Interpreter[M]

    /** A `Strategy` for running a program on a connection **/
    def strategy: Strategy

    /** Construct a [[Yolo]] for REPL experimentation. */
    def yolo(implicit ev1: Sync[M]): Yolo[M] = new Yolo(this)

    /**
     * Construct a program to peform arbitrary configuration on the kernel value (changing the
     * timeout on a connection pool, for example). This can be the basis for constructing a
     * configuration language for a specific kernel type `A`, whose operations can be added to
     * compatible `Transactor`s via implicit conversion.
     * @group Configuration
     */
    def configure[B](f: A => M[B]): M[B] =
      f(kernel)

    /**
     * Natural transformation equivalent to `exec` that does not use the provided `Strategy` and
     * instead directly binds the `Connection` provided by `connect`. This can be useful in cases
     * where transactional handling is unsupported or undesired.
     * @group Natural Transformations
     */
    def rawExec(implicit ev: Monad[M]): Kleisli[M, Connection, ?] ~> M =
      λ[Kleisli[M, Connection, ?] ~> M](k => connect(kernel).flatMap(k.run))

    /**
     * Natural transformation that provides a connection and binds through a `Kleisli` program
     * using the given `Strategy`, yielding an independent program in `M`.
     * @group Natural Transformations
     */
    @deprecated("Use execB", "0.7.0")
    def exec(implicit ev: MonadError[M, Throwable]): Kleisli[M, Connection, ?] ~> M =
      strategy.wrapK(interpret) andThen rawExec(ev)

    /**
      * Natural transformation that provides a connection and binds through a `Kleisli` program
      * using the given `Strategy`, yielding an independent program in `M`.
      * @group Natural Transformations
      */
    def execB(implicit ev: Bracket[M, Throwable]): Kleisli[M, Connection, ?] ~> M =
      λ[Kleisli[M, Connection, ?] ~> M] { ka =>
        val beforeʹ = strategy.before foldMap interpret
        val afterʹ  = strategy.after  foldMap interpret
        val oopsʹ   = strategy.oops   foldMap interpret
        val alwaysʹ = strategy.always foldMap interpret

        val wrap = (beforeʹ *> ka <* afterʹ)
          .onError { case NonFatal(_) => oopsʹ }

        connect(kernel).bracket(wrap.run)(alwaysʹ.run)
      }

    /**
     * Natural transformation equivalent to `trans` that does not use the provided `Strategy` and
     * instead directly binds the `Connection` provided by `connect`. This can be useful in cases
     * where transactional handling is unsupported or undesired.
     * @group Natural Transformations
     */
    def rawTrans(implicit ev: Monad[M]): ConnectionIO ~> M =
      λ[ConnectionIO ~> M](f => connect(kernel).flatMap(f.foldMap(interpret).run))

    /**
      * Natural transformation that provides a connection and binds through a `ConnectionIO` program
      * interpreted via the given interpreter, using the given `Strategy`, yielding an independent
      * program in `M`. This is the most common way to run a doobie program.
      * @group Natural Transformations
      */
    @deprecated("Use transB", "0.7.0")
    def trans(implicit ev: Monad[M]): ConnectionIO ~> M =
      strategy.wrap andThen rawTrans

    /**
     * Natural transformation that provides a connection and binds through a `ConnectionIO` program
     * interpreted via the given interpreter, using the given `Strategy`, yielding an independent
     * program in `M`. This is the most common way to run a doobie program.
     * @group Natural Transformations
     */
    def transB(implicit ev: Bracket[M, Throwable]): ConnectionIO ~> M =
      λ[ConnectionIO ~> M] { f =>
        val wrap = (strategy.before *> f <* strategy.after)
          .onError { case NonFatal(_) => strategy.oops }
        def release(c: Connection) = run(c).apply(strategy.always)

        connect(kernel).bracket(c => run(c).apply(wrap))(release)
      }

    def rawTransP[T](implicit ev: Monad[M]): Stream[ConnectionIO, ?] ~> Stream[M, ?] =
      λ[Stream[ConnectionIO, ?] ~> Stream[M, ?]] { s =>
        eval(connect(kernel)).flatMap(c => s.translate(run(c)))
      }

    def transP(implicit ev: Monad[M]): Stream[ConnectionIO, ?] ~> Stream[M, ?] =
      λ[Stream[ConnectionIO, ?] ~> Stream[M, ?]] { s =>
        val acquire = connect(kernel)
        def release(c: Connection) = run(c).apply(strategy.always)

        Stream.bracket(acquire)(release).flatMap { c =>
          (eval_(strategy.before) ++ s ++ eval_(strategy.after))
            .onError { case NonFatal(e) => eval_(strategy.oops) ++ eval_(raiseError(e)) }
            .translate(run(c))
        }
      }

    private def run(c: Connection)(implicit ev: Monad[M]): ConnectionIO ~> M =
      λ[ConnectionIO ~> M] { f =>
        f.foldMap(interpret).run(c)
      }

    @SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
    def copy(
      kernel0: A = self.kernel,
      connect0: A => M[Connection] = self.connect,
      interpret0: Interpreter[M] = self.interpret,
      strategy0: Strategy = self.strategy
    ): Transactor.Aux[M, A] = new Transactor[M] {
      type A = self.A
      val kernel = kernel0
      val connect = connect0
      val interpret = interpret0
      val strategy = strategy0
    }
  }

  object Transactor {

    def apply[M[_], A0](
      kernel0: A0,
      connect0: A0 => M[Connection],
      interpret0: Interpreter[M],
      strategy0: Strategy
    ): Transactor.Aux[M, A0] = new Transactor[M] {
      type A = A0
      val kernel = kernel0
      val connect = connect0
      val interpret = interpret0
      val strategy = strategy0
    }

    type Aux[M[_], A0] = Transactor[M] { type A = A0  }

    /** @group Lenses */ def kernel   [M[_], A]: Transactor.Aux[M, A] Lens A                    = Lens(_.kernel,    (a, b) => a.copy(kernel0    = b))
    /** @group Lenses */ def connect  [M[_], A]: Transactor.Aux[M, A] Lens (A => M[Connection]) = Lens(_.connect,   (a, b) => a.copy(connect0   = b))
    /** @group Lenses */ def interpret[M[_]]: Transactor[M] Lens Interpreter[M]       = Lens(_.interpret, (a, b) => a.copy(interpret0 = b))
    /** @group Lenses */ def strategy [M[_]]: Transactor[M] Lens Strategy             = Lens(_.strategy,  (a, b) => a.copy(strategy0  = b))
    /** @group Lenses */ def before   [M[_]]: Transactor[M] Lens ConnectionIO[Unit]   = strategy[M] >=> Strategy.before
    /** @group Lenses */ def after    [M[_]]: Transactor[M] Lens ConnectionIO[Unit]   = strategy[M] >=> Strategy.after
    /** @group Lenses */ def oops     [M[_]]: Transactor[M] Lens ConnectionIO[Unit]   = strategy[M] >=> Strategy.oops
    /** @group Lenses */ def always   [M[_]]: Transactor[M] Lens ConnectionIO[Unit]   = strategy[M] >=> Strategy.always

    /**
     * Construct a constructor of `Transactor[M, D]` for some `D <: DataSource` by partial
     * application of `M`, which cannot be inferred in general. This follows the pattern described
     * [here](http://tpolecat.github.io/2015/07/30/infer.html).
     * @group Constructors
     */
     object fromDataSource {
       def apply[M[_]] = new FromDataSourceUnapplied[M]

      /**
       * Constructor of `Transactor[M, D]` fixed for `M`; see the `apply` method for details.
       * @group Constructors (Partially Applied)
       */
      class FromDataSourceUnapplied[M[_]] {
        def apply[A <: DataSource](
          dataSource: A,
          connectEC:  ExecutionContext,
          transactEC: ExecutionContext
        )(implicit ev: Async[M],
                   cs: ContextShift[M]
        ): Transactor.Aux[M, A] = {
          val connect = (dataSource: A) => cs.evalOn(connectEC)(ev.delay(dataSource.getConnection))
          val interp  = KleisliInterpreter[M](transactEC).ConnectionInterpreter
          Transactor(dataSource, connect, interp, Strategy.default)
        }
      }

    }

    /**
     * Construct a `Transactor` that wraps an existing `Connection`, using a `Strategy` that does
     * not close the connection but is otherwise identical to `Strategy.default`.
     * @param connection a raw JDBC `Connection` to wrap
     * @param transactEC an `ExecutionContext` for blocking database operations
     * @group Constructors
     */
    def fromConnection[M[_]: Async: ContextShift](
      connection: Connection,
      transactEC: ExecutionContext
    ): Transactor.Aux[M, Connection] = {
      val connect = (c: Connection) => Async[M].pure(c)
      val interp  = KleisliInterpreter[M](transactEC).ConnectionInterpreter
      Transactor(connection, connect, interp, Strategy.default.copy(always = unit))
    }

    /**
     * Module of constructors for `Transactor` that use the JDBC `DriverManager` to allocate
     * connections. Note that `DriverManager` is unbounded and will happily allocate new connections
     * until server resources are exhausted. It is usually preferable to use `DataSourceTransactor`
     * with an underlying bounded connection pool (as with `H2Transactor` and `HikariTransactor` for
     * instance). Blocking operations on `DriverManagerTransactor` are executed on an unbounded
     * cached daemon thread pool, so you are also at risk of exhausting system threads. TL;DR this
     * is fine for console apps but don't use it for a web application.
     * @group Constructors
     */
    @SuppressWarnings(Array("org.wartremover.warts.Overloading"))
    object fromDriverManager {

      // An unbounded cached pool of daemon threads.
      private val blockingContext: ExecutionContext =
        ExecutionContext.fromExecutor(Executors.newCachedThreadPool(
          new ThreadFactory {
            def newThread(r: Runnable): Thread = {
              val th = new Thread(r)
              th.setName(s"doobie-fromDriverManager-pool-${th.getId}")
              th.setDaemon(true)
              th
            }
          }
        ))

      @SuppressWarnings(Array("org.wartremover.warts.NonUnitStatements"))
      private def create[M[_]](
        driver:   String,
        conn:  => Connection,
        strategy: Strategy
      )(implicit am: Async[M], cs: ContextShift[M]): Transactor.Aux[M, Unit] =
        Transactor(
          (),
          _ => cs.evalOn(blockingContext)(am.delay { Class.forName(driver); conn }),
          KleisliInterpreter[M](blockingContext).ConnectionInterpreter,
          strategy
        )

      /**
       * Construct a new `Transactor` that uses the JDBC `DriverManager` to allocate connections.
       * @param driver     the class name of the JDBC driver, like "org.h2.Driver"
       * @param url        a connection URL, specific to your driver
       */
      def apply[M[_]: Async: ContextShift](
        driver: String,
        url:    String
      ): Transactor.Aux[M, Unit] =
        create(driver, DriverManager.getConnection(url), Strategy.default)

      /**
       * Construct a new `Transactor` that uses the JDBC `DriverManager` to allocate connections.
       * @param driver     the class name of the JDBC driver, like "org.h2.Driver"
       * @param url        a connection URL, specific to your driver
       * @param user       database username
       * @param pass       database password
       */
      def apply[M[_]: Async: ContextShift](
        driver: String,
        url:    String,
        user:   String,
        pass:   String
      ): Transactor.Aux[M, Unit] =
        create(driver, DriverManager.getConnection(url, user, pass), Strategy.default)

      /**
       * Construct a new `Transactor` that uses the JDBC `DriverManager` to allocate connections.
       * @param driver     the class name of the JDBC driver, like "org.h2.Driver"
       * @param url        a connection URL, specific to your driver
       * @param info       a `Properties` containing connection information (see `DriverManager.getConnection`)
       */
      def apply[M[_]: Async: ContextShift](
        driver: String,
        url:    String,
        info:   java.util.Properties
      ): Transactor.Aux[M, Unit] =
        create(driver, DriverManager.getConnection(url, info), Strategy.default)

    }

  }


}
