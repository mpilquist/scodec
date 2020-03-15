package scodec
package codecs

import java.io.ByteArrayInputStream
import java.security.cert.{Certificate, CertificateException, CertificateFactory}

import scodec.bits.BitVector

/**
  * Codec that supports encoding and decoding of [[java.security.cert.Certificate]]s using their default encoding.
  */
private[codecs] final class CertificateCodec(certType: String) extends Codec[Certificate] {

  def sizeBound = SizeBound.unknown

  def encode(cert: Certificate) =
    Attempt.successful(BitVector(cert.getEncoded.nn))

  def decode(buffer: BitVector) =
    try {
      val factory = CertificateFactory.getInstance(certType).nn
      val cert = factory.generateCertificate(new ByteArrayInputStream(buffer.toByteArray)).nn
      Attempt.successful(DecodeResult(cert, BitVector.empty))
    } catch {
      case e: CertificateException =>
        Attempt.failure(Err("Failed to decode certificate: " + e.getMessage))
    }

  override def toString = s"certificate($certType)"
}
