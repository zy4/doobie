// Copyright (c) 2013-2018 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.tagless

import cats._
import cats.effect._
import cats.implicits._
import doobie.tagless.async._
import fs2.Stream
import fs2.Stream.eval_

/**
 * A bundle of configuration sufficient to interpret programs depending on `Connection[F]`, using
 * a provided `Interpreter[F]`, a transactional `Strategy[F]`, and a `Connector[F]` to provide
 * fresh connections.
 */
@SuppressWarnings(Array("org.wartremover.warts.Overloading"))
final case class Transactor[F[_]](
  interp:    Interpreter[F],
  strategy:  Strategy[F],
  connector: Connector[F]
) {

  /**
   * `Resource` yielding a `Connection[F]`, which will be closed after use. Note that `strategy` is
   * not consulted; any configuration or transactional handling must be performed manually.
   */
  def connect(implicit ev: Functor[F]): Resource[F, Connection[F]] =
    Resource.make(connector.connect.map(interp.forConnection))(_.jdbc.close)

  /**
   * Apply a `Connection[F]` to `f`, with transaction handling as defined by `strategy`, yielding
   * an `F[A]`.
   */
  def transact[A](f: Connection[F] => F[A])(implicit ev: Bracket[F, Throwable]): F[A] =
    connect.use { c =>
      val xa = strategy.before(c) *> f(c) <* strategy.after(c)
      xa.onError { case _ => strategy.onError(c) }
    }

  /**
   * Apply a `Connection[F]` to `f`, with transaction handling as defined by `strategy`, yielding a
   * `Stream[F, A]`.
   */
  def transact[A](f: Connection[F] => Stream[F, A])(implicit ev: Functor[F]): Stream[F, A] =
    Stream.resource(connect).flatMap { c =>
      val sxa = eval_(strategy.before(c)) ++ f(c) ++ eval_(strategy.after(c))
      sxa.onError { case _ => eval_(strategy.onError(c)) }
    }

}
