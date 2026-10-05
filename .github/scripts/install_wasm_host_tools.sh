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
    wasmtime_api_sha256="92e74e76ef06ae699beec893cf3268d439da4d05dbca020e8f608ca3f0c1e862"
    wasmtime_cli_sha256="36d78cf1a5c8a5f40c0b9e933208ac3d937e868aca44b3cbbe1b48a3a530cf47"
    wasm_tools_sha256="94c4fc9baeada6e793f0d4adeca537633b5d93e9dc6bf718be2f478f4afe0296"
    library_name="libwasmtime.dylib"
    ;;
  Darwin-x86_64)
    target="x86_64-macos"
    wasmtime_api_sha256="47789d6be9ae03d4ba0ff13821e1d069bbaa3a8926bcd4b701482c3149539556"
    wasmtime_cli_sha256="b28b3987e6ba5a18d39dc3dc60e7c312e3fd3dcd5b2cb7a7f7fb9cfb834a6d0e"
    wasm_tools_sha256="354a84ffeede30ba11f29bc72de76875a977cf43bf1c5bdc3e91e2313e8a854b"
    library_name="libwasmtime.dylib"
    ;;
  Linux-aarch64|Linux-arm64)
    target="aarch64-linux"
    wasmtime_api_sha256="3fd35813cf4a922b968fa3dbd12a300ecea032181a895c4ffe7b1f6f2824fca6"
    wasmtime_cli_sha256="8713dcbc6f9427eb120d7a4b514b6a58597a369e5b5d79cebeaae11fa24479f5"
    wasm_tools_sha256="6f19389a69e21c7dbd2a4e7ae72f807dc999988b48782c706c4102bad3e44487"
    library_name="libwasmtime.so"
    ;;
  Linux-x86_64)
    target="x86_64-linux"
    wasmtime_api_sha256="81fffe5fe895c7f8f84a744f4d4d165dda28793d00ee943ee71fcfb6ab5ed923"
    wasmtime_cli_sha256="f533a0fb3eca20aee34d69192f214839c5227a507fff2ecb3070ae1a37f0c40d"
    wasm_tools_sha256="ad62b2176037e93e1348cb65d6212d128ca9f097b63d155569f25215818ff7b1"
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
