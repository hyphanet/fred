#!/bin/bash

#run script every 5 hours
#Not too often (reduce network load and data size), but still gets all
#times of day
if [ $((`date --utc +%s`/3600 % 5)) -eq 0 ]; then
  ./probe_test.sh 120
fi
