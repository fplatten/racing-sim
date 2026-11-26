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

// Paints every car's livery and helmet once at build time (about 1.5 s of CPU each at run time);
// the PNGs are packaged under Textures/Liveries/ and the game falls back to painting if one is missing.
val liveryDir = layout.buildDirectory.dir("generated/liveries")
val bakeLiveries = tasks.register<JavaExec>("bakeLiveries") {
    group = "build"
    description = "Pre-renders the livery and helmet textures of all 33 cars."
    classpath = sourceSets["main"].output.classesDirs + configurations["runtimeClasspath"] + files("src/main/resources")
    mainClass.set("com.racingsim.vehicle.LiveryBaker")
    args(liveryDir.get().asFile.absolutePath)
    inputs.files(sourceSets["main"].output.classesDirs)
    outputs.dir(liveryDir)
}
sourceSets["main"].resources.srcDir(files(liveryDir).builtBy(bakeLiveries))

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(21)
}

// Tuning overrides, passed on to the game (run), simCheck and carCheck as system properties:
// -PaiSling=2 -PpowerScale=1.1 ... (the AI's -Pai*= settings in ai/AIDriver.java and app/RaceDirector.java,
// the engine's -PpowerScale= and -PfinalDrive= in vehicle/VehicleSpec.java), -Ptrack=road (the road course)
// and -Pcar=indy|f1 (that car on either track; by default the Indy car races the oval, the Formula car the road);
// -PplayerPaint=N gives the player's Formula car another team's paint scheme (vehicle/CarPaint)
fun JavaExec.passTuning() {
    project.properties.filterKeys { it.matches(Regex("ai[A-Z]\\w*|powerScale|finalDrive|track|car|carTrace|playerPaint")) }
        .forEach { (k, v) -> systemProperty(k, v.toString()) }
}
tasks.named<JavaExec>("run") { passTuning() }

// Headless physics/AI/drafting run (no window): ./gradlew simCheck -Pseconds=150 -Pplayer=passive|erratic|ai
tasks.register<JavaExec>("simCheck") {
    group = "verification"
    description = "Runs the race simulation headless and prints telemetry."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.racingsim.HeadlessSimCheck")
    passTuning()
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

// Launch, braking and top speed of one car on the oval's front straight, at minimum and maximum
// wing: ./gradlew carCheck [-Pcar=indy|f1] (the Indy car unless -Pcar= says otherwise)
tasks.register<JavaExec>("carCheck") {
    group = "verification"
    description = "Measures 0-60 / 0-100 mph, the stop from 200 mph and the top speed of a car."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.racingsim.CarCheck")
    passTuning()
}

// Fires a car into the outer wall at several speeds / angles / yaws: ./gradlew wallCheck
tasks.register<JavaExec>("wallCheck") {
    group = "verification"
    description = "Measures how far wall impacts throw a car."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.racingsim.WallHitCheck")
}
