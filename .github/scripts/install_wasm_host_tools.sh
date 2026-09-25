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
    wasmtime_api_sha256="c4b95346d92168963607f8ac56ef70298aa83b9d2b23af3e801759df3443ed09"
    wasmtime_cli_sha256="8abc55958f04678bf01d4d0c46868f45aa8083bacc29e5a1685f971f110fbc14"
    wasm_tools_sha256="58bf83fdfa59da2c70ac6eb8dd395870934d8e3af835ff9311f34b9072586547"
    library_name="libwasmtime.dylib"
    ;;
  Darwin-x86_64)
    target="x86_64-macos"
    wasmtime_api_sha256="5fbf2c7700063282000dcefe9d0691ac9e27f2f213746acd56f42d1772cebe4a"
    wasmtime_cli_sha256="e877b5daf52f4b668b0eb2d28b04b4c8c58bf39c96d1edb31fcdcf125e82b102"
    wasm_tools_sha256="21f0d003c5a937f29fe4cbbcb947b41ed7cc14982b8680abb15ba4078cb6a227"
    library_name="libwasmtime.dylib"
    ;;
  Linux-aarch64|Linux-arm64)
    target="aarch64-linux"
    wasmtime_api_sha256="af6d19b2bf6a3147b7550356f557dc5a5e538a70c099f4c435d12311e20e5c6c"
    wasmtime_cli_sha256="230aa7104d3e25da303fc30925ca606fcfb7b8a5d45d1bfee68db04e217be45e"
    wasm_tools_sha256="b51adcd4b7e2b85c689af3a1800534e7de192fdf47b1b6b6a8b5bcb0f449c392"
    library_name="libwasmtime.so"
    ;;
  Linux-x86_64)
    target="x86_64-linux"
    wasmtime_api_sha256="d9a2b5dfaf688035f288a7ae81a4b96c3acdd3e849262c2ab577b61908c3f9f9"
    wasmtime_cli_sha256="f2b0ad1ce9253f2f9a38793c2c42cd1cba4e90b27dc40d685eaf723dc8438d94"
    wasm_tools_sha256="a62237f4731c45f665f1115cad39acaeec02963cbc848c9473ab033eed837072"
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
