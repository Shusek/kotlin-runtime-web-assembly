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
    wasmtime_api_sha256="9e3c636ed487a41026ff76388c5fa6f3a48ea0968408d033ed4b5e8082c20d69"
    wasmtime_cli_sha256="88cc08b395fbfb960b99f355a81224af975679b8a5f4b74a51d59e5e34b20dcd"
    wasm_tools_sha256="58bf83fdfa59da2c70ac6eb8dd395870934d8e3af835ff9311f34b9072586547"
    library_name="libwasmtime.dylib"
    ;;
  Darwin-x86_64)
    target="x86_64-macos"
    wasmtime_api_sha256="a5d92170718d41e4bd08173049019f0cedb318d0156365a52667d4a35ea3ca69"
    wasmtime_cli_sha256="ce95a41b85adaf2c44f47cb68defb282bdb87d68ed96d1662295330fe542335e"
    wasm_tools_sha256="21f0d003c5a937f29fe4cbbcb947b41ed7cc14982b8680abb15ba4078cb6a227"
    library_name="libwasmtime.dylib"
    ;;
  Linux-aarch64|Linux-arm64)
    target="aarch64-linux"
    wasmtime_api_sha256="1c521a9be661644541158b360df8f7c7ec5bc2d88d23ff4dbbc12f639247c266"
    wasmtime_cli_sha256="fdbebd838ed7b9cc4e2b63f6d7d855b33386fc388f3595f668bf394131dd072f"
    wasm_tools_sha256="b51adcd4b7e2b85c689af3a1800534e7de192fdf47b1b6b6a8b5bcb0f449c392"
    library_name="libwasmtime.so"
    ;;
  Linux-x86_64)
    target="x86_64-linux"
    wasmtime_api_sha256="67683d04b416a8b91f0e607e7b4c22bd32f18f947c10b5372eb8c277ae3b883a"
    wasmtime_cli_sha256="4c2e31b68ad99e0a519f225a261fda099eb15f056d4a24fdb3c2a46517bde1df"
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
