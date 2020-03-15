package scodec

import scodec.bits._
import scodec.codecs._

class CodecTest extends CodecSuite {
  sealed trait Parent
  case class Foo(x: Int, y: Int, s: String) extends Parent
  case class Bar(x: Int) extends Parent

  "all codecs" should {

    "support flatZip" in {
      val codec = uint8.flatZip(n => fixedSizeBits(n.toLong, utf8))
      roundtripAll(codec, Seq((0, ""), (8, "a"), (32, "test")))
    }

    "support complete combinator" in {
      val codec = codecs.bits(8)
      codec.decode(hex"00112233".toBitVector) shouldBe Attempt.successful(
        DecodeResult(hex"00".bits, hex"112233".bits)
      )
      codec.complete.decode(hex"00112233".toBitVector) shouldBe Attempt.failure(
        Err("24 bits remaining: 0x112233")
      )
      codec.complete.decode(BitVector.fill(2000)(false)) shouldBe Attempt.failure(
        Err("more than 512 bits remaining")
      )
    }

    "support as method for converting to a new codec using implicit transform,".which {

      "works with tuple codecs of 1 element" in {
        roundtripAll(uint8.tuple.as[Bar], Seq(Bar(0), Bar(1), Bar(255)))
      }

      "works with non-tuple codecs" in {
        roundtripAll(uint8.as[Bar], Seq(Bar(0), Bar(1), Bar(255)))
      }

      "supports destructuring case classes in to tuples" in {
        (uint8 :: uint8 :: cstring).as[Foo].as[(Int, Int, String)]
        uint8.tuple.as[Bar].as[Int]
      }

      "supports destructuring singleton case classes in to values" in {
        uint8.tuple.as[Bar].as[Int]
        ()
      }

      "supports implicitly dropping unit values from a tuple" in {
        val c = (uint2 :: uint2 :: ignore(4) :: utf8_32).as[Foo]
        roundtrip(c, Foo(1, 2, "Hi"))
      }
    }

    "support the unit combinator" in {
      val codec = uint8.unit(0)
      codec.encode(()) shouldBe Attempt.successful(BitVector(0))
      codec.decode(BitVector(1)) shouldBe Attempt.successful(DecodeResult((), BitVector.empty))
      codec.decode(BitVector.empty) shouldBe Attempt.failure(Err.InsufficientBits(8, 0, Nil))
      uint8.unit(255).encode(()) shouldBe Attempt.successful(BitVector(0xff))
    }

    "support dropRight combinator" in {
      val codec = uint8 <~ uint8.unit(0)
      codec.encode(0xff) shouldBe Attempt.successful(hex"ff00".bits)
    }
  }

  "literal values" should {
    "be usable as constant codecs" in {
      import scodec.codecs.literals._
      (1 ~> uint8).encode(2) shouldBe Attempt.successful(hex"0102".bits)
      (hex"11223344" ~> uint8).encode(2) shouldBe Attempt.successful(hex"1122334402".bits)
      (hex"11223344".bits ~> uint8).encode(2) shouldBe Attempt.successful(hex"1122334402".bits)
    }
  }

  "exmap" should {
    "support validating input and output" in {
      // accept 8 bit values no greater than 9
      val oneDigit: Codec[Int] = uint8.exmap[Int](
        v => if (v > 9) Attempt.failure(Err("badv")) else Attempt.successful(v),
        d => if (d > 9) Attempt.failure(Err("badd")) else Attempt.successful(d)
      )

      oneDigit.encode(3) shouldBe Attempt.successful(BitVector(0x03))
      oneDigit.encode(10) shouldBe Attempt.failure(Err("badd"))
      oneDigit.encode(30000000) shouldBe Attempt.failure(Err("badd"))
      oneDigit.decode(BitVector(0x05)) shouldBe Attempt.successful(DecodeResult(5, BitVector.empty))
      oneDigit.decode(BitVector(0xff)) shouldBe Attempt.failure(Err("badv"))
      oneDigit.decode(BitVector.empty) shouldBe uint8.decode(BitVector.empty)
    }

    "result in a no-op when mapping successful over both sides".which {
      val noop: Codec[Int] = uint8.exmap[Int](Attempt.successful, Attempt.successful)
      forAll((n: Int) => noop.encode(n) shouldBe uint8.encode(n))
      ()
    }
  }

  def i2l(i: Int): Long = i.toLong
  def l2i(l: Long): Attempt[Int] =
    if (l >= Int.MinValue && l <= Int.MaxValue) Attempt.successful(l.toInt)
    else Attempt.failure(Err("out of range"))

  "narrow" should {
    "support converting to a smaller type" in {
      val narrowed: Codec[Int] = uint32.narrow(l2i, i2l)
      forAll((n: Int) => narrowed.encode(n) shouldBe uint32.encode(n.toLong))
    }
  }

  "widen" should {
    "support converting to a larger type" in {
      val narrowed = int32.widen(i2l, l2i)
      forAll { (n: Long) =>
        if (n >= Int.MinValue && n <= Int.MaxValue)
          narrowed.encode(n) shouldBe int32.encode(n.toInt)
        else
          narrowed.encode(n) shouldBe Attempt.failure(Err("out of range"))
      }
    }
  }

  "encodeOnly" should {
    val char8: Codec[Char] = uint8.contramap[Char](_.toInt).encodeOnly
    "encode successfully" in {
      char8.encode('a') shouldBe Attempt.successful(BitVector(0x61))
    }
    "fail to decode" in {
      char8.decode(hex"61".bits) shouldBe Attempt.failure(Err("decoding not supported"))
    }
  }

  "decodeOnly" should {
    val char8: Codec[Char] = uint8.map[Char](_.asInstanceOf[Char]).decodeOnly
    "decode successfully" in {
      char8.decode(BitVector(0x61)) shouldBe Attempt.successful(DecodeResult('a', BitVector.empty))
    }
    "fail to encode" in {
      char8.encode('a') shouldBe Attempt.failure(Err("encoding not supported"))
    }
  }

  "upcast" should {
    trait A
    case class B(x: Int) extends A
    case class C(x: Int) extends A
    val codec: Codec[A] = uint8.xmap[B](B.apply, _.x).upcast[A]
    "roundtrip values of original type" in {
      roundtrip(codec, B(0))
    }
    "return an error from encode if passed a different subtype of target type" in {
      codec.encode(C(0)).isFailure shouldBe true
    }
    "work in presence of nested objects/classes" in {
      object X { object Y }
      val c = provide(X).upcast[Any]
      c.encode(X) shouldBe Attempt.successful(BitVector.empty)
      c.encode(X.Y) shouldBe Attempt.failure(Err("not a value of type X$"))
    }
  }

  "downcast" should {
    trait A
    case object B extends A
    case object C extends A
    val codec =
      discriminated[A].by(uint8).typecase(1, provide(B)).typecase(2, provide(C)).downcast[B.type]
    "roundtrip values of original type" in {
      roundtrip(codec, B)
    }
    "return an error from decode if decoded value is a supertype of a different type" in {
      codec.decode(hex"02".bits).isFailure shouldBe true
    }
    "work in presence of nested objects/classes" in {
      trait P
      object X extends P { object Y extends P }
      val c = discriminated[P]
        .by(uint8)
        .typecase(0, provide(X))
        .typecase(1, provide(X.Y))
        .downcast[X.type]
      c.decodeValue(hex"00".bits) shouldBe Attempt.successful(X)
      c.decodeValue(hex"01".bits) shouldBe Attempt.failure(Err("not a value of type X$"))
    }
  }
}
