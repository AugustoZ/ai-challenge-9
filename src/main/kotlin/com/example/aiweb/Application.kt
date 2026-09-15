package com.example.aiweb

import com.example.aiweb.auth.ShturmanMigration
import com.example.aiweb.config.AppConfig
import com.example.aiweb.routes.aiRoutes
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.http.content.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json

fun main() {
    val config = AppConfig.fromEnv()

    embeddedServer(Netty, port = config.port, host = "0.0.0.0") {
        module(config)
    }.start(wait = true)
}

fun Application.module(config: AppConfig) {
    install(ContentNegotiation) {
        json(
            Json {
                ignoreUnknownKeys = true
                isLenient = true
            }
        )
    }

    // staticResources свой Cache-Control не добавляет — ставим до роутинга:
    // хэшированные сборки Vite кэшируются «вечно», favicon/index.html — без кэша.
    intercept(ApplicationCallPipeline.Plugins) {
        val path = call.request.path()
        if (path.startsWith("/static/")) {
            call.response.header(
                HttpHeaders.CacheControl,
                if (path.startsWith("/static/assets/")) {
                    "public, max-age=31536000, immutable"
                } else {
                    "no-cache"
                }
            )
        }
    }

    // Идемпотентная миграция «Шумахер» (задача 10 плана персонализации): legacy-сессии
    // без владельца и общий memory/longterm.json наследуются пользователем «Шумахер».
    // ДО построения маршрутов: сторы aiRoutes читают файлы с диска при создании.
    // План размещал bootstrap в aiRoutes, но Routes.kt в этой волне не трогается
    // (задача 12 владеет файлом); Application.module — тот же момент жизненного
    // цикла «при старте приложения».
    ShturmanMigration.run(config.dataDir)

    routing {
        staticResources("/static", "static")
        aiRoutes(config)
        // dist Gradle-задачей copyFrontend копируется в classpath static/index.html
        get("/") {
            val index = Application::class.java.classLoader
                .getResourceAsStream("static/index.html")
                ?.use { it.readBytes() }
            if (index == null) {
                call.respond(
                    HttpStatusCode.NotFound,
                    "Фронтенд не собран: выполните ./gradlew build (или npm run build в frontend/)"
                )
            } else {
                call.response.header(HttpHeaders.CacheControl, "no-cache")
                call.respondBytes(index, ContentType.Text.Html)
            }
        }
    }
}
