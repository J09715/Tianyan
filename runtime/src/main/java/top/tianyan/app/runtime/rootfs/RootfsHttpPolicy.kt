package top.tianyan.app.runtime.rootfs

import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/** 5.10.0 download policy: TLS redirects stay on TLS; bound inactivity, not total transfer time. */
internal fun OkHttpClient.forRootfsDownloads(): OkHttpClient = newBuilder()
    .readTimeout(2, TimeUnit.MINUTES)
    .writeTimeout(2, TimeUnit.MINUTES)
    .callTimeout(0, TimeUnit.MILLISECONDS)
    .addNetworkInterceptor { chain ->
        val response = chain.proceed(chain.request())
        val location = response.header("Location")
        if (response.code in setOf(301, 302, 303, 307, 308) && location != null &&
            response.request.url.isHttps && response.request.url.resolve(location)?.isHttps == false
        ) {
            response.close()
            throw IOException("RootFS 下载禁止从 HTTPS 重定向到 HTTP")
        }
        response
    }
    .build()
