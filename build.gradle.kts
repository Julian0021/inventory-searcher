plugins {
    id("net.fabricmc.fabric-loom") version "1.18-SNAPSHOT"
}

val minecraftVersion = providers.gradleProperty("minecraft_version").get()
val loaderVersion = providers.gradleProperty("loader_version").get()
val fabricApiVersion = providers.gradleProperty("fabric_api_version").get()

base {
    archivesName = providers.gradleProperty("archives_base_name").get()
    group = providers.gradleProperty("maven_group").get()
    version = "${providers.gradleProperty("mod_version").get()}+$minecraftVersion"
}

repositories {
    exclusiveContent {
        forRepository {
            maven {
                name = "modrinth"
                url = uri("https://api.modrinth.com/maven")
            }
        }
        filter {
            includeGroup("maven.modrinth")
        }
    }
}

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")
    implementation("net.fabricmc:fabric-loader:$loaderVersion")

    // loads the language file from the mod jar, bundled so only Fabric Loader is needed;
    // the resource loader uses classes from fabric-api-base without declaring it, so both are bundled
    for (module in listOf("fabric-api-base", "fabric-resource-loader-v1")) {
        val dependency = fabricApi.module(module, fabricApiVersion)
        implementation(dependency)
        include(dependency)
    }

    // optional, only for the config button in Mod Menu
    compileOnly("maven.modrinth:modmenu:${providers.gradleProperty("modmenu_version").get()}")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

tasks {
    processResources {
        val properties = mapOf(
            "version" to project.version,
            "minecraft_version" to minecraftVersion,
            "loader_version" to loaderVersion
        )
        inputs.properties(properties)
        filesMatching("fabric.mod.json") {
            expand(properties)
        }
    }

    withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release = 25
    }

    jar {
        from("LICENSE")
    }
}

// local client gametests, only when gametest.gradle exists (it's not committed)
if (file("gametest.gradle").exists()) apply(from = "gametest.gradle")
