#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" &> /dev/null && pwd)
ROOT_DIR="${SCRIPT_DIR}/.."
TMP_ROOT="${KRWA_COREMARK_TMP_ROOT:-/private/tmp}"
TASK="${1:-:jmh:coremarkChasmStableGate}"

if [ "$#" -gt 0 ]; then
  shift
fi

RUN_DIR=$(mktemp -d "${TMP_ROOT%/}/krwa-coremark-clean.XXXXXX")
COPY_DIR="${RUN_DIR}/repo"
PATCH_FILE="${RUN_DIR}/benchmark-and-compiler.patch"
PATCH_PATHS=(
  testing/jmh/build.gradle.kts
  testing/jmh/src/main/kotlin/uk/shusek/krwa/bench/ChasmCoremark.kt
  testing/jmh/src/main/kotlin/uk/shusek/krwa/bench/CoremarkCompiledClassDump.kt
  testing/jmh/src/main/kotlin/uk/shusek/krwa/bench/CoremarkDynamicOpcodeReport.kt
  testing/jmh/src/main/kotlin/uk/shusek/krwa/bench/CoremarkFrameSlotPlanReport.kt
  testing/jmh/src/main/kotlin/uk/shusek/krwa/bench/CoremarkFunctionCallReport.kt
  testing/jmh/src/main/kotlin/uk/shusek/krwa/bench/CoremarkLoweredFunctionReport.kt
  testing/jmh/src/main/kotlin/uk/shusek/krwa/bench/CoremarkLoweredOpcodeReport.kt
  testing/jmh/src/main/kotlin/uk/shusek/krwa/bench/CoremarkOpcodeReport.kt
  testing/jmh/src/main/kotlin/uk/shusek/krwa/bench/CoremarkRunner.kt
  tools/compiler/src/main/kotlin/uk/shusek/krwa/compiler/MachineFactoryCompiler.kt
  tools/compiler/src/main/kotlin/uk/shusek/krwa/compiler/internal/Compiler.kt
  tools/compiler/src/main/kotlin/uk/shusek/krwa/compiler/internal/Context.kt
  tools/compiler/src/main/kotlin/uk/shusek/krwa/compiler/internal/Emitters.kt
  tools/compiler/src/main/kotlin/uk/shusek/krwa/compiler/internal/Shaded.kt
  tools/compiler/src/main/kotlin/uk/shusek/krwa/compiler/internal/ShadedRefs.kt
)

mkdir -p "${COPY_DIR}"

echo "Preparing clean CoreMark worktree at ${COPY_DIR}"

(
  cd "${ROOT_DIR}"

  git diff --binary HEAD -- "${PATCH_PATHS[@]}" > "${PATCH_FILE}"
  for path in "${PATCH_PATHS[@]}"; do
    if [ -f "${path}" ] && ! git ls-files --error-unmatch -- "${path}" > /dev/null 2>&1; then
      git diff --binary --no-index -- /dev/null "${path}" >> "${PATCH_FILE}" || true
    fi
  done

  git archive --format=tar HEAD | tar -x -C "${COPY_DIR}"
)

(
  cd "${COPY_DIR}"

  if [ -s "${PATCH_FILE}" ]; then
    git init -q
    git apply --whitespace=nowarn "${PATCH_FILE}"
  fi

  echo "Running ./gradlew --no-daemon ${TASK} $*"
  ./gradlew --no-daemon "${TASK}" "$@"
)

echo "Clean CoreMark worktree kept at ${COPY_DIR}"
