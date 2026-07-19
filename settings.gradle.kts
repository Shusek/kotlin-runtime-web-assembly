pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "krwa"

fun includeProject(path: String, directory: String) {
    include(path)
    project(path).projectDir = file(directory)
}

includeProject(":annotations:annotations", "modules/annotations/annotations")
includeProject(":annotations:processor", "modules/annotations/processor")
project(":annotations").projectDir = file("modules/annotations")

includeProject(":bom", "modules/bom")
includeProject(":cli", "tools/cli")
includeProject(":codegen", "tools/codegen")
includeProject(":component-model-gradle-plugin", "tools/component-model-gradle-plugin")
includeProject(":component-model", "modules/component-model")
includeProject(":jmh", "testing/jmh")
includeProject(":ios-runtime-smoke", "samples/ios-runtime-smoke")
includeProject(":log", "modules/log")
includeProject(":runtime", "modules/runtime")
includeProject(":runtime-wasmtime-android", "modules/runtime-wasmtime-android")
includeProject(":runtime-tests", "testing/runtime-tests")
includeProject(":test-gen-lib", "tools/test-gen-lib")
includeProject(":wabt", "tools/wabt")
includeProject(":wasi", "modules/wasi")
includeProject(":wasi-preview3", "modules/wasi-preview3")
includeProject(":wasi-test-gen", "tools/wasi-test-gen")
includeProject(":wasi-tests", "testing/wasi-tests")
includeProject(":wasm", "modules/wasm")
includeProject(":wasm-corpus", "testing/wasm-corpus")
includeProject(":wasm-tools", "tools/wasm-tools")
