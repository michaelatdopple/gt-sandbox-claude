package com.dopple.webview.server

import android.util.Base64
import android.util.Log
import java.io.ByteArrayInputStream
import java.security.KeyFactory
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/**
 * Generates self-signed TLS certificates for localhost HTTPS.
 * Required for secure JavaScript APIs (getUserMedia, sensors, etc.)
 *
 * For the prototype, we use a pre-generated self-signed certificate
 * that is valid for 127.0.0.1 and localhost.
 */
object CertificateGenerator {

    private const val TAG = "CertificateGenerator"

    // Pre-generated self-signed certificate for localhost
    // Generated with: openssl req -x509 -newkey rsa:2048 -keyout key.pem -out cert.pem -days 365 -nodes
    // Subject: CN=localhost
    // SANs: DNS:localhost, IP:127.0.0.1
    private const val CERTIFICATE_PEM = """
-----BEGIN CERTIFICATE-----
MIIDJTCCAg2gAwIBAgIUccMh+IXSukfpqseSZsNbJnSuxa4wDQYJKoZIhvcNAQEL
BQAwFDESMBAGA1UEAwwJbG9jYWxob3N0MB4XDTI2MDExMzIwMDE1NVoXDTI3MDEx
MzIwMDE1NVowFDESMBAGA1UEAwwJbG9jYWxob3N0MIIBIjANBgkqhkiG9w0BAQEF
AAOCAQ8AMIIBCgKCAQEAx+7f7J3na/YPSijyTPAZ2HKtPZ+WF2Vkt6XSQPVZIZET
gD/e34+AOgx7iuJMUyWH9ln6e56kT2DOJkxK7ZGJy3UXLZ0YDk/FqzaT/6Jb1Zjl
+jAlUub2oIt6yL96io3JDWxTkQSyWa49pcc5t6gr+B33a+v2IWqmBPJuUocM7NRe
knw4lgOGPpgSjYJKX9OnfeMsy22G2obpjLn10ztAJFIlH4NAEA/4SJ+3DSob7Pe8
7Q4m3WX+C+UNKSUO0nF4sAeHyltH/PoT6gRdbSeekxfrw2CTUkt1NJeVcP8FLqw7
n2j+6dE2mYzkLxaQaX1KEQrmR5pM1FfyY60cRB5NOwIDAQABo28wbTAdBgNVHQ4E
FgQUjwfXjN8JWUbJBVG/p6brTJYoUu0wHwYDVR0jBBgwFoAUjwfXjN8JWUbJBVG/
p6brTJYoUu0wDwYDVR0TAQH/BAUwAwEB/zAaBgNVHREEEzARgglsb2NhbGhvc3SH
BH8AAAEwDQYJKoZIhvcNAQELBQADggEBAD/u3LmDWLTnZUQl9UvMUiVuGp+5LWhG
7zAj0MQ1w4xIH1SSsZdkBLMd/1x26cYE9Bx/7mlhrtJn2BtT14xe1BH8Lbs9jEI5
cxXr0Dn9W80vgA/NrvH/bvBmZFk99xpETSQO9i1cPwRTNHzwN3GL+IUCyXcNPjDz
lmjeqmF8hv1t0UNsgM5KcHei37LJcQZ41Czhiswnnfum7Mkz1F6jmvKR5If6INF0
Y/hsJTnbFt6+sSpbRZgcAmNWVkLXEZjLpZNOVxpc8VYAZtecUhmjc1cFSOhHYjO0
fgMB6KoW2+BhihG7oNnLl68LSB/4t8E85A3iakNaz5LxyqRlVOLedr8=
-----END CERTIFICATE-----
    """

