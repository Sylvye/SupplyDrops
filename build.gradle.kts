plugins { java }
group = "dev.supplydrops"
version = "1.0.0"
repositories { mavenCentral(); maven("https://repo.papermc.io/repository/maven-public/") }
val bundled by configurations.creating
configurations.implementation { extendsFrom(bundled) }
dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.129-stable")
    bundled("org.xerial:sqlite-jdbc:3.50.3.0")
    implementation("com.google.code.gson:gson:2.13.1")
    testImplementation("io.papermc.paper:paper-api:26.2.build.129-stable")
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
java { toolchain.languageVersion.set(JavaLanguageVersion.of(25)) }
tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8"; options.release.set(25) }
tasks.test { useJUnitPlatform() }
tasks.jar {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(bundled.map { if (it.isDirectory) it else zipTree(it) })
    exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA")
}

val integration by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output + sourceSets.main.get().compileClasspath
    runtimeClasspath += output + compileClasspath
}
tasks.register<Jar>("integrationJar") {
    dependsOn(tasks.named(integration.classesTaskName))
    archiveClassifier.set("integration")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(integration.output)
    from(sourceSets.main.get().output) { exclude("plugin.yml") }
    from(bundled.map { if (it.isDirectory) it else zipTree(it) })
    exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA")
}
