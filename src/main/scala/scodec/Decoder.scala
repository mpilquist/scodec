package scodec

import scala.language.higherKinds

import scalaz.{ \/, \/-, -\/, Monad, Monoid }
import scalaz.syntax.std.option._

import scodec.bits.BitVector

/**
 * Supports decoding a value of type `A` from a `BitVector`.
 *
 * @groupname primary Primary Members
 * @groupprio primary 0
 *
 * @groupname combinators Basic Combinators
 * @groupprio combinators 10
 *
 * @groupname coproduct Coproduct Support
 * @groupprio coproduct 13
 */
trait Decoder[A] { self =>

  /**
   * Attempts to decode a value of type `A` from the specified bit vector.
   *
   * @param bits bits to decode
   * @return error if value could not be decoded or the remaining bits and the decoded value
   * @group primary
   */
  def decode(bits: BitVector): String \/ (BitVector, A)

  /**
   * Attempts to decode a value of type `A` from the specified bit vector and discards any
   * remaining bits.
   *
   * @param bits bits to decode
   * @return error if value could not be decoded or the decoded value
   * @group primary
   */
  final def decodeValue(bits: BitVector): String \/ A =
    decode(bits) map { case (rem, value) => value }

    /**
   * Decodes a value of type `A` from the specified bit vector and discards any
   * remaining bits, throwing an `IllegalArgumentException` if an error occurs.
   *
   * @param bits bits to decode
   * @return the decoded value
   * @throws IllegalArgumentException if a decoding error occurs
   * @group primary
   */
  final def decodeValidValue(bits: BitVector): A =
    decodeValue(bits) valueOr { err => throw new IllegalArgumentException(err) }

  /**
   * Converts this decoder to a `Decoder[B]` using the supplied `A => B`.
   * @group combinators
   */
  def map[B](f: A => B): Decoder[B] = new Decoder[B] {
    def decode(bits: BitVector) = self.decode(bits) map { case (rem, a) => (rem, f(a)) }
  }

  /**
   * Converts this decoder to a `Decoder[B]` using the supplied `A => Decoder[B]`.
   * @group combinators
   */
  def flatMap[B](f: A => Decoder[B]): Decoder[B] = new Decoder[B] {
    def decode(bits: BitVector) = self.decode(bits) flatMap { case (rem, a) => f(a).decode(rem) }
  }

  /**
   * Converts this decoder to a `Decoder[B]` using the supplied `A => String \/ B`.
   * @group combinators
   */
  def emap[B](f: A => String \/ B): Decoder[B] = new Decoder[B] {
    def decode(bits: BitVector) = self.decode(bits) flatMap { case (rem, a) => f(a).map { b => (rem, b) } }
  }

  /**
   * Converts this decoder to a new decoder that fails decoding if there are remaining bits.
   * @group combinators
   */
  def complete: Decoder[A] = new Decoder[A] {
    def decode(bits: BitVector) = self.decode(bits) flatMap { case r @ (rem, a) =>
      if (rem.isEmpty) \/.right(r) else {
        \/.left {
          val max = 512
          if (rem.sizeLessThan(max + 1)) {
            val preview = rem.take(max)
            s"${preview.size} bits remaining: 0x${preview.toHex}"
          } else s"more than $max bits remaining"
        }
      }
    }
  }

  /**
   * Gets this as a `Decoder`.
   * @group combinators
   */
  def asDecoder: Decoder[A] = this

  /**
   * Converts this to a codec that fails encoding with an error.
   * @group combinators
   */
  def decodeOnly[AA >: A]: Codec[AA] = new Codec[AA] {
    def encode(a: AA) = \/.left("encoding not supported")
    def decode(bits: BitVector) = self.decode(bits)
  }
}

/** Provides functions for working with decoders. */
trait DecoderFunctions {

  /** Decodes the specified bit vector in to a value of type `A` using an implicitly available codec. */
  final def decode[A: Decoder](bits: BitVector): String \/ (BitVector, A) = Decoder[A].decode(bits)

  /**
   * Decodes the specified bit vector in to a value of type `A` using an implicitly available
   * codec and discards the remaining bits.
   */
  final def decodeValue[A: Decoder](bits: BitVector): String \/ A = Decoder[A].decodeValue(bits)

  /**
   * Decodes the specified bit vector in to a value of type `A` using an implicitly available
   * codec and discards the remaining bits or throws an `IllegalArgumentException` if decoding
   * fails.
   */
  final def decodeValidValue[A: Decoder](bits: BitVector): A = Decoder[A].decodeValidValue(bits)

