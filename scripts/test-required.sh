#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

for option in "$@"; do
    if [[ "$option" != "--no-daemon" ]]; then
        echo "Unsupported option: $option (only --no-daemon is allowed; this runner always executes the full suite)" >&2
        exit 2
    fi
done

# Fail before Gradle rather than letting @Testcontainers(disabledWithoutDocker = true)
# turn unavailable Docker into a green build with skipped integration cases.
if ! docker_api_range="$(docker version --format '{{.Server.MinAPIVersion}} {{.Server.APIVersion}}' 2>/dev/null)"; then
    echo "Docker is unavailable. Start Docker/OrbStack and confirm 'docker version' can reach its daemon." >&2
    exit 1
fi
read -r docker_min_api docker_server_api <<< "$docker_api_range"
if [[ -z "${docker_server_api:-}" ]]; then
    echo "Docker daemon did not report its API version; cannot run required Testcontainers verification." >&2
    exit 1
fi

docker_java_api="$(python3 - "$docker_min_api" "$docker_server_api" "${DOCKER_JAVA_API_VERSION:-}" <<'PY'
import re
import sys

minimum, server, override = sys.argv[1:]
def version(value):
    if not re.fullmatch(r"\d+\.\d+", value or ""):
        raise SystemExit(f"Invalid Docker API version: {value!r}")
    return tuple(map(int, value.split(".")))

server_version = version(server)
minimum_version = version(minimum) if minimum else min((1, 40), server_version)
if override:
    selected = version(override)
    if not minimum_version <= selected <= server_version:
        raise SystemExit(
            f"DOCKER_JAVA_API_VERSION={override} is outside daemon API range "
            f"{minimum or 'unknown'}..{server}"
        )
else:
    selected = max((1, 40), minimum_version)
    if selected > server_version:
        selected = server_version
print(".".join(map(str, selected)))
PY
)" || exit 1

# docker-java 3.4.1 defaults to API 1.32. Use the daemon's minimum supported API
# (at least 1.40 where possible); scope the system property to this Gradle process.
echo "Docker API range: ${docker_min_api:-unknown}..${docker_server_api}; Testcontainers will use ${docker_java_api}."
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:+$JAVA_TOOL_OPTIONS }-Dapi.version=$docker_java_api"

./gradlew --rerun-tasks test jacocoTestReport "$@"

python3 - <<'PY'
import glob
import sys
import xml.etree.ElementTree as ET

files = sorted(glob.glob("build/test-results/test/TEST-*.xml"))
skipped = []
executed = 0
for path in files:
    root = ET.parse(path).getroot()
    for case in root.iter("testcase"):
        executed += 1
        if int(case.attrib.get("skipped", "0")) > 0 or case.find("skipped") is not None:
            marker = case.find("skipped")
            reason = marker.attrib.get("message", "") if marker is not None else ""
            skipped.append((case.attrib.get("classname", "?"), case.attrib.get("name", "?"), reason))

if not files or not executed:
    print("Required verification found no JUnit test cases.", file=sys.stderr)
    sys.exit(1)

if skipped:
    print("Required integration verification had skipped tests:", file=sys.stderr)
    for class_name, test_name, reason in skipped:
        print(f"  {class_name}#{test_name}" + (f" — {reason}" if reason else ""), file=sys.stderr)
    print("Check Docker availability and the Testcontainers diagnostics in the Gradle output.", file=sys.stderr)
    sys.exit(1)

print(f"Required full-suite verification passed: {executed} test cases across {len(files)} reports, no skips.")
PY
