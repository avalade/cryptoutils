import java.io.ByteArrayOutputStream

import com.karasiq.tls.internal.BCConversions._
import com.karasiq.tls.internal.TLSUtils
import com.karasiq.tls.pem.PEM
import com.karasiq.tls.x509._
import com.karasiq.tls.x509.crl.CRL
import com.karasiq.tls.x509.ocsp.OCSP
import com.karasiq.tls.x509.ocsp.OCSP.Status
import com.karasiq.tls.{TLS, TLSKeyStore}
import org.apache.commons.io.IOUtils
import org.bouncycastle.asn1.x509
import org.bouncycastle.asn1.x509.CRLReason
import org.scalatest.{FreeSpec, Matchers}

import scala.util.control.Exception

class X509Test extends FreeSpec with Matchers {
  private def resource(name: String) = {
    val stream = getClass.getClassLoader.getResourceAsStream(name)
    Exception.allCatch.andFinally(IOUtils.closeQuietly(stream))(IOUtils.toString(stream))
  }

  "Certificate generator" - {
    val keyGenerator = CertificateGenerator()

    "With generated keys" - {
      val certificationAuthority = keyGenerator.generateEcdsa(X509Utils.subject("Localhost Root CA", "US", "California", "San Francisco", "Karasiq", "Cryptoutils Test Root CA", "karasiq@karasiq.com"), TLSUtils.getEllipticCurve("secp256k1"), extensions = CertExtension.certificationAuthorityExtensions(1))

      val serverKeySet = keyGenerator.generateKeySet(X509Utils.subject("Localhost Server", "US", "California", "San Francisco", "Karasiq", "Cryptoutils Test Server", "karasiq@karasiq.com"), 2048, 1024, TLSUtils.getEllipticCurve("secp256k1"), Some(certificationAuthority), BigInt(1), extensions = CertExtension.defaultExtensions() ++ Set(CertExtension.crlDistributionUrls(certificationAuthority.certificate, "http://localhost/test.crl")))

      "should print certificate" in {
        val encoded = PEM.encode(certificationAuthority.certificate)
        println(encoded)
        assert(PEM.certificate(encoded).getSubject == certificationAuthority.certificate.getSubject)
        println(PEM.encode(serverKeySet.ecdsa.get.certificate))
      }

      "should print private key" in {
        val encoded = PEM.encode(serverKeySet.rsa.get.key.getPrivate)
        println(encoded)
        assert(PEM.publicKey(encoded) == serverKeySet.rsa.get.key.getPublic.toSubjectPublicKeyInfo)
      }

      "should verify extensions" in {
        X509Utils.verifyAuthorityIdentifier(serverKeySet.rsa.get.certificate, certificationAuthority.certificate) shouldBe Some(true)
        X509Utils.verifyPublicKeyIdentifier(serverKeySet.dsa.get.certificate, serverKeySet.dsa.get.key.getPublic.toSubjectPublicKeyInfo) shouldBe Some(true)
        X509Utils.getPathLengthConstraint(certificationAuthority.certificate) shouldBe Some(1)
        X509Utils.getCrlDistributionUrls(serverKeySet.ecdsa.get.certificate).toList shouldBe List("http://localhost/test.crl")
      }

      "should sign CSR" in {
        val Some(key: TLS.CertificateKey) = serverKeySet.ecdsa
        val request = keyGenerator.createRequest(key.key.toKeyPair, key.certificate.getSubject)
        val encoded = PEM.encode(request)
        println(encoded)
        assert(PEM.certificationRequest(encoded).getSubject == request.getSubject)
        val cert = keyGenerator.signRequest(request, certificationAuthority)
        val verifier = CertificateVerifier(CertificateStatusProvider.AlwaysValid, certificationAuthority.certificate)
        assert(verifier.isChainValid(cert.getCertificateList.toList))
        X509Utils.verifyAuthorityIdentifier(cert.toTlsCertificate, certificationAuthority.certificate) shouldBe Some(true)
        X509Utils.verifyPublicKeyIdentifier(cert.toTlsCertificate, serverKeySet.ecdsa.get.key.getPublic.toSubjectPublicKeyInfo) shouldBe Some(true)
        println("CSR signed: " + cert.toTlsCertificate.getSubject)
      }

      "should read CRL" in {
        val issuer = PEM.certificate(resource("ocsp-crl-issuer.crt"))
        X509Utils.getCrlDistributionUrls(PEM.certificate(resource("ocsp-crl-issuer.crt"))).toList shouldBe List("http://g.symcb.com/crls/gtglobal.crl")
        val Some(crl) = CRL.fromUrl("http://pki.google.com/GIAG2.crl", issuer)
        println(crl.getIssuer)
        println(PEM.encode(crl))
      }

      "should create CRL" in {
        val crl = CRL.build(certificationAuthority, Seq(CRL.RevokedCert(serverKeySet.rsa.get.certificate, x509.CRLReason.keyCompromise)))
        assert(CRL.verify(crl, certificationAuthority.certificate), "Couldn't verify CRL signature")
        assert(CRL.contains(crl, serverKeySet.rsa.get.certificate))
        println(PEM.encode(crl))
      }

      "should create OCSP response" in {
        val ocsp = OCSP.response(certificationAuthority, Status(OCSP.id(certificationAuthority.certificate, BigInt(1)), OCSP.Status.revoked(CRLReason.keyCompromise)))
        assert(OCSP.verify(ocsp, certificationAuthority.certificate), "Signature not valid")
        assert(OCSP.Status.wrap(ocsp.getResponses).find(_.id.getSerialNumber == BigInt(1).underlying()).exists(_.isRevoked), "Not revoked")
      }

      "should read OCSP response" in {
        val cert = PEM.certificate(resource("ocsp-test.crt"))
        val issuer = PEM.certificate(resource("ocsp-crl-issuer.crt"))
        val status = OCSP.getStatus(cert, issuer)
        status.exists(_.isRevoked) shouldBe false
        status.map(_.status) shouldBe Some(OCSP.Status.good())
      }

      "should create java key store" in {
        val keyStore = TLSKeyStore.empty()
        keyStore.putCertificate("ca", certificationAuthority.certificate)
        keyStore.putKeySet("test", serverKeySet)
        val outputStream = new ByteArrayOutputStream()
        Exception.allCatch.andFinally(outputStream.close()) {
          keyStore.save(outputStream)
          println(s"Key store size: ${outputStream.size()} bytes")
        }
      }
    }
  }
}
