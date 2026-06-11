plugins {
  alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
  wasmWasi {
    binaries.executable()
  }
}
