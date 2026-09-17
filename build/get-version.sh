#!/bin/bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo >&2 "ERROR: Invalid get-version.sh invocation."
  echo >&2 "Usage: $0 <is-release:true|false>"
  exit 2
fi

IS_M2RELEASEBUILD=$1

if [[ "${IS_M2RELEASEBUILD}" != "true" && "${IS_M2RELEASEBUILD}" != "false" ]]; then
  echo >&2 "ERROR: Invalid release flag '${IS_M2RELEASEBUILD}'."
  echo >&2 "Expected 'true' or 'false'."
  exit 2
fi

POM_VERSION=$(mvn -q -Dexec.executable="echo" -Dexec.args='${project.version}' --non-recursive exec:exec)

if [[ -z "${POM_VERSION//[[:space:]]/}" ]]; then
  echo >&2 "ERROR: Maven returned an empty project version."
  exit 1
fi

if [[ "${IS_M2RELEASEBUILD}" == "true" ]]; then
  POM_VERSION=${POM_VERSION%-SNAPSHOT}
fi

echo "${POM_VERSION}"
