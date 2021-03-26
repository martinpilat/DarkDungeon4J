#!/bin/bash

set -x

python fast-downward.py --alias lama-first --plan-file $3 $1 $2 &>result.txt
