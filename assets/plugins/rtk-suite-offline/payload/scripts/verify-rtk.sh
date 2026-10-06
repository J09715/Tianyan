#!/bin/sh
set -eu

test -x /opt/tianyan/bin/rtk || { echo "rtk binary is missing or not executable" >&2; exit 1; }
/opt/tianyan/bin/rtk --version || exit 1
