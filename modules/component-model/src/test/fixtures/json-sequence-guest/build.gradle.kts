plugins {
  alias(libs.plugins.kotlinMultiplatform)
  alias(libs.plugins.kotlinSerialization)
}

kotlin {
  wasmWasi {
    binaries.executable()
  }
  sourceSets {
    wasmWasiMain.dependencies {
      implementation(libs.kotlinxIoCore)
      implementation(libs.kotlinxSerializationJson)
      implementation(libs.kotlinxSerializationJsonIo)
    }
  }
}
