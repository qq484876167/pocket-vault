pluginManagement {
    repositories {
        // 这台机器上 repo1.maven.org 不可达，mavenCentral() 必须排在可达镜像之后
        google()
        maven("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")
        maven("https://repo.maven.apache.org/maven2/")
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        maven("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")
        maven("https://repo.maven.apache.org/maven2/")
        mavenCentral()
    }
}

rootProject.name = "PocketVault"
include(":app")
