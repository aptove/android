package com.acp.chat.service

import android.util.Log
import com.acp.chat.data.model.ConnectionConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Type of pairing connection.
 * This is determined by the URL path (e.g., /pair/local, /pair/cloudflare, /pair/tailscale).
 */
enum class PairingType {
    /** Local network pairing with self-signed certificates */
    LOCAL,
    /** Cloudflare-tunneled pairing */
    CLOUDFLARE,
    /** Tailscale transport: standard TLS for serve mode, cert pinning for ip mode */
    TAILSCALE,
    /** Unknown pairing type */
    UNKNOWN;

    companion object {
        fun fromPath(path: String): PairingType {
            return when {
                path.contains("/pair/local") -> LOCAL
                path.contains("/pair/cloudflare") -> CLOUDFLARE
                path.contains("/pair/tailscale") -> TAILSCALE
                else -> UNKNOWN
            }
        }
    }
}

/**
 * Parsed pairing URL containing all components needed to complete pairing.
 */
data class PairingURL(
    val fullURL: String,
    val baseURL: String,
    val pairingType: PairingType,
    val code: String,
    val fingerprint: String?
) {
    companion object {
        private const val TAG = "PairingURL"

        /**
         * Parse a pairing URL from QR code content.
         * Expected format: https://IP:PORT/pair/local?code=XXXXXX&fp=SHA256:...
         */
        fun parse(urlString: String): PairingURL? {
            return try {
                val uri = URI(urlString)
                
                // Must be HTTPS for pairing
                if (uri.scheme != "https") {
                    Log.w(TAG, "Invalid scheme: ${uri.scheme}, expected https")
                    return null
                }
                
                // Parse query parameters
                val queryParams = parseQueryParams(uri.query ?: "")
                
                val code = queryParams["code"]
                if (code.isNullOrBlank()) {
                    Log.w(TAG, "Missing code parameter in pairing URL")
                    return null
                }
                
                val fingerprint = queryParams["fp"]
                val pairingType = PairingType.fromPath(uri.path)
                
                // Build base URL (scheme + host + port)
                val port = if (uri.port > 0) ":${uri.port}" else ""
                val baseURL = "${uri.scheme}://${uri.host}$port"
                
                PairingURL(
                    fullURL = urlString,
                    baseURL = baseURL,
                    pairingType = pairingType,
                    code = code,
                    fingerprint = fingerprint
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse pairing URL: ${e.message}")
                null
            }
        }
        
        private fun parseQueryParams(query: String): Map<String, String> {
            if (query.isBlank()) return emptyMap()
            
            return query.split("&")
                .mapNotNull { param ->
                    val parts = param.split("=", limit = 2)
                    if (parts.size == 2) {
                        parts[0] to java.net.URLDecoder.decode(parts[1], "UTF-8")
                    } else null
                }
                .toMap()
        }
    }
}

/**
 * Response from the pairing endpoint.
 */
@Serializable
data class PairingResponse(
    val url: String,
    val protocol: String,
    val version: String,
    val authToken: String,
    val certFingerprint: String? = null
)

/**
 * Error response from the pairing endpoint.
 */
@Serializable
data class PairingError(
    val error: String,
    val message: String
)

/**
 * Result of a pairing attempt.
 */
sealed class PairingResult {
    data class Success(val config: ConnectionConfig) : PairingResult()
    data class InvalidCode(val message: String) : PairingResult()
    data class RateLimited(val message: String) : PairingResult()
    data class CertificateMismatch(val expected: String?, val actual: String) : PairingResult()
    data class NetworkError(val message: String) : PairingResult()
    data class UnknownError(val message: String) : PairingResult()
}

/**
 * Service that handles pairing with the bridge.
 * Supports different pairing types (local, cloudflare) based on URL path.
 */
@Singleton
class PairingService @Inject constructor() {
    
    companion object {
        private const val TAG = "PairingService"
    }
    
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
    
    /**
     * Attempt to pair with the bridge using a pairing URL.
     * 
     * @param pairingURL The parsed pairing URL from QR code
     * @return PairingResult indicating success or specific failure type
     */
    suspend fun pair(pairingURL: PairingURL): PairingResult {
        Log.d(TAG, "Starting pairing with type: ${pairingURL.pairingType}")

        return when (pairingURL.pairingType) {
            PairingType.LOCAL -> pairLocal(pairingURL)
            PairingType.CLOUDFLARE -> pairCloudflare(pairingURL)
            PairingType.TAILSCALE -> pairTailscale(pairingURL)
            PairingType.UNKNOWN -> PairingResult.UnknownError("Unknown pairing type for URL: ${pairingURL.fullURL}")
        }
    }
    
    /**
     * Local pairing with certificate pinning.
     */
    private suspend fun pairLocal(pairingURL: PairingURL): PairingResult = withContext(Dispatchers.IO) {
        var actualFingerprint: String? = null
        
        // Create trust manager that captures and validates the certificate fingerprint
        val trustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                if (chain.isNullOrEmpty()) {
                    throw java.security.cert.CertificateException("No certificates in chain")
                }
                
                val serverCert = chain[0]
                actualFingerprint = calculateFingerprint(serverCert)
                
                Log.d(TAG, "🔐 Server cert fingerprint: $actualFingerprint")
                Log.d(TAG, "🔐 Expected fingerprint: ${pairingURL.fingerprint}")
                
                // Validate fingerprint if provided
                if (pairingURL.fingerprint != null) {
                    val expectedNormalized = normalizeFingerprint(pairingURL.fingerprint)
                    val actualNormalized = normalizeFingerprint(actualFingerprint!!)
                    
                    if (!expectedNormalized.equals(actualNormalized, ignoreCase = true)) {
                        Log.e(TAG, "🔐 Certificate fingerprint mismatch!")
                        throw java.security.cert.CertificateException(
                            "Certificate fingerprint mismatch"
                        )
                    }
                    Log.d(TAG, "🔐 Certificate fingerprint matches!")
                }
            }
            
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        
        // Create SSL context with our trust manager
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf<TrustManager>(trustManager), java.security.SecureRandom())
        
        // Create OkHttp client with custom SSL and reasonable timeouts
        val okHttpClient = OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .hostnameVerifier { _, _ -> true } // Allow any hostname for local network
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        
        try {
            val request = Request.Builder()
                .url(pairingURL.fullURL)
                .get()
                .build()
            
            Log.d(TAG, "📤 Sending pairing request to: ${pairingURL.fullURL}")
            
            val response = okHttpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            
            Log.d(TAG, "📥 Response code: ${response.code}")
            
            when (response.code) {
                200 -> {
                    val pairingResponse = json.decodeFromString<PairingResponse>(responseBody)
                    
                    // Build connection config from pairing response
                    val config = ConnectionConfig(
                        url = pairingResponse.url,
                        authToken = pairingResponse.authToken,
                        certFingerprint = pairingResponse.certFingerprint ?: actualFingerprint,
                        protocol = pairingResponse.protocol,
                        version = pairingResponse.version
                    )
                    
                    Log.d(TAG, "✅ Pairing successful, got WebSocket URL: ${config.url}")
                    PairingResult.Success(config)
                }
                
                401 -> {
                    val error = json.decodeFromString<PairingError>(responseBody)
                    Log.w(TAG, "❌ Invalid code: ${error.message}")
                    PairingResult.InvalidCode(error.message)
                }
                
                429 -> {
                    val error = json.decodeFromString<PairingError>(responseBody)
                    Log.w(TAG, "❌ Rate limited: ${error.message}")
                    PairingResult.RateLimited(error.message)
                }
                
                else -> {
                    Log.e(TAG, "❌ Unexpected response ${response.code}: $responseBody")
                    PairingResult.UnknownError("Unexpected response: ${response.code}")
                }
            }
        } catch (e: java.security.cert.CertificateException) {
            Log.e(TAG, "🔐 Certificate validation failed", e)
            PairingResult.CertificateMismatch(
                expected = pairingURL.fingerprint,
                actual = actualFingerprint ?: "unknown"
            )
        } catch (e: javax.net.ssl.SSLHandshakeException) {
            Log.e(TAG, "🔐 SSL handshake failed - possible certificate mismatch", e)
            PairingResult.CertificateMismatch(
                expected = pairingURL.fingerprint,
                actual = actualFingerprint ?: "unknown"
            )
        } catch (e: java.net.ConnectException) {
            Log.e(TAG, "🌐 Connection refused - is the bridge running?", e)
            PairingResult.NetworkError("Connection refused. Make sure the bridge is running and the address is correct.")
        } catch (e: java.net.SocketTimeoutException) {
            Log.e(TAG, "🌐 Connection timed out", e)
            PairingResult.NetworkError("Connection timed out. Check that your device is on the same network as the bridge.")
        } catch (e: java.net.UnknownHostException) {
            Log.e(TAG, "🌐 Unknown host: ${e.message}", e)
            PairingResult.NetworkError("Cannot resolve host. Check the bridge address.")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Network error during pairing: ${e.javaClass.simpleName}", e)
            PairingResult.NetworkError("${e.javaClass.simpleName}: ${e.message ?: "Connection failed"}")
        }
    }
    
    /**
     * Tailscale transport pairing.
     * - ip mode (fingerprint present): reuses cert-pinning logic from pairLocal.
     * - serve mode (fingerprint absent): Tailscale provides a valid Let's Encrypt cert → standard TLS.
     */
    private suspend fun pairTailscale(pairingURL: PairingURL): PairingResult {
        Log.d(TAG, "🔐 PairingService: Starting Tailscale pairing")

        if (pairingURL.fingerprint != null) {
            // ip mode: fingerprint present — reuse cert-pinning logic
            Log.d(TAG, "🔐 PairingService: Tailscale ip mode — using cert pinning")
            return pairLocal(pairingURL)
        }

        // serve mode: standard TLS
        Log.d(TAG, "🔐 PairingService: Tailscale serve mode — using standard TLS")
        return withContext(Dispatchers.IO) {
            val okHttpClient = OkHttpClient.Builder()
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .writeTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .build()

            try {
                val request = Request.Builder()
                    .url(pairingURL.fullURL)
                    .get()
                    .build()

                Log.d(TAG, "📤 Sending Tailscale pairing request to: ${pairingURL.fullURL}")

                val response = okHttpClient.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""

                Log.d(TAG, "📥 Response code: ${response.code}")

                when (response.code) {
                    200 -> {
                        val pairingResponse = json.decodeFromString<PairingResponse>(responseBody)
                        val config = ConnectionConfig(
                            url = pairingResponse.url,
                            authToken = pairingResponse.authToken,
                            certFingerprint = null, // serve mode: no pinning needed
                            protocol = pairingResponse.protocol,
                            version = pairingResponse.version
                        )
                        Log.d(TAG, "✅ Tailscale pairing successful: ${config.url}")
                        PairingResult.Success(config)
                    }
                    401 -> {
                        val error = json.decodeFromString<PairingError>(responseBody)
                        Log.w(TAG, "❌ Invalid code: ${error.message}")
                        PairingResult.InvalidCode(error.message)
                    }
                    429 -> {
                        val error = json.decodeFromString<PairingError>(responseBody)
                        Log.w(TAG, "❌ Rate limited: ${error.message}")
                        PairingResult.RateLimited(error.message)
                    }
                    else -> {
                        Log.e(TAG, "❌ Unexpected response ${response.code}: $responseBody")
                        PairingResult.UnknownError("Unexpected response: ${response.code}")
                    }
                }
            } catch (e: java.net.ConnectException) {
                Log.e(TAG, "🌐 Connection refused", e)
                PairingResult.NetworkError("Connection refused. Make sure the bridge is running.")
            } catch (e: java.net.SocketTimeoutException) {
                Log.e(TAG, "🌐 Connection timed out", e)
                PairingResult.NetworkError("Connection timed out.")
            } catch (e: java.net.UnknownHostException) {
                Log.e(TAG, "🌐 Unknown host: ${e.message}", e)
                PairingResult.NetworkError("Cannot resolve host. Ensure your device is on the same tailnet.")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Network error during Tailscale pairing: ${e.javaClass.simpleName}", e)
                PairingResult.NetworkError("${e.javaClass.simpleName}: ${e.message ?: "Connection failed"}")
            }
        }
    }

    /**
     * Cloudflare-tunneled pairing. Uses standard HTTPS (no cert pinning) since
     * Cloudflare handles TLS termination with a valid public certificate.
     */
    private suspend fun pairCloudflare(pairingURL: PairingURL): PairingResult = withContext(Dispatchers.IO) {
        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        try {
            val request = Request.Builder()
                .url(pairingURL.fullURL)
                .get()
                .build()

            Log.d(TAG, "📤 Sending Cloudflare pairing request to: ${pairingURL.fullURL}")

            val response = okHttpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            Log.d(TAG, "📥 Response code: ${response.code}")

            when (response.code) {
                200 -> {
                    val pairingResponse = json.decodeFromString<PairingResponse>(responseBody)

                    val config = ConnectionConfig(
                        url = pairingResponse.url,
                        authToken = pairingResponse.authToken,
                        certFingerprint = null, // No cert pinning for Cloudflare
                        protocol = pairingResponse.protocol,
                        version = pairingResponse.version
                    )

                    Log.d(TAG, "✅ Cloudflare pairing successful: ${config.url}")
                    PairingResult.Success(config)
                }

                401 -> {
                    val error = json.decodeFromString<PairingError>(responseBody)
                    Log.w(TAG, "❌ Invalid code: ${error.message}")
                    PairingResult.InvalidCode(error.message)
                }

                429 -> {
                    val error = json.decodeFromString<PairingError>(responseBody)
                    Log.w(TAG, "❌ Rate limited: ${error.message}")
                    PairingResult.RateLimited(error.message)
                }

                else -> {
                    Log.e(TAG, "❌ Unexpected response ${response.code}: $responseBody")
                    PairingResult.UnknownError("Unexpected response: ${response.code}")
                }
            }
        } catch (e: java.net.ConnectException) {
            Log.e(TAG, "🌐 Connection refused", e)
            PairingResult.NetworkError("Connection refused. Make sure the bridge is running.")
        } catch (e: java.net.SocketTimeoutException) {
            Log.e(TAG, "🌐 Connection timed out", e)
            PairingResult.NetworkError("Connection timed out.")
        } catch (e: java.net.UnknownHostException) {
            Log.e(TAG, "🌐 Unknown host: ${e.message}", e)
            PairingResult.NetworkError("Cannot resolve host. Check the bridge address.")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Network error during Cloudflare pairing: ${e.javaClass.simpleName}", e)
            PairingResult.NetworkError("${e.javaClass.simpleName}: ${e.message ?: "Connection failed"}")
        }
    }
    
    /**
     * Calculate SHA256 fingerprint of a certificate.
     * Returns format: SHA256:XX:XX:XX:...
     */
    private fun calculateFingerprint(cert: X509Certificate): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(cert.encoded)
        val hexString = digest.joinToString(":") { String.format("%02X", it) }
        return "SHA256:$hexString"
    }
    
    /**
     * Normalize fingerprint for comparison.
     * Removes "SHA256:" prefix and converts to uppercase.
     */
    private fun normalizeFingerprint(fingerprint: String): String {
        return fingerprint
            .removePrefix("SHA256:")
            .uppercase()
            .replace(":", "")
    }
}
