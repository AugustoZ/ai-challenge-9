plugins {
    kotlin("jvm") version "2.0.20"
    kotlin("plugin.serialization") version "2.0.20"
    application
}

group = "com.example"
version = "1.0.0"

repositories {
    mavenCentral()
}

val ktorVersion = "2.3.12"

dependencies {
    implementation("io.ktor:ktor-server-core-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-netty-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:$ktorVersion")
    implementation("io.ktor:ktor-client-core-jvm:$ktorVersion")
    implementation("io.ktor:ktor-client-cio-jvm:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation-jvm:$ktorVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(22)
}

application {
    mainClass.set("com.example.aiweb.ApplicationKt")
    applicationName = "aiweb"
}

// ---------- Фронтенд: Vite-сборка без node-плагина ----------
// Цепочка npmCi → buildFrontend (npm run verify) → copyFrontend.
// Все три задачи отключаются флагом -PskipFrontend; у Exec объявлены
// inputs/outputs, иначе up-to-date-слепота даёт stale dist.
// dist копируется в build/frontend-static/static (classpath-каталог),
// src/main/resources/static не перезаписывается — старое приложение
// живо до flip-коммита.

val frontendDir = layout.projectDirectory.dir("frontend")

tasks.register<Exec>("npmCi") {
    group = "build"
    description = "Установка npm-зависимостей фронтенда (npm ci)"
    workingDir = frontendDir.asFile
    commandLine("npm", "ci")
    inputs.file(frontendDir.file("package.json"))
    inputs.file(frontendDir.file("package-lock.json"))
    outputs.dir(frontendDir.dir("node_modules"))
    onlyIf { !project.hasProperty("skipFrontend") }
}

tasks.register<Exec>("buildFrontend") {
    group = "build"
    description = "Верификация и сборка фронтенда (npm run verify: tsc + eslint + vitest + vite build)"
    dependsOn(tasks.named("npmCi"))
    workingDir = frontendDir.asFile
    commandLine("npm", "run", "verify")
    inputs.dir(frontendDir.dir("src"))
    inputs.file(frontendDir.file("vite.config.ts"))
    inputs.file(frontendDir.file("tsconfig.json"))
    inputs.file(frontendDir.file("tsconfig.app.json"))
    inputs.file(frontendDir.file("tsconfig.node.json"))
    inputs.file(frontendDir.file("index.html"))
    inputs.file(frontendDir.file("package.json"))
    outputs.dir(frontendDir.dir("dist"))
    onlyIf { !project.hasProperty("skipFrontend") }
}

tasks.register<Copy>("copyFrontend") {
    group = "build"
    description = "Копирование dist фронтенда в каталог ресурсов classpath"
    dependsOn(tasks.named("buildFrontend"))
    from(frontendDir.dir("dist"))
    into(layout.buildDirectory.dir("frontend-static/static"))
    onlyIf { !project.hasProperty("skipFrontend") }
}

sourceSets {
    main {
        resources {
            // frontend-static подключается после src/main/resources:
            // при совпадении путей побеждает исходная статика (до flip)
            srcDir(layout.buildDirectory.dir("frontend-static"))
        }
    }
}

tasks.processResources {
    dependsOn(tasks.named("copyFrontend"))
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.named("check") {
    dependsOn(tasks.named("buildFrontend"))
}
