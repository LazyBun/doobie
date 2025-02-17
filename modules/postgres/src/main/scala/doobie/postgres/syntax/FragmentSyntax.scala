// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.postgres.syntax

import cats.Foldable
import cats.effect.kernel.Resource
import cats.syntax.all._
import doobie._
import doobie.implicits._
import doobie.postgres._
import doobie.postgres.implicits._
import fs2._
import fs2.text._

import java.io.StringReader

class FragmentOps(f: Fragment) {

  /**
   * Given a fragment of the form `COPY table (col, ...) FROM STDIN` construct a
   * `ConnectionIO` that inserts the values provided in `fa`, returning the number of affected
   * rows.
   */
  def copyIn[F[_]: Foldable, A](fa: F[A])(implicit ev: Text[A]): ConnectionIO[Long] = {
    // Fold with a StringBuilder and unsafeEncode to minimize allocations. Note that inserting no
    // rows is an error so we shortcut on empty input.
    // TODO: stream this rather than constructing the string in memory.
    if (fa.isEmpty) 0L.pure[ConnectionIO] else {
      val data = foldToString(fa)
      PHC.pgGetCopyAPI(PFCM.copyIn(f.query.sql, new StringReader(data)))
    }
  }

  /**
   * Given a fragment of the form `COPY table (col, ...) FROM STDIN` construct a
   * `ConnectionIO` that inserts the values provided by `stream`, returning the number of affected
   * rows. Chunks input `stream` for more efficient sending to `STDIN` with `minChunkSize`.
   */
  def copyIn[A: Text](
    stream: Stream[ConnectionIO, A],
    minChunkSize: Int
  ): ConnectionIO[Long] = {

    val byteStream: Stream[ConnectionIO, Byte] =
      stream.chunkMin(minChunkSize).map(foldToString(_)).through(utf8Encode)

    Stream.bracketCase(
      PHC.pgGetCopyAPI(PFCM.copyIn(f.query.sql))
    ){
      case (copyIn, Resource.ExitCase.Succeeded) =>
        PHC.embed(copyIn, PFCI.isActive.ifM(PFCI.endCopy.void, PFCI.unit))
      case (copyIn, _) =>
        PHC.embed(copyIn, PFCI.cancelCopy)
    }.flatMap(copyIn =>
      byteStream.chunks.evalMap(bytes =>
        PHC.embed(copyIn, PFCI.writeToCopy(bytes.toArray, 0, bytes.size))
      ) *>
      Stream.eval(PHC.embed(copyIn, PFCI.endCopy))
    ).compile.foldMonoid

  }

  /** Folds given `F` to string, encoding each `A` with `Text` instance and joining resulting strings with `\n` */
  private def foldToString[F[_]: Foldable, A](fa: F[A])(implicit ev: Text[A]): String =
    fa.foldLeft(new StringBuilder) { (b, a) =>
      ev.unsafeEncode(a, b)
      b.append("\n")
    }.toString

}

trait ToFragmentOps {
  implicit def toFragmentOps(f: Fragment): FragmentOps =
    new FragmentOps(f)
}

object fragment extends ToFragmentOps
