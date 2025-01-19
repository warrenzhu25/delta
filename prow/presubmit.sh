#!/bin/bash
set -euxo pipefail

echo "Running in " "$(pwd)"
printenv

cd /home/prow/go/src/dataproc/third_party/delta-io/delta
build/sbt compile test
