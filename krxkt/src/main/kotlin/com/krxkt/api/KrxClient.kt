package com.krxkt.api

import com.krxkt.error.KrxError
import kotlinx.coroutines.delay
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

/**
 * KRX API HTTP 클라이언트
 *
 * 특징:
 * - OkHttp 기반 POST 요청
 * - application/x-www-form-urlencoded 인코딩
 * - 필수 Referer 헤더 포함
 * - Exponential backoff 재시도 (3회: 1s/2s/4s)
 * - 쿠키 자동 관리 (세션 유지)
 * - 세션 초기화로 JSESSIONID 획득
 *
 * Referer 전략:
 * - 일반 통계 (MDCSTAT00301 등): outerLoader Referer → 세션 불필요
 * - 파생상품 (MDCSTAT01201, MDCSTAT13102): mdiLoader Referer → 세션 필요
 *
 * @param okHttpClient 커스텀 OkHttpClient (테스트용)
 * @param baseUrl 기본 API URL (테스트/프록시용)
 * @param sessionInitUrl 세션 초기화 URL (테스트용)
 */
class KrxClient(
    private val okHttpClient: OkHttpClient = createDefaultClient(),
    private val baseUrl: String = KrxEndpoints.BASE_URL,
    private val sessionInitUrl: String = KrxEndpoints.SESSION_INIT_URL
) {
    @Volatile
    private var sessionInitialized = false

    companion object {
        private const val MAX_RETRIES = 3
        private val RETRY_DELAYS_MS = listOf(1000L, 2000L, 4000L)

        /** Maximum response body size: 50MB (KRX full-market responses can be large) */
        private const val MAX_RESPONSE_SIZE_BYTES = 50L * 1024 * 1024

        /**
         * 기본 OkHttpClient 생성
         * - 연결 타임아웃: 30초
         * - 읽기 타임아웃: 30초
         * - 쓰기 타임아웃: 30초
         * - InMemoryCookieJar로 세션 쿠키 관리
         */
        fun createDefaultClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .cookieJar(InMemoryCookieJar())
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build()
        }
    }

    /**
     * KRX 세션 초기화
     *
     * KRX 메인 페이지를 GET 요청하여 JSESSIONID 쿠키를 획득.
     * 파생상품 통계 등 세션 검증이 엄격한 엔드포인트 호출 전에 사용.
     */
    fun initSession() {
        if (sessionInitialized) return

        val request = Request.Builder()
            .url(sessionInitUrl)
            .get()
            .addHeader("User-Agent", KrxEndpoints.USER_AGENT)
            .addHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .build()

        try {
            okHttpClient.newCall(request).execute().close()
            sessionInitialized = true
        } catch (_: IOException) {
            // 세션 초기화 실패는 무시 — API 호출 시 재시도됨
        }
    }

    /**
     * KRX API POST 요청 실행 (기본 Referer)
     *
     * @param params 요청 파라미터 (bld, mktId, trdDd 등)
     * @return JSON 응답 문자열
     * @throws KrxError.NetworkError 네트워크 에러 (재시도 후에도 실패)
     */
    suspend fun post(params: Map<String, String>): String {
        return post(params, KrxEndpoints.REFERER)
    }

    /**
     * KRX API POST 요청 실행 (커스텀 Referer)
     *
     * 파생상품 통계처럼 mdiLoader Referer가 필요한 엔드포인트에서 사용.
     *
     * @param params 요청 파라미터
     * @param referer 커스텀 Referer URL
     * @return JSON 응답 문자열
     * @throws KrxError.NetworkError 네트워크 에러
     */
    suspend fun post(params: Map<String, String>, referer: String): String {
        var lastException: Exception? = null

        repeat(MAX_RETRIES) { attempt ->
            try {
                return executeRequest(params, referer)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                lastException = e
                if (attempt < MAX_RETRIES - 1) {
                    if (e.message?.contains("LOGOUT") == true) {
                        sessionInitialized = false
                        initSession()
                    }
                    delay(RETRY_DELAYS_MS[attempt])
                }
            }
        }

        throw KrxError.NetworkError(
            "Failed after $MAX_RETRIES attempts: ${lastException?.message}",
            lastException
        )
    }

    /**
     * 실제 HTTP 요청 실행
     */
    private fun executeRequest(params: Map<String, String>, referer: String): String {
        val formBody = FormBody.Builder().apply {
            params.forEach { (key, value) ->
                add(key, value)
            }
        }.build()

        val bld = params["bld"] ?: "unknown"

        val request = Request.Builder()
            .url(baseUrl)
            .post(formBody)
            .addHeader("Referer", referer)
            .addHeader("User-Agent", KrxEndpoints.USER_AGENT)
            .addHeader("Accept", "application/json, text/javascript, */*; q=0.01")
            .addHeader("Accept-Language", "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7")
            .addHeader("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            .addHeader("X-Requested-With", "XMLHttpRequest")
            .addHeader("Origin", "https://data.krx.co.kr")
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            val body = response.body?.string()

            // LOGOUT 감지: 200 또는 400 모두에서 발생 가능
            if (body?.trim() == "LOGOUT") {
                throw IOException("KRX session expired or access denied (LOGOUT). Try from Korean network or use VPN.")
            }

            if (!response.isSuccessful) {
                val errorSnippet = body?.take(500)
                throw IOException("Unexpected response code: ${response.code} for bld=$bld, body=$errorSnippet")
            }

            if (body == null) {
                throw IOException("Empty response body")
            }

            if (body.length > MAX_RESPONSE_SIZE_BYTES) {
                throw IOException("Response too large: ${body.length} bytes exceeds limit of $MAX_RESPONSE_SIZE_BYTES bytes")
            }

            return body
        }
    }

    /**
     * 리소스 정리 (OkHttpClient 연결 풀)
     */
    fun close() {
        okHttpClient.dispatcher.executorService.shutdown()
        okHttpClient.connectionPool.evictAll()
    }
}

/**
 * 메모리 기반 쿠키 저장소
 *
 * KRX 세션 쿠키(JSESSIONID)를 자동 관리.
 * requests.Session()의 쿠키 관리와 동일한 역할.
 *
 * 스레드 안전: synchronized(lock) 으로 동시 접근 보호.
 * OkHttp는 여러 스레드에서 CookieJar를 호출할 수 있으므로 필수.
 */
class InMemoryCookieJar : CookieJar {
    private val lock = Any()
    private val store = mutableListOf<Cookie>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        synchronized(lock) {
            cookies.forEach { newCookie ->
                store.removeAll { it.name == newCookie.name && it.domain == newCookie.domain }
            }
            store.addAll(cookies)
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            store.removeAll { it.expiresAt < now }
            return store.filter { it.matches(url) }.toList()  // 방어적 복사
        }
    }
}
