#!/bin/bash

set -x

python fast-downward.py --overall-time-limit 2 --alias lama-first --plan-file $3 $1 $2 &>result.txt
