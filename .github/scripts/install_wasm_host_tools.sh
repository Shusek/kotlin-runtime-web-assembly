#!/usr/bin/env bash
set -euo pipefail

: "${RUNNER_TEMP:?RUNNER_TEMP must be set}"
: "${GITHUB_ENV:?GITHUB_ENV must be set}"
: "${GITHUB_PATH:?GITHUB_PATH must be set}"

sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | cut -d ' ' -f 1
  else
    shasum -a 256 "$1" | cut -d ' ' -f 1
  fi
}

wasmtime_version="$(sed -n 's/^wasmtime = "\(.*\)"$/\1/p' gradle/libs.versions.toml)"
wasm_tools_version="$(sed -n 's/^wasmTools = "\(.*\)"$/\1/p' gradle/libs.versions.toml)"
test -n "$wasmtime_version"
test -n "$wasm_tools_version"

case "$(uname -s)-$(uname -m)" in
  Darwin-arm64)
    target="aarch64-macos"
    wasmtime_api_sha256="bff3b03d2a68aeb26f0b4557c5fc442e3e60f31d68b9b72c5a9c2beafc799f44"
    wasmtime_cli_sha256="323dc098dc0455d60f396c404627e967b7033ffcfa29abfffb243126cf82c9d6"
    wasm_tools_sha256="801b237643cc0f4ab27ebd1745e7863e23bf194a038bc6ca8ce5618212e43669"
    library_name="libwasmtime.dylib"
    ;;
  Darwin-x86_64)
    target="x86_64-macos"
    wasmtime_api_sha256="f521aa9e6fbb77a3cc25a8899ec9d407882edae219b2e4796a3a5ac2b8f44819"
    wasmtime_cli_sha256="ad14d9fb6a51d1633318426d6df21734127faddc81fe18a3afd70c5964eabeb5"
    wasm_tools_sha256="970b078911cad1a01a645c769de722cd44feee3355dcc4170c2ac3066154e5e9"
    library_name="libwasmtime.dylib"
    ;;
  Linux-aarch64|Linux-arm64)
    target="aarch64-linux"
    wasmtime_api_sha256="cf29164de6379e17f6175d42b6a828a8e045c56575cf84db8183337cc6c682cb"
    wasmtime_cli_sha256="10f9ad1f8539d2e04e041cc622de6a270109ab1e43a6be1f69843c2082166832"
    wasm_tools_sha256="729dc47a114305ed60873ce6199d403eed1da89c3aa2367eef292da358b1e8d3"
    library_name="libwasmtime.so"
    ;;
  Linux-x86_64)
    target="x86_64-linux"
    wasmtime_api_sha256="631643d8be5dac2a1abe026528f0ca18a515bce275bcfa7eb9731c9468a592f1"
    wasmtime_cli_sha256="77c19ee0437991f9ac7837b9c269d9c7076744f85dad263fea5214cc2723c7ff"
    wasm_tools_sha256="655b8df67d54c3ef9b52fc237c4e94bbfbb5f5f43825245cb0940a7002a9ed65"
    library_name="libwasmtime.so"
    ;;
  *)
    echo "Unsupported host architecture: $(uname -s)-$(uname -m)" >&2
    exit 1
    ;;
esac

wasmtime_api_archive="$RUNNER_TEMP/wasmtime-c-api.tar.xz"
wasmtime_cli_archive="$RUNNER_TEMP/wasmtime-cli.tar.xz"
wasmtime_root="$RUNNER_TEMP/wasmtime"
curl --fail --location --output "$wasmtime_api_archive" \
  "https://github.com/bytecodealliance/wasmtime/releases/download/v${wasmtime_version}/wasmtime-v${wasmtime_version}-${target}-c-api.tar.xz"
curl --fail --location --output "$wasmtime_cli_archive" \
  "https://github.com/bytecodealliance/wasmtime/releases/download/v${wasmtime_version}/wasmtime-v${wasmtime_version}-${target}.tar.xz"
test "$(sha256_file "$wasmtime_api_archive")" = "$wasmtime_api_sha256"
test "$(sha256_file "$wasmtime_cli_archive")" = "$wasmtime_cli_sha256"
mkdir -p "$wasmtime_root"
tar -xJf "$wasmtime_api_archive" -C "$wasmtime_root"
tar -xJf "$wasmtime_cli_archive" -C "$wasmtime_root"
wasmtime_library="$wasmtime_root/wasmtime-v${wasmtime_version}-${target}-c-api/lib/$library_name"
wasmtime_executable="$wasmtime_root/wasmtime-v${wasmtime_version}-${target}/wasmtime"
test -f "$wasmtime_library"
test -x "$wasmtime_executable"
printf 'KRWA_WASMTIME_LIBRARY=%s\n' "$wasmtime_library" >> "$GITHUB_ENV"
printf 'WASMTIME=%s\n' "$wasmtime_executable" >> "$GITHUB_ENV"
dirname "$wasmtime_executable" >> "$GITHUB_PATH"

wasm_tools_archive="$RUNNER_TEMP/wasm-tools.tar.gz"
wasm_tools_root="$RUNNER_TEMP/wasm-tools"
curl --fail --location --output "$wasm_tools_archive" \
  "https://github.com/bytecodealliance/wasm-tools/releases/download/v${wasm_tools_version}/wasm-tools-${wasm_tools_version}-${target}.tar.gz"
test "$(sha256_file "$wasm_tools_archive")" = "$wasm_tools_sha256"
mkdir -p "$wasm_tools_root"
tar -xzf "$wasm_tools_archive" -C "$wasm_tools_root"
wasm_tools_executable="$wasm_tools_root/wasm-tools-${wasm_tools_version}-${target}/wasm-tools"
test -x "$wasm_tools_executable"
printf 'KRWA_WASM_TOOLS=%s\n' "$wasm_tools_executable" >> "$GITHUB_ENV"
dirname "$wasm_tools_executable" >> "$GITHUB_PATH"
