#!/bin/sh
#
# Copyright (c) 2026 devlive-community/pipeforge
#
# Licensed under the MIT License. See the LICENSE file in the
# project root for full license text.
#
# The master process: pipeforge-master.sh <start|stop|status|restart|run> [options]; see pipeforge-daemon.sh.

exec "$(dirname "$0")/pipeforge-daemon.sh" master "$@"
