package br.geomaps.data

import android.accounts.Account
import android.content.Context
import br.geomaps.core.Json
import br.geomaps.core.jArr
import br.geomaps.core.jObj
import br.geomaps.core.jStr
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** Autorização Google para a pasta oculta do app no Drive (escopo drive.appdata, não sensível). */
object DriveAuth {
    const val SCOPE = "https://www.googleapis.com/auth/drive.appdata"

    fun request(account: String?): AuthorizationRequest {
        val b = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(SCOPE)))
        if (!account.isNullOrBlank()) b.setAccount(Account(account, "com.google"))
        return b.build()
    }

    /** Token sem interação (segundo plano). Retorna null se o usuário precisar autorizar de novo. */
    fun tokenSilently(context: Context, account: String): String? {
        val r = Tasks.await(Identity.getAuthorizationClient(context).authorize(request(account)), 30, TimeUnit.SECONDS)
        return if (r.hasResolution()) null else r.accessToken
    }

    /** Descarta um token recusado pelo servidor (expirado) para que o próximo pedido gere outro. */
    fun invalidate(context: Context, token: String) {
        runCatching { GoogleAuthUtil.clearToken(context, token) }
    }
}

class DriveAuthException(msg: String) : IOException(msg)

/** Cliente mínimo da Drive API v3 (REST) restrito ao appDataFolder. */
class DriveApi(private val token: String) {
    data class RFile(val id: String, val name: String, val version: Long, val props: Map<String, String>)

    companion object {
        private const val API = "https://www.googleapis.com/drive/v3"
        private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
        private const val FIELDS = "id,name,version,appProperties"
    }

    private fun open(url: String, method: String = "GET"): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20000; c.readTimeout = 60000
        c.setRequestProperty("Authorization", "Bearer $token")
        if (method == "PATCH") {
            // HttpURLConnection não aceita PATCH: as APIs do Google aceitam o método por cabeçalho
            c.requestMethod = "POST"
            c.setRequestProperty("X-HTTP-Method-Override", "PATCH")
        } else c.requestMethod = method
        return c
    }

    private fun check(c: HttpURLConnection): HttpURLConnection {
        val code = c.responseCode
        if (code == 401) throw DriveAuthException("Autorização do Google expirada")
        if (code !in 200..299) {
            val err = c.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) }?.take(300) ?: ""
            throw IOException("Google Drive respondeu HTTP $code $err")
        }
        return c
    }

    private fun readJson(c: HttpURLConnection): Map<String, Any?> =
        Json.parse(check(c).inputStream.use { it.readBytes().toString(Charsets.UTF_8) }).jObj() ?: emptyMap()

    private fun rfile(m: Map<String, Any?>) = RFile(
        id = m["id"].jStr() ?: "", name = m["name"].jStr() ?: "",
        version = m["version"].jStr()?.toLongOrNull() ?: (m["version"] as? Number)?.toLong() ?: 0L,
        props = m["appProperties"].jObj()?.mapValues { it.value.jStr() ?: "" } ?: emptyMap(),
    )

    /** E-mail da conta conectada. */
    fun email(): String? = runCatching {
        readJson(open("$API/about?fields=user(emailAddress)"))["user"].jObj()?.get("emailAddress").jStr()
    }.getOrNull()

    /** Todos os arquivos da pasta oculta do app. */
    fun list(): List<RFile> {
        val out = ArrayList<RFile>()
        var page: String? = null
        do {
            val url = "$API/files?spaces=appDataFolder&pageSize=1000&fields=" + URLEncoder.encode("nextPageToken,files($FIELDS)", "UTF-8") +
                (page?.let { "&pageToken=" + URLEncoder.encode(it, "UTF-8") } ?: "")
            val m = readJson(open(url))
            m["files"].jArr()?.forEach { f -> f.jObj()?.let { out += rfile(it) } }
            page = m["nextPageToken"].jStr()
        } while (page != null)
        return out
    }

    fun download(id: String): ByteArray = check(open("$API/files/$id?alt=media")).inputStream.use { it.readBytes() }

    fun delete(id: String) {
        val c = open("$API/files/$id", "DELETE")
        if (c.responseCode == 404) return
        check(c)
    }

    fun create(name: String, props: Map<String, String>, bytes: ByteArray, mime: String): RFile =
        upload("$UPLOAD/files?uploadType=multipart&fields=$FIELDS", "POST", mapOf("name" to name, "parents" to listOf("appDataFolder"), "appProperties" to props), bytes, mime)

    fun update(id: String, props: Map<String, String>, bytes: ByteArray, mime: String): RFile =
        upload("$UPLOAD/files/$id?uploadType=multipart&fields=$FIELDS", "PATCH", mapOf("appProperties" to props), bytes, mime)

    private fun upload(url: String, method: String, meta: Map<String, Any?>, bytes: ByteArray, mime: String): RFile {
        val boundary = "geomaps" + System.nanoTime()
        val body = ByteArrayOutputStream().apply {
            write("--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n".toByteArray())
            write(Json.write(meta).toByteArray())
            write("\r\n--$boundary\r\nContent-Type: $mime\r\n\r\n".toByteArray())
            write(bytes)
            write("\r\n--$boundary--\r\n".toByteArray())
        }.toByteArray()
        val c = open(url, method)
        c.doOutput = true
        c.setRequestProperty("Content-Type", "multipart/related; boundary=$boundary")
        c.setFixedLengthStreamingMode(body.size)
        c.outputStream.use { it.write(body) }
        return rfile(readJson(c))
    }
}
