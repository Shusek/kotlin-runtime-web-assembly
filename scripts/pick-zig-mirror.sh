#!/usr/bin/env bash
set -euo pipefail

OFFICIAL_DOWNLOAD_URL="https://ziglang.org/download"
KNOWN_MIRROR_URL="https://pkg.hexops.org/zig"
MIN_ARCHIVE_SIZE_BYTES=$((1024 * 1024))
curl_probe_opts=(--head --fail --location --silent --show-error --retry 3 --retry-all-errors --retry-delay 2)

candidate_urls() {
  local mirrors

  if mirrors=$(curl --fail --silent --show-error --location --retry 3 --retry-all-errors --retry-delay 2 https://ziglang.org/download/community-mirrors.txt 2> /dev/null); then
    if command -v shuf > /dev/null 2>&1; then
      printf '%s\n' ${mirrors} | shuf
    else
      printf '%s\n' ${mirrors}
    fi
  fi

  printf '%s\n' "${KNOWN_MIRROR_URL}" "${OFFICIAL_DOWNLOAD_URL}"
}

artifact_is_available() {
  local base_url="${1%/}"
  local artifact_path="$2"
  local headers
  local content_type
  local content_length

  if ! headers=$(curl "${curl_probe_opts[@]}" "${base_url}/${artifact_path}" 2> /dev/null); then
    return 1
  fi

  content_type=$(printf '%s\n' "${headers}" | tr -d '\r' | awk 'tolower($0) ~ /^content-type:/ { value = tolower($0) } END { print value }')
  if [[ "${content_type}" == *"text/html"* ]]; then
    return 1
  fi

  content_length=$(printf '%s\n' "${headers}" | tr -d '\r' | awk 'tolower($0) ~ /^content-length:/ { len = $2 + 0; if (len > max) max = len } END { if (max > 0) print max }')
  if [[ -n "${content_length}" && "${content_length}" -lt "${MIN_ARCHIVE_SIZE_BYTES}" ]]; then
    return 1
  fi
}

if [ "$#" -eq 0 ]; then
  echo "${OFFICIAL_DOWNLOAD_URL}"
  exit 0
fi

for url in $(candidate_urls); do
  available=true
  for artifact_path in "$@"; do
    if ! artifact_is_available "${url}" "${artifact_path}"; then
      available=false
      break
    fi
  done
  if [ "${available}" = true ]; then
    echo "$url"
    exit 0
  fi
done

echo "No Zig download mirror has the requested artifacts: $*" >&2
exit 1
