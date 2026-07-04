plugins {
    java
}

group = "com.gitmc"
version = "0.1"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.31-alpha")
}

tasks {
    processResources {
        filteringCharset = "UTF-8"
        filesMatching("plugin.yml") {
            expand("version" to project.version)
        }
    }

    jar {
        archiveBaseName.set("gitmc")
    }
}