  /** Decodes a tuple `(A, B)` by first decoding `A` and then using the remaining bits to decode `B`. */
  final def decodeBoth[A, B](decA: Decoder[A], decB: Decoder[B])(buffer: BitVector): String \/ (BitVector, (A, B)) =
    decodeBothCombine(decA, decB)(buffer) { (a, b) => (a, b) }

  /** Decodes a `C` by first decoding `A` and then using the remaining bits to decode `B`, then applying the decoded values to the specified function to generate a `C`. */
  final def decodeBothCombine[A, B, C](decA: Decoder[A], decB: Decoder[B])(buffer: BitVector)(f: (A, B) => C): String \/ (BitVector, C) = {
    // Note: this could be written using DecodingContext but this function is called *a lot* and needs to be very fast
    decA.decode(buffer) match {
      case e @ -\/(_) => e
      case \/-((postA, a)) =>
        decB.decode(postA) match {
          case e @ -\/(_) => e
          case \/-((rest, b)) => \/-((rest, f(a, b)))
        }
      }
  }

  /**
   * Repeatedly decodes values of type `A` from the specified vector, converts each value to a `B` and appends it to an accumulator of type `B` using the `Monoid[B]`.
   * Terminates when no more bits are available in the vector. Exits upon first decoding error.
   *
   * @return tuple consisting of the terminating error if any and the accumulated value
   */
  final def decodeAll[A: Decoder, B: Monoid](buffer: BitVector)(f: A => B): (Option[String], B) = {
    val decoder = Decoder[A]
    var remaining = buffer
    var acc = Monoid[B].zero
    while (remaining.nonEmpty) {
      decoder.decode(remaining).fold(
        { err => return (Some(err), acc) },
        { case (newRemaining, a) =>
            remaining = newRemaining
            acc = Monoid[B].append(acc, f(a))
        }
      )
    }
    (None, acc)
  }

  /**
   * Repeatedly decodes values of type `A` from the specified vector and returns a collection of the specified type.
   * Terminates when no more bits are available in the vector or when `limit` is defined and that many records have been
   * decoded. Exits upon first decoding error.
   */
  final def decodeCollect[F[_], A](dec: Decoder[A], limit: Option[Int])(buffer: BitVector)(implicit cbf: collection.generic.CanBuildFrom[F[A], A, F[A]]): String \/ (BitVector, F[A]) = {
    val bldr = cbf()
    var remaining = buffer
    var count = 0
    var maxCount = limit getOrElse Int.MaxValue
    var error: Option[String] = None
    while (count < maxCount && remaining.nonEmpty) {
      dec.decode(remaining) match {
        case \/-((rest, value)) =>
          bldr += value
          count += 1
          remaining = rest
        case -\/(err) =>
          error = Some(err)
          remaining = BitVector.empty
      }
    }
    error.toLeftDisjunction((remaining, bldr.result))
  }

  /**
   * Creates a decoder that decodes with each of the specified decoders, returning
   * the first successful result.
   */
  final def choiceDecoder[A](decoders: Decoder[A]*): Decoder[A] = new Decoder[A] {
    def decode(buffer: BitVector): String \/ (BitVector, A) = {
      @annotation.tailrec def go(rem: List[Decoder[A]], lastErr: String): String \/ (BitVector, A) = rem match {
        case Nil => \/.left(lastErr)
        case hd :: tl =>
          hd.decode(buffer) match {
            case res @ \/-(_) => res
            case -\/(err) => go(tl, err)
          }
      }
      go(decoders.toList, "no decoders provided")
    }
  }
}

/** Companion for [[Decoder]]. */
object Decoder extends DecoderFunctions {

  /** Provides syntax for summoning a `Decoder[A]` from implicit scope. */
  def apply[A](implicit dec: Decoder[A]): Decoder[A] = dec

  /** Creates a decoder that always decodes the specified value and returns the input bit vector unmodified. */
  def point[A](a: => A): Decoder[A] = new Decoder[A] {
    private lazy val value = a
    def decode(bits: BitVector) = \/.right((bits, value))
    override def toString = s"const($value)"
  }

  implicit val monadInstance: Monad[Decoder] = new Monad[Decoder] {
    def point[A](a: => A) = Decoder.point(a)
    def bind[A, B](decoder: Decoder[A])(f: A => Decoder[B]) = decoder.flatMap(f)
  }

  implicit def monoidInstance[A: Monoid]: Monoid[Decoder[A]] = new Monoid[Decoder[A]] {
    def zero = Decoder.point(Monoid[A].zero)
    def append(x: Decoder[A], y: => Decoder[A]) = new Decoder[A] {
      private lazy val yy = y
      def decode(bits: BitVector) = for {
        first <- x.decode(bits)
        second <- yy.decode(first._1)
      } yield (second._1, Monoid[A].append(first._2, second._2))
    }
  }
}
