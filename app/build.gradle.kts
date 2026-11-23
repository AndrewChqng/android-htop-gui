import com.android.bundle.Commands.BuildApksResult
import com.android.bundle.Targeting.SdkVersionTargeting
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

plugins {
    id("com.android.application")
}

android {
    namespace = "com.andrewchqng.htop"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.andrewchqng.htop"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "1.3.0"
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    val keystorePropsFile = rootProject.file("keystore.properties")
    val keystoreProps = Properties().apply {
        if (keystorePropsFile.exists()) {
            keystorePropsFile.inputStream().use { load(it) }
        }
    }
    val releaseStoreFile = keystoreProps.getProperty("storeFile")
        ?: providers.environmentVariable("ANDROID_KEYSTORE_PATH").orNull
    val releaseStorePassword = keystoreProps.getProperty("storePassword")
        ?: providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").orNull
    val releaseKeyAlias = keystoreProps.getProperty("keyAlias")
        ?: providers.environmentVariable("ANDROID_KEY_ALIAS").orNull
    val releaseKeyPassword = keystoreProps.getProperty("keyPassword")
        ?: providers.environmentVariable("ANDROID_KEY_PASSWORD").orNull

    signingConfigs {
        if (!releaseStoreFile.isNullOrBlank() &&
            !releaseStorePassword.isNullOrBlank() &&
            !releaseKeyAlias.isNullOrBlank() &&
            !releaseKeyPassword.isNullOrBlank()
        ) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = false
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    packaging {
        jniLibs.useLegacyPackaging = true
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

val ensureBundledHtop = tasks.register<Exec>("ensureBundledHtop") {
    val jniDir = layout.projectDirectory.dir("src/main/jniLibs").asFile
    val requiredAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64")
    val forceFromSource = providers.gradleProperty("buildBundledHtopFromSource")
        .map { it.equals("true", ignoreCase = true) }
        .getOrElse(false)
    onlyIf {
        forceFromSource || requiredAbis.any { abi ->
            !File(jniDir, "$abi/libhtop_bundled.so").exists()
        }
    }
    workingDir = rootProject.projectDir
    commandLine("bash", rootProject.file("scripts/build-bundled-htop.sh").absolutePath)
}

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(ensureBundledHtop)
}

tasks.matching { it.name == "buildReleasePreBundle" }.configureEach {
    doFirst {
        val dir = layout.buildDirectory
            .dir("intermediates/shrunk_resources_proto_format/release/minifyReleaseWithR8")
            .get()
            .asFile
        dir.listFiles()
            ?.filter { it.name.endsWith(".ap_") && !it.name.contains("universal") }
            ?.forEach { it.delete() }
    }
}

val hasReleaseSigning = android.signingConfigs.findByName("release") != null

listOf("Debug" to "debug", "Release" to "release").forEach { (cap, lower) ->
    val isSigningConfigured = (lower == "debug") || hasReleaseSigning
    val packageApks = tasks.register("package${cap}Apks") {
        onlyIf { isSigningConfigured }
        dependsOn("makeApkFromBundleFor$cap")
        mustRunAfter("package$cap", "create${cap}ApkListingFileRedirect")
        val inputApks = layout.buildDirectory.file("intermediates/apks_from_bundle/$lower/makeApkFromBundleFor$cap/bundle.apks")
        val outputApks = layout.buildDirectory.file("outputs/apks/$lower/app-$lower.apks")
        inputs.file(inputApks)
        outputs.file(outputApks)
        doLast {
            val inFile = inputApks.get().asFile
            val outFile = outputApks.get().asFile
            outFile.parentFile.mkdirs()

            ZipFile(inFile).use { zipIn ->
                val tocEntry = zipIn.getEntry("toc.pb")
                    ?: error("Missing toc.pb in ${inFile.absolutePath}")
                val originalToc = zipIn.getInputStream(tocEntry).use { BuildApksResult.parseFrom(it) }
                val primaryVariant = originalToc.getVariant(0)
                val appId = android.defaultConfig.applicationId ?: "com.andrewchqng.htop"
                val pathRemap = LinkedHashMap<String, String>()

                val remappedApkSets = primaryVariant.apkSetList.map { apkSet ->
                    val remappedDescriptions = apkSet.apkDescriptionList.map { desc ->
                        val newPath = if (desc.hasSplitApkMetadata() && desc.splitApkMetadata.isMasterSplit) {
                            "splits/$appId-base.apk"
                        } else {
                            val splitId = desc.splitApkMetadata.splitId.ifEmpty {
                                desc.path.substringAfterLast('/').removePrefix("base-")
                            }
                            "splits/$splitId.apk"
                        }
                        pathRemap[desc.path] = newPath
                        desc.toBuilder().setPath(newPath).build()
                    }
                    apkSet.toBuilder()
                        .clearApkDescription()
                        .addAllApkDescription(remappedDescriptions)
                        .build()
                }

                val cleanVariant = primaryVariant.toBuilder()
                    .setTargeting(
                        primaryVariant.targeting.toBuilder()
                            .setSdkVersionTargeting(
                                SdkVersionTargeting.newBuilder()
                                    .addValue(primaryVariant.targeting.sdkVersionTargeting.getValue(0))
                                    .build(),
                            )
                            .build(),
                    )
                    .clearVariantNumber()
                    .clearApkSet()
                    .addAllApkSet(remappedApkSets)
                    .build()
                val cleanToc = originalToc.toBuilder()
                    .clearVariant()
                    .addVariant(cleanVariant)
                    .clearLocalTestingInfo()
                    .build()

                ZipOutputStream(outFile.outputStream().buffered()).use { zipOut ->
                    fun writeStoredEntry(name: String, bytes: ByteArray) {
                        val crc = CRC32().apply { update(bytes) }.value
                        val entry = ZipEntry(name).apply {
                            method = ZipEntry.STORED
                            size = bytes.size.toLong()
                            compressedSize = bytes.size.toLong()
                            this.crc = crc
                            time = 347158860000L
                        }
                        zipOut.putNextEntry(entry)
                        zipOut.write(bytes)
                        zipOut.closeEntry()
                    }

                    writeStoredEntry("toc.pb", cleanToc.toByteArray())
                    for ((oldPath, newPath) in pathRemap) {
                        val entry = zipIn.getEntry(oldPath)
                            ?: error("Missing split entry $oldPath in ${inFile.absolutePath}")
                        val bytes = zipIn.getInputStream(entry).use { it.readBytes() }
                        writeStoredEntry(newPath, bytes)
                    }
                }
            }
        }
    }
    if (isSigningConfigured) {
        tasks.matching { it.name == "assemble$cap" }.configureEach {
            finalizedBy(packageApks)
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.fromTarget("21")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    testImplementation("junit:junit:4.13.2")
}
