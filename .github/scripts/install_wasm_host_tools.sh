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
    wasmtime_api_sha256="d20c74557c515e6a7e68cf69adf0aa3eeaf863302c87f61d8c10976d96a882ed"
    wasmtime_cli_sha256="7eee840798c071e9eff501fe456cf031353556fd28d05d21aa3ace866643a941"
    wasm_tools_sha256="801b237643cc0f4ab27ebd1745e7863e23bf194a038bc6ca8ce5618212e43669"
    library_name="libwasmtime.dylib"
    ;;
  Darwin-x86_64)
    target="x86_64-macos"
    wasmtime_api_sha256="8c63e0b27af10bc84997e08462563717e6633a2ae322cadd2542f2714c5badae"
    wasmtime_cli_sha256="f0dd86c807657794d87c0a6e7b471c5c2c07f1b7640968adff5b3b45c57bafe5"
    wasm_tools_sha256="970b078911cad1a01a645c769de722cd44feee3355dcc4170c2ac3066154e5e9"
    library_name="libwasmtime.dylib"
    ;;
  Linux-aarch64|Linux-arm64)
    target="aarch64-linux"
    wasmtime_api_sha256="e90a63a0254497690010dfa82aa7b2be459916ab6ff0809e8a0a3a997e75b937"
    wasmtime_cli_sha256="cc07a1107b53e7f3caee171249e3718a244b90ddc206eec417b3033360db8f63"
    wasm_tools_sha256="729dc47a114305ed60873ce6199d403eed1da89c3aa2367eef292da358b1e8d3"
    library_name="libwasmtime.so"
    ;;
  Linux-x86_64)
    target="x86_64-linux"
    wasmtime_api_sha256="3d65ab7a10127632ec6d41f0340866272abd9dab8bc7b9fdc9deaea811ee3719"
    wasmtime_cli_sha256="2192e2b5b911dc42321b9eac2b7f1f0b0d03356058c1e2b2c2b892625bdd0f45"
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
