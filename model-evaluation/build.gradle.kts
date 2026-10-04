plugins { kotlin("jvm"); kotlin("plugin.serialization"); application }
kotlin { jvmToolchain(21) }
kotlin.sourceSets.main { kotlin.srcDir("../app/src/runtimeEvaluation/java"); kotlin.exclude("**/LiteRtRuntimeEvaluationHarness.kt"); kotlin.srcDir(layout.buildDirectory.dir("generated/provider")) }
val extractProvider by tasks.registering(Exec::class) {
    inputs.file("src/legacyProvider/LiteRtRecoveryProvider.prompt3.kt.txt")
    inputs.file("../scripts/model-batch-extract.py")
    inputs.file("../app/src/runtimeEvaluation/java/jp/n624/takupoke/android/LiteRtEvaluationFixtures.kt")
    outputs.dir(layout.buildDirectory.dir("generated/provider"))
    commandLine("python3", "../scripts/model-batch-extract.py", "src/legacyProvider/LiteRtRecoveryProvider.prompt3.kt.txt", layout.buildDirectory.dir("generated/provider").get().asFile,
        "../app/src/runtimeEvaluation/java/jp/n624/takupoke/android/LiteRtEvaluationFixtures.kt")
}
tasks.named("compileKotlin") { dependsOn(extractProvider) }
application { mainClass.set("jp.n624.takupoke.evaluation.ModelBatchKt") }
dependencies {
    implementation(project(":core"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("com.google.code.gson:gson:2.13.2")
    testImplementation(kotlin("test"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.13.4")
}
tasks.test { useJUnitPlatform() }
