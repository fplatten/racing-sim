plugins {
    application
    java
}

group = "com.racingsim"
version = "0.1.0"

val jmeVersion = "3.9.0-stable"
val minieVersion = "9.0.3"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

dependencies {
    implementation("org.jmonkeyengine:jme3-core:$jmeVersion")
    implementation("org.jmonkeyengine:jme3-desktop:$jmeVersion")
    implementation("org.jmonkeyengine:jme3-lwjgl3:$jmeVersion")
    // glTF loader (Blender-made car and trackside props) and post-processing filters
    implementation("org.jmonkeyengine:jme3-plugins:$jmeVersion")
    implementation("org.jmonkeyengine:jme3-effects:$jmeVersion")
    // Minie bundles the Bullet native libraries for desktop platforms.
    implementation("com.github.stephengold:Minie:$minieVersion")
    // Keep transitive jME modules (pulled in by Minie) on the same version.
    implementation("org.jmonkeyengine:jme3-terrain:$jmeVersion")
}

application {
    mainClass.set("com.racingsim.app.Main")
    val jvmArgsList = mutableListOf("-Xms512m", "-Xmx2g")
    if (System.getProperty("os.name").lowercase().contains("mac")) {
        jvmArgsList += "-XstartOnFirstThread"
    }
    applicationDefaultJvmArgs = jvmArgsList
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(21)
}

// Headless physics/AI/drafting run (no window): ./gradlew simCheck -Pseconds=150 -Pplayer=passive|erratic|ai
tasks.register<JavaExec>("simCheck") {
    group = "verification"
    description = "Runs the race simulation headless and prints telemetry."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.racingsim.HeadlessSimCheck")
    args(
        project.findProperty("seconds")?.toString() ?: "150",
        project.findProperty("verbose")?.toString() ?: "-",
        project.findProperty("cars")?.toString() ?: "8",
        project.findProperty("player")?.toString() ?: "ai",
        project.findProperty("laps")?.toString() ?: "25",
        project.findProperty("pits")?.toString() ?: "0",
        project.findProperty("downforce")?.toString() ?: "0"
    )
}

// Writes the synthesized engine / squeal / wind loops as WAV files: ./gradlew audioDump [-Pout=dir]
tasks.register<JavaExec>("audioDump") {
    group = "verification"
    description = "Writes the procedural audio loops to WAV files."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.racingsim.AudioDump")
    args(project.findProperty("out")?.toString() ?: "build/audio")
}

// Fires a car into the outer wall at several speeds / angles / yaws: ./gradlew wallCheck
tasks.register<JavaExec>("wallCheck") {
    group = "verification"
    description = "Measures how far wall impacts throw a car."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.racingsim.WallHitCheck")
}
