pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        // ObjectBox plugin repository
        maven { 
            url = uri("https://maven.objectbox.io/")
            content {
                includeGroup("io.objectbox")
            }
        }
    }
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "io.objectbox") {
                useModule("io.objectbox:objectbox-gradle-plugin:${requested.version}")
            }
        }
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Guardian Project raw GitHub Maven (hosts info.guardianproject:arti-mobile-ex)
        maven { 
            url = uri("https://raw.githubusercontent.com/guardianproject/gpmaven/master")
            content {
                includeGroup("info.guardianproject")
            }
        }
        // ObjectBox repository (for RAG vector database dependencies)
        maven { 
            url = uri("https://maven.objectbox.io/")
            content {
                includeGroup("io.objectbox")
            }
        }
    }
}

rootProject.name = "bitchat-android"
include(":app")
// Using published Arti AAR; local module not included
