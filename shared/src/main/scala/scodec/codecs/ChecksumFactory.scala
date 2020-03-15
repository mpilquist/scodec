package scodec
package codecs

import java.security.MessageDigest
import java.util.Arrays
import java.util.zip.{Adler32, CRC32, Checksum}
import scodec.bits.ByteVector

/**
  * Creates checksum implementations of [[SignerFactory]].
  */
object ChecksumFactory {

  /** Creates a `java.security.Digest` factory for the specified algorithm. */
  def digest(algorithm: String): SignerFactory = new ChecksumFactory {
    def newSigner: Signer = new DigestSigner(MessageDigest.getInstance(algorithm).nn)
  }

  /** Signer factory that does not have a distinct verifier. */
  private trait ChecksumFactory extends SignerFactory {
    def newVerifier: Signer = newSigner
  }

  /** Fletcher-16 checksum. */
  val fletcher16: SignerFactory = new ChecksumFactory {
    def newSigner = new Fletcher16Checksum
  }

  /** CRC-32 checksum. */
  val crc32: SignerFactory = new ChecksumFactory {
    def newSigner = new ZipChecksumSigner(new CRC32())
  }

  /** Adler-32 checksum. */
  val adler32: SignerFactory = new ChecksumFactory {
    def newSigner = new ZipChecksumSigner(new Adler32())
  }

  /** xor checksum. */
  val xor: SignerFactory = new ChecksumFactory {
    def newSigner = new XorSigner
  }

  /** `java.security.Digest` implementation of Signer. */
  private class DigestSigner(impl: MessageDigest) extends Signer {
    def update(data: Array[Byte]): Unit = impl.update(data)
    def sign: Array[Byte] = impl.digest.nn
    def verify(signature: Array[Byte]): Boolean = MessageDigest.isEqual(impl.digest(), signature)
  }

  /** http://en.wikipedia.org/wiki/Fletcher's_checksum */
  private class Fletcher16Checksum extends Signer {
    var checksum = (0, 0)
    def update(data: Array[Byte]): Unit =
      checksum = data.foldLeft(checksum) { (p, b) =>
        val lsb = (p._2 + (0xff & b)) % 255
        ((p._1 + lsb) % 255, lsb)
      }
    def sign: Array[Byte] = Array(checksum._1.asInstanceOf[Byte], checksum._2.asInstanceOf[Byte])
    def verify(signature: Array[Byte]): Boolean = Arrays.equals(sign, signature)
  }

  /** `java.util.zip.Checksum` implementation of Signer. */
  private class ZipChecksumSigner(impl: Checksum) extends Signer {
    def update(data: Array[Byte]): Unit = impl.update(data, 0, data.length)
    def sign: Array[Byte] = ByteVector.fromLong(impl.getValue()).drop(4).toArray
    def verify(signature: Array[Byte]): Boolean = MessageDigest.isEqual(sign, signature)
  }

  private class XorSigner extends Signer {
    var data: Array[Byte] = _

    def update(data: Array[Byte]): Unit = this.data = data
    def sign: Array[Byte] = Array(data.reduce((b1, b2) => (b1 ^ b2).toByte))
    def verify(signature: Array[Byte]): Boolean = sign.sameElements(signature)
  }
}