    private const val PRIVATE_KEY_PEM = """
-----BEGIN PRIVATE KEY-----
MIIEvwIBADANBgkqhkiG9w0BAQEFAASCBKkwggSlAgEAAoIBAQDH7t/snedr9g9K
KPJM8BnYcq09n5YXZWS3pdJA9VkhkROAP97fj4A6DHuK4kxTJYf2Wfp7nqRPYM4m
TErtkYnLdRctnRgOT8WrNpP/olvVmOX6MCVS5vagi3rIv3qKjckNbFORBLJZrj2l
xzm3qCv4Hfdr6/YhaqYE8m5Shwzs1F6SfDiWA4Y+mBKNgkpf06d94yzLbYbahumM
ufXTO0AkUiUfg0AQD/hIn7cNKhvs97ztDibdZf4L5Q0pJQ7ScXiwB4fKW0f8+hPq
BF1tJ56TF+vDYJNSS3U0l5Vw/wUurDufaP7p0TaZjOQvFpBpfUoRCuZHmkzUV/Jj
rRxEHk07AgMBAAECggEAWToCNVdDMKtfp3keqbd90VtcdWQDV+2oRU17yAG3BYP5
cAMRNDSWxVFM0W6tIW5ef1MotoLWUZaiafUie2O4W1SmQ3UYh0qSrRZudme/FFRo
Tn+uKJFq/7s/0NejmLIuSA9QPCgccGmSmamP2Kb0+IZnJYLpFYDDFvIayjn7SrWy
KQ7mBKoGHzwpf+mx03SLONz0xjJn6HOG5zg5OflhqeAWHsTn2ktCtAcFim2D8COU
MeZVKcAQe7vDTuczCPgzP8FRO/+JgmTA7E5FCZvwGerrGzkWVohPI6drjyXoVWyr
PVGU9itjieHg2evq99SpZJbjBEBqntCOiFaW7mvIAQKBgQDw/AkrXAuuopyJgohK
cg24/YnlhFMe3Gys/enoruO7jtUawQk6VkyPh0Lm5Qqhi/3sBocMFzERPc+y58Cb
tM4Fm6qdrHpxv8J4V31UWc9s7F0XKblFRSXBYI2UI6XweZUmxhrdyT8qDxI8y68J
edRqS19wz6+KBiVPb+/Xl/9z0QKBgQDUZAY+6SmaeByCWunhJOUIbqGzpN6fxmev
uEX22xN+rHC3eSDlhcejBQ8BTKXLWDoyvj44Up/4C5hxIRcg6JqFE1e5Z6yxQCxg
C2Ru0YPxUegpIgT7AjxXA7yRWYTZXqOt+CSENdqlkAiQdTfekh0Zh3zl03nBaWiT
nAbwCsQvSwKBgQDWWfj1/nvNrIq8rcT5IxYdtDfSVa9xxfNMtNY4yEd874GSuJ2y
rEyRZmkV5ClepJ0KMowxLvfQNEDpC8eBOIQA7QQIGAEZ6M3cKUYrn28nnd732X8Y
pHb+2RlV6ZeSnTMUOCZxnK54hMteFHbvYNSWb9DHEZU8mlbZn+GgzWzF4QKBgQCr
Hb9Odko3kqfdemPOKgyA46lR8/YNIiutjjiIL28gcswJdgTEBymVtOCm+lrlXrrc
4Rt+A0uw656xHqjksaK0rqXR0a53zC00YFlVU2YLSiNS6H68wMtei7skG8yF2NLk
ufSptD3pgAb2ZApUPsJFLDy7actBwawoqN+KDBKNywKBgQCopWgrJf0S1yISAE0z
igQjI5qmxYkxOmXBq3YAg41qVGv78/asfcgyxtHJ8ZOgld9ZaMpvfUKD/vE8OywK
o7CyOFNqy9iB0vDe1AZ6M/D+FaoRVOphU+GJDhdHyeeuQvO5MgDLqJv8bC1/yaEu
3VZUj7va9G3i/pJiA8m4T5MzoQ==
-----END PRIVATE KEY-----
    """

    /**
     * Creates an SSLContext configured with the self-signed certificate.
     */
    fun createSSLContext(): SSLContext {
        try {
            val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
            keyStore.load(null, null)

            // Parse certificate
            val certFactory = CertificateFactory.getInstance("X.509")
            val certBytes = parsePEM(CERTIFICATE_PEM, "CERTIFICATE")
            val certificate = certFactory.generateCertificate(ByteArrayInputStream(certBytes))

            // Parse private key
            val keyFactory = KeyFactory.getInstance("RSA")
            val keyBytes = parsePEM(PRIVATE_KEY_PEM, "PRIVATE KEY")
            val privateKey = keyFactory.generatePrivate(PKCS8EncodedKeySpec(keyBytes))

            // Add to keystore
            keyStore.setKeyEntry("localhost", privateKey, charArrayOf(), arrayOf(certificate))

            // Create key manager
            val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            keyManagerFactory.init(keyStore, charArrayOf())

            // Create SSL context
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(keyManagerFactory.keyManagers, null, null)

            Log.d(TAG, "SSL context created successfully")
            return sslContext
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create SSL context", e)
            throw e
        }
    }

    private fun parsePEM(pem: String, type: String): ByteArray {
        val base64 = pem
            .replace("-----BEGIN $type-----", "")
            .replace("-----END $type-----", "")
            .replace("\n", "")
            .replace("\r", "")
            .replace(" ", "")
            .trim()

        return Base64.decode(base64, Base64.DEFAULT)
    }
}
