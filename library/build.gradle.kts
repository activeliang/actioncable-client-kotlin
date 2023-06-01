import Versions.VERSION_NAME
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("kotlin")
    id("maven-publish")
}

group = "com.github.activeliang"

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "1.8"
}

tasks.test {
    useJUnitPlatform {
        excludeTags("slow")
        includeEngines("junit-jupiter")
        failFast = true
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = "$group"
            artifactId = "actioncable-client-kotlin"
            version = VERSION_NAME
            from(components["java"])
        }
    }
}

tasks {
    val sourcesJar by creating(Jar::class) {
        archiveClassifier.set("sources")
        from(sourceSets.main.get().allSource)
    }

    val javadocJar by creating(Jar::class) {
        dependsOn.add(javadoc)
        archiveClassifier.set("javadoc")
        from(javadoc)
    }

    artifacts {
        archives(sourcesJar)
        archives(javadocJar)
        archives(jar)
    }
}

dependencies {
    implementation(Libs.KOTLIN_STDLIB)
    implementation(Libs.KOTLIN_COROUTINES)
    implementation(Libs.OKHTTP)
    implementation(Libs.GSON)
    testImplementation(Libs.JUNIT)
    testImplementation(Libs.MOCKWEBSERVER)
    testImplementation(Libs.MOCKITO_KOTLIN)
    testImplementation(Libs.MOCKITO_INLINE)
}
