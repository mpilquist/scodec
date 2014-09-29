package scodec

import scala.annotation.unchecked.uncheckedVariance

import scalaz.{ \/, \/-, -\/, Contravariant, Corepresentable }
import \/.left

import scodec.bits.BitVector

/**
 * Supports encoding a value of type `A` to a `BitVector`.
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
trait Encoder[A] { self =>

  /**
   * Attempts to encode the specified value in to a bit vector.
   *
   * @param value value to encode
   * @param return error or binary encoding of the value
   * @group primary
   */
  def encode(value: A): String \/ BitVector

  /**
   * Encodes the specified value in to a bit vector, throwing an
   * `IllegalArgumentException` if encoding fails.
   *
   * @param value value to encode
   * @param return error or binary encoding of the value
   * @throws IllegalArgumentException upon encoding failure
   * @group primary
   */
  final def encodeValid(value: A): BitVector =
    encode(value) valueOr { err => throw new IllegalArgumentException(err) }

  /**
   * Converts this encoder to an `Encoder[B]` using the supplied `B => A`.
   * @group combinators
   */
  def contramap[B](f: B => A): Encoder[B] = new Encoder[B] {
    def encode(b: B) = self.encode(f(b))
  }

  /**
   * Converts this encoder to an `Encoder[B]` using the supplied partial
   * function from `B` to `A`. The encoding will fail for any `B` that
   * `f` maps to `None`.
   * @group combinators
   */
  def pcontramap[B](f: B => Option[A]): Encoder[B] = new Encoder[B] {
    def encode(b: B): String \/ BitVector =
      f(b).map(self.encode).getOrElse(left(s"extraction failure: $b"))
  }

  /**
   * Converts this encoder to an `Encoder[B]` using the supplied `B => String \/ A`.
   * @group combinators
   */
  def econtramap[B](f: B => String \/ A): Encoder[B] = new Encoder[B] {
    def encode(b: B) = f(b) flatMap self.encode
  }

  /**
   * Converts this encoder to a new encoder that compacts the generated bit vector before returning it
   * @group combinators
   */
  def compact: Encoder[A] = new Encoder[A] {
    def encode(a: A) = self.encode(a).map { _.compact }
  }

  /**
   * Gets this as an `Encoder`.
   * @group combinators
   */
  def asEncoder: Encoder[A] = this

  /**
   * Converts this to a codec that fails decoding with an error.
   * @group combinators
   */
  def encodeOnly: Codec[A @uncheckedVariance] = new Codec[A] {
    def encode(a: A) = self.encode(a)
    def decode(bits: BitVector) = \/.left("decoding not supported")
  }
}

/** Provides functions for working with encoders. */
trait EncoderFunctions {

  /** Encodes the specified value to a bit vector using an implicitly available encoder. */
  final def encode[A: Encoder](a: A): String \/ BitVector = Encoder[A].encode(a)

  /** Encodes the specified value to a bit vector using an implicitly available encoder or throws an `IllegalArgumentException` if encoding fails. */
  final def encodeValid[A: Encoder](a: A): BitVector = Encoder[A].encodeValid(a)

  /** Encodes the specified values, one after the other, to a bit vector using the specified encoders. */
  final def encodeBoth[A, B](encA: Encoder[A], encB: Encoder[B])(a: A, b: B): String \/ BitVector = for {
    encodedA <- encA.encode(a)
    encodedB <- encB.encode(b)
  } yield encodedA ++ encodedB

  /** Encodes all elements of the specified sequence and concatenates the results, or returns the first encountered error. */
  final def encodeSeq[A](enc: Encoder[A])(seq: collection.immutable.Seq[A]): String \/ BitVector = {
    val buf = new collection.mutable.ArrayBuffer[BitVector](seq.size)
    seq foreach { a =>
      enc.encode(a) match {
        case \/-(aa) => buf += aa
        case e @ -\/(_) => return e
      }
    }
    def merge(offset: Int, size: Int): BitVector = size match {
      case 0 => BitVector.empty
      case 1 => buf(offset)
      case n =>
        val half = size / 2
        merge(offset, half) ++ merge(offset + half, half + (if (size % 2 == 0) 0 else 1))
    }
    \/.right(merge(0, buf.size))
  }

  /**
   * Creates an encoder that encodes with each of the specified encoders, returning
   * the first successful result.
   */
  final def choiceEncoder[A](encoders: Encoder[A]*): Encoder[A] = new Encoder[A] {
    def encode(a: A) = {
      @annotation.tailrec def go(rem: List[Encoder[A]], lastErr: String): String \/ BitVector = rem match {
        case Nil => \/.left(lastErr)
        case hd :: tl =>
          hd.encode(a) match {
            case res @ \/-(_) => res
            case -\/(err) => go(tl, err)
          }
      }
      go(encoders.toList, "no encoders provided")
    }
  }
}

/** Companion for [[Encoder]]. */
object Encoder extends EncoderFunctions {

  /** Provides syntaax for summoning an `Encoder[A]` from implicit scope. */
  def apply[A](implicit enc: Encoder[A]): Encoder[A] = enc

  def instance[A](f: A => String \/ BitVector): Encoder[A] = new Encoder[A] {
    def encode(value: A) = f(value)
  }

  implicit val contravariantInstance: Contravariant[Encoder] = new Contravariant[Encoder] {
    def contramap[A, B](e: Encoder[A])(f: B => A) = e contramap f
  }

  implicit val corepresentableInstance: Corepresentable[Encoder, String \/ BitVector] = new Corepresentable[Encoder, String \/ BitVector] {
    def corep[A](f: A => String \/ BitVector): Encoder[A] = Encoder.instance(f)
    def uncorep[A](f: Encoder[A]): A => String \/ BitVector = f.encode
  }
}
